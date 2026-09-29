"""The voice engine of the sidecar: the shared audio machine, answering with the core's words.

`SidecarVoice` is `VoiceEngine` (voice/engine.py: capture, echo gate, VAD, wake word, listening
windows, speculative STT and its filters, chunking, synthesis, playback, barge-in) whose answers
come from Marvin's core instead of a model in this process:

1. a question is decided: `Heard` goes to the core (uid, text, language, source, the latency so
   far), and the voice is "thinking";
2. the core answers with `ReplyStart` (for that uid), then `TextPiece`s as its model writes, maybe
   a `Filler` while a tool runs, then `ReplyEnd`;
3. the pieces are split into sentences and spoken as they come (first clause early), exactly as
   the in-process assistant speaks its model's stream; `SayProgress` follows each piece;
4. at the end `ReplySpoken` (text said, latencies: first_chunk, tts, audio_start, filler_start,
   total), or `Interrupted` (+ `ReplySpoken` with interrupted) after a barge-in or Stop.

One thought, one question: when the person goes on right after a question (before its answer is
heard), or cuts the answer to go on, the pending question is cancelled (`Interrupted` "merged", or
"barge-in") and a `Heard` with both texts follows, listing the questions it `continues`.

Proactive speech: `Say` (a whole text), or a `ReplyStart` without a question (streamed); both are
skipped with `Interrupted` "busy" while a conversation is going on, unless forced.

Every live signal of the engine (status, level, utterance, partial, say, ignored, muted) becomes
its `VoiceToCore` message. Messages are handed to `send`, from the engine's threads.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import queue
import threading
import time
from typing import Callable

from ...voice import persona
from ...voice.engine import Status, VoiceEngine, _Job
from ...voice.text import SentenceSplitter, clean_for_speech
from .contract import voice_pb2 as pb

log = logging.getLogger("marvin.sidecar.voice")

STATES = {Status.IDLE.value: pb.Status.IDLE, Status.LISTENING.value: pb.Status.LISTENING,
          Status.THINKING.value: pb.Status.THINKING, Status.SPEAKING.value: pb.Status.SPEAKING}
UTTERANCE = {"start": pb.Utterance.START, "end": pb.Utterance.END, "done": pb.Utterance.DONE}


class _Reply:
    """What the core writes for one answer: ("start" | "text" | "filler" | "end", value) items."""

    def __init__(self):
        self.items: queue.Queue = queue.Queue()
        self.reply_id = 0


class SidecarVoice(VoiceEngine):
    """See the module docstring. `send(VoiceToCore)` delivers messages to the core; `names` are
    the backends in use, for `Status` ({"stt": ..., "tts": ...})."""

    def __init__(self, *args, send: Callable[[pb.VoiceToCore], None], names: dict | None = None,
                 reply_timeout_s: float = 30.0, **kw):
        self._send = send
        self.names = dict(names or {})
        self.reply_timeout_s = reply_timeout_s
        self._replies_lock = threading.Lock()
        self._by_uid: dict[int, _Reply] = {}         # answers awaited or being spoken, by question uid
        self._by_id: dict[int, _Reply] = {}          # the same (and proactive ones), by reply id
        super().__init__(*args, **kw)
        self.add_listener(self._on_event)

    # ------------------------------------------------------------ from the core

    def reply_start(self, m: pb.ReplyStart) -> None:
        with self._replies_lock:
            if m.utterance_uid:
                reply = self._by_uid.get(m.utterance_uid)
                if reply is None:
                    log.debug("reply %d: question %d is not awaited any more", m.reply_id, m.utterance_uid)
                    return
            else:
                reply = _Reply()
            reply.reply_id = m.reply_id
            self._by_id[m.reply_id] = reply
        reply.items.put(("start", m))
        if not m.utterance_uid:                          # proactive, streamed
            if not self.say("", m.language or None, reply_id=m.reply_id):
                self._forget(reply_id=m.reply_id)
                self._interrupted(m.reply_id, "busy", 0)

    def text(self, m: pb.TextPiece) -> None:
        self._put(m.reply_id, ("text", m.text))

    def filler(self, m: pb.Filler) -> None:
        self._put(m.reply_id, ("filler", m.text))

    def reply_end(self, m: pb.ReplyEnd) -> None:
        self._put(m.reply_id, ("end", m.error))

    def say_now(self, m: pb.Say) -> None:
        if not self.say(m.text, m.language or None, m.force, reply_id=m.reply_id):
            self._interrupted(m.reply_id, "busy", 0)

    def _put(self, reply_id: int, item: tuple) -> None:
        with self._replies_lock:
            reply = self._by_id.get(reply_id)
        if reply is not None:
            reply.items.put(item)

    def _forget(self, uid: int = 0, reply_id: int = 0) -> None:
        with self._replies_lock:
            reply = self._by_uid.pop(uid, None) if uid else None
            rid = reply_id or (reply.reply_id if reply is not None else 0)
            if rid:
                self._by_id.pop(rid, None)

    # ------------------------------------------------------------ the engine's hooks

    def _prepare(self, job: _Job) -> None:
        with self._replies_lock:
            self._by_uid[job.uid] = _Reply()

    def _dropped(self, job: _Job) -> None:
        with self._replies_lock:
            reply = self._by_uid.get(job.uid) if job.uid else self._by_id.get(job.reply_id)
        rid = reply.reply_id if reply is not None else job.reply_id
        self._forget(job.uid, rid)
        self._interrupted(rid, job.cancel_reason or "stop", job.uid)

    def _answer(self, job: _Job) -> None:
        with self._replies_lock:
            reply = self._by_uid.get(job.uid)
        if reply is None:                   # cannot happen: _prepare made it
            reply = _Reply()
        lat: dict[str, float] = {}
        try:
            text, answer = self._speak_reply(job, reply, lat, wait_start=True)
        finally:
            self._forget(job.uid, reply.reply_id)
        lat["total"] = time.monotonic() - job.t_heard
        if job.cancel.is_set():
            self._interrupted(reply.reply_id, job.cancel_reason or "stop", job.uid)
        elif answer:
            self._last_reply = text         # "oui" / "non" follow-ups
        self._spoken(reply.reply_id, job.uid, text, lat, job.cancel.is_set())

    def _say(self, job: _Job) -> None:
        job.t_heard = time.monotonic()
        lat: dict[str, float] = {}
        with self._replies_lock:
            reply = self._by_id.get(job.reply_id) if job.reply_id and not job.text else None
        try:
            if reply is not None:
                text, _ = self._speak_reply(job, reply, lat, wait_start=False)
            else:
                text = self._speak_all(job, lambda emit: emit(job.text), lat)
        finally:
            self._forget(reply_id=job.reply_id)
        lat["total"] = time.monotonic() - job.t_heard
        if job.cancel.is_set():
            self._interrupted(job.reply_id, job.cancel_reason or "stop", 0)
        self._spoken(job.reply_id, 0, text, lat, job.cancel.is_set())

    def _speak_reply(self, job: _Job, reply: _Reply, lat: dict, wait_start: bool) -> tuple[str, bool]:
        """Speaks what the core writes for `reply` as it comes. Returns (what was said, whether an
        answer was said: not only a filler or an error sentence)."""
        t = time.monotonic()
        answer: list[str] = []

        def produce(emit):
            def say(s: str) -> None:
                lat.setdefault("first_chunk", time.monotonic() - t)
                answer.append(s)
                emit(s)

            splitter = SentenceSplitter()
            started = not wait_start
            last = time.monotonic()
            while not job.cancel.is_set():
                try:
                    kind, value = reply.items.get(timeout=0.05)
                except queue.Empty:
                    if time.monotonic() - last > self.reply_timeout_s:
                        log.warning("no answer from the core after %.0f s", self.reply_timeout_s)
                        emit(persona.phrase("error", job.language))
                        return
                    continue
                last = time.monotonic()
                if kind == "start":
                    started = True
                    job.reply_id = value.reply_id
                    job.language = value.language or job.language
                    lat["reply_start"] = time.monotonic() - t
                elif not started:
                    continue
                elif kind == "text":
                    for s in splitter.feed(value):
                        say(s)
                elif kind == "filler":
                    emit.filler(value)
                elif kind == "end":
                    for s in splitter.flush():
                        say(s)
                    if value and not clean_for_speech(" ".join(answer)):
                        emit(persona.phrase("llm_down" if value == "llm_down" else "error", job.language))
                    return

        text = self._speak_all(job, produce, lat)
        return text, bool(clean_for_speech(" ".join(answer)))

    # ------------------------------------------------------------ to the core

    def _interrupted(self, reply_id: int, reason: str, uid: int) -> None:
        self._send(pb.VoiceToCore(interrupted=pb.Interrupted(reply_id=reply_id, reason=reason, utterance_uid=uid)))

    def _spoken(self, reply_id: int, uid: int, text: str, lat: dict, interrupted: bool) -> None:
        self._send(pb.VoiceToCore(spoken=pb.ReplySpoken(
            reply_id=reply_id, utterance_uid=uid, text=text, interrupted=interrupted,
            latency={k: round(float(v), 4) for k, v in lat.items()})))

    def status_message(self) -> pb.VoiceToCore:
        s = pb.Status(state=STATES[self.status], muted=self.muted, stt=self.names.get("stt", ""),
                      tts=self.names.get("tts", ""), hearing=self.status == Status.LISTENING.value and self.hearing)
        left = self.listen_remaining()
        if left is not None:
            s.listen_s = round(left, 3)
        return pb.VoiceToCore(status=s)

    def _on_event(self, kind: str, d: dict) -> None:
        if kind in ("status", "muted"):
            msg = self.status_message()
        elif kind == "level":
            msg = pb.VoiceToCore(level=pb.Level(mic=d["mic"], speech=bool(d["speech"]), gated=bool(d["gated"])))
        elif kind == "utterance":
            msg = pb.VoiceToCore(utterance=pb.Utterance(state=UTTERANCE[d["state"]], uid=d["uid"]))
            if d["state"] != "end" and self.status == Status.LISTENING.value:
                self._send(msg)                 # then the window's state: frozen while they talk, or its time left
                msg = self.status_message()
        elif kind == "partial":
            msg = pb.VoiceToCore(partial=pb.Partial(uid=d["uid"], text=d["text"]))
        elif kind == "say":
            msg = pb.VoiceToCore(say=pb.SayProgress(reply_id=d.get("reply_id", 0), text=d["text"],
                                                    seconds=d["seconds"], envelope=d["envelope"]))
        elif kind == "ignored":
            ig = pb.Ignored(text=d.get("text", ""), reason=d.get("reason", ""))
            if d.get("dbfs") is not None:
                ig.dbfs = d["dbfs"]
            msg = pb.VoiceToCore(ignored=ig)
        elif kind == "heard":
            msg = pb.VoiceToCore(heard=pb.Heard(
                uid=d["uid"], text=d["text"], raw=d.get("raw", ""), language=d["language"],
                source=d.get("source", "voice"), wall_time=d["t"], continues=d.get("continues", ()),
                latency={k: round(float(v), 4) for k, v in self.last_latency.items()}))
        else:
            return
        self._send(msg)
