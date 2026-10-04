"""The voice engine of the sidecar: the shared audio machine, answering with the core's words.

`SidecarVoice` is `VoiceEngine` (voice/engine.py: capture, echo gate, VAD, wake word, listening
windows, speculative STT and its filters, synthesis, playback, barge-in) whose answers come from
Marvin's core:

1. a question is decided: `Heard` goes to the core (uid, text, language, source, the latency so
   far), and the voice is "thinking";
2. the core answers with `ReplyStart` (for that uid), then `TextPiece`s as its model writes, maybe
   a `Filler` while a tool runs, then `ReplyEnd`. Each piece is one clause, already cut, cleaned
   for speech and tagged with its language by the core (contract version 2): it is spoken as it
   is, in that order (the first clause early). If the answer failed, the core sends the sentence
   that says so;
3. `SayProgress` follows each clause as it is queued on the speaker;
4. at the end `ReplySpoken` (text said, latencies: first_chunk, tts, audio_start, filler_start,
   total), or `Interrupted` (+ `ReplySpoken` with interrupted) after a barge-in or Stop.

One thought, one question: when the person goes on right after a question (before its answer is
heard), or cuts the answer to go on, the pending question is cancelled (`Interrupted` "merged", or
"barge-in") and a `Heard` with both texts follows, listing the questions it `continues`.

Proactive speech: `Say` (its clauses), or a `ReplyStart` without a question (streamed); both are
skipped with `Interrupted` "busy" while a conversation is going on, unless forced.

Earcons: `Earcon` plays a short sound on the device (the core decides when, docs/voice.md).

Every live signal of the engine (status, level, utterance, partial, say, ignored, muted, and the ears'
diagnostics, hearing) becomes its `VoiceToCore` message. Messages are handed to `send`, from the engine's threads.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import queue
import threading
import time
from typing import Callable

from ...voice.engine import Status, VoiceEngine, _Job
from ...voice.speaker import Voiceprint
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
        self._put(m.reply_id, ("text", (m.text, m.language or None)))

    def filler(self, m: pb.Filler) -> None:
        self._put(m.reply_id, ("filler", (m.text, m.language or None)))

    def reply_end(self, m: pb.ReplyEnd) -> None:
        self._put(m.reply_id, ("end", m.error))

    def say_now(self, m: pb.Say) -> None:
        clauses = [(c.text, c.language or m.language or None) for c in m.clauses] or [(m.text, m.language or None)]
        if not self.say(m.text, m.language or None, m.force, reply_id=m.reply_id, clauses=clauses):
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
        if "playback_start" in lat:
            log.info("first sound %.2f s after the question: reply %.2f, first clause %.2f, synthesis %.2f, speaker %.2f",
                     lat["playback_start"], lat.get("reply_start", 0.0), lat.get("first_chunk", 0.0), lat.get("tts", 0.0),
                     lat["playback_start"] - lat.get("audio_start", lat["playback_start"]))
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
                clauses = job.clauses or [(job.text, job.language)]
                text = self._speak_all(job, lambda emit: [emit(t, lang) for t, lang in clauses], lat)
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
            def say(s: str, language: str | None) -> None:
                if not s.strip():
                    return
                lat.setdefault("first_chunk", time.monotonic() - t)
                answer.append(s)
                emit(s, language)

            started = not wait_start
            last = time.monotonic()
            while not job.cancel.is_set():
                try:
                    kind, value = reply.items.get(timeout=0.05)
                except queue.Empty:
                    if time.monotonic() - last > self.reply_timeout_s:
                        log.warning("no answer from the core after %.0f s", self.reply_timeout_s)
                        self.earcon("error")
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
                    say(*value)
                elif kind == "filler":
                    emit.filler(*value)
                elif kind == "end":
                    return

        text = self._speak_all(job, produce, lat)
        return text, bool(" ".join(answer).strip())

    # ------------------------------------------------------------ to the core

    def _interrupted(self, reply_id: int, reason: str, uid: int) -> None:
        self._send(pb.VoiceToCore(interrupted=pb.Interrupted(reply_id=reply_id, reason=reason, utterance_uid=uid)))

    def _spoken(self, reply_id: int, uid: int, text: str, lat: dict, interrupted: bool) -> None:
        self._send(pb.VoiceToCore(spoken=pb.ReplySpoken(
            reply_id=reply_id, utterance_uid=uid, text=text, interrupted=interrupted,
            latency={k: round(float(v), 4) for k, v in lat.items()})))

    def status_message(self) -> pb.VoiceToCore:
        s = pb.Status(state=STATES[self.status], muted=self.muted, stt=self.names.get("stt", ""),
                      tts=self.names.get("tts", ""), hearing=self.status == Status.LISTENING.value and self.hearing,
                      asked=self.listen_asked, device=self.device.name)
        left = self.listen_remaining()
        if left is not None:
            s.listen_s = round(left, 3)
        return pb.VoiceToCore(status=s)

    def _on_event(self, kind: str, d: dict) -> None:
        if kind in ("status", "muted"):
            msg = self.status_message()
        elif kind == "level":
            lv = pb.Level(mic=d["mic"], speech=bool(d["speech"]), gated=bool(d["gated"]))
            if d.get("dbfs") is not None:
                lv.dbfs = d["dbfs"]
            msg = pb.VoiceToCore(level=lv)
        elif kind == "hearing":
            msg = pb.VoiceToCore(hearing=hearing_message(d, self.names.get("stt", "")))
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
            h = pb.Heard(uid=d["uid"], text=d["text"], raw=d.get("raw", ""), language=d["language"],
                         source=d.get("source", "voice"), wall_time=d["t"], continues=d.get("continues", ()),
                         latency={k: round(float(v), 4) for k, v in self.last_latency.items()},
                         speaker=d.get("speaker", ""))
            if d.get("speaker_score") is not None:
                h.speaker_score = d["speaker_score"]
            msg = pb.VoiceToCore(heard=h)
        elif kind == "enroll":
            msg = pb.VoiceToCore(enroll=enroll_progress(d))
        else:
            return
        self._send(msg)


_HEARING_OPTIONAL = ("complete", "probability", "turn_s", "dbfs", "stt_s", "language_prob", "speaker_score")
_HEARING_FIELDS = ("uid", "duration_s", "text", "language", "speculative", "wake", "speaker", "outcome", "reason",
                   "vad", "turn")


def hearing_message(d: dict, stt: str = "") -> pb.Hearing:
    """A "hearing" event of the engine (the ears' diagnostics) as the contract's message; `stt`: the speech
    recognition in use, named on utterances that were transcribed."""
    h = pb.Hearing(kind=d["kind"], wall_time=float(d.get("t", 0.0)))
    for k in _HEARING_FIELDS:
        if d.get(k) is not None:
            setattr(h, k, d[k])
    for k in _HEARING_OPTIONAL:
        if d.get(k) is not None:
            setattr(h, k, d[k])
    if d["kind"] == "utterance" and d.get("stt_s") is not None:
        h.stt = stt
    return h


def enroll_progress(d: dict) -> pb.EnrollProgress:
    """An "enroll" event of the engine (voice/enroll.py) as the contract's message; "done" carries
    the device's thresholds and the voiceprint (the mean of the sentences' embeddings)."""
    state = d["state"]
    m = pb.EnrollProgress(state=state, index=int(d.get("index", 0) or 0), message=d.get("message", ""),
                          seconds=float(d.get("seconds", 0.0) or 0.0))
    if state in ("sentence", "heard"):
        m.text = d.get("text", "")
    if state == "heard":
        m.match, m.dbfs = float(d.get("match", 0.0)), float(d.get("dbfs", 0.0))
    if state == "done":
        cal, p = d["calibration"], d["device"]
        r = m.result
        r.name = d.get("name", "")
        r.device.kind, r.device.robot = p.kind, p.robot
        r.noise_dbfs, r.speech_dbfs = cal.noise_dbfs, cal.speech_dbfs
        r.vad_min_dbfs, r.speech_min_dbfs, r.vad_threshold = cal.vad_min_dbfs, cal.speech_min_dbfs, cal.vad_threshold
        r.warnings.extend(cal.warnings)
        r.transcripts.extend(d.get("transcripts", []))
        if d.get("embeddings") and r.name:
            vp = Voiceprint.of(r.name, d["embeddings"], d.get("model", ""))
            r.voiceprint.name, r.voiceprint.model = vp.name, vp.model
            r.voiceprint.embedding.extend(float(x) for x in vp.embedding)
    return m
