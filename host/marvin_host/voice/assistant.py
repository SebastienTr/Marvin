"""The voice assistant: hears "Marvin, ...", thinks with a local model, answers aloud.

    source -> [capture thread: echo gate] -> VAD segmenter -> wake word / Whisper -> question
           -> LLM (streamed) -> first clause / sentences -> [speaker thread: TTS -> sink]

States: idle -> listening -> thinking -> speaking -> (listening for a follow-up) -> idle.

- "Marvin, what time is it?" in one breath: the name is stripped, the rest is the question.
- "Marvin." alone: a soft chime, then the next utterance within `listen_window_s` is the question.
- After an answer, a follow-up question needs no name for `follow_up_s`.
- Barge-in: saying "Marvin" while it thinks stops it. While it speaks, only in `duplex` mode
  (half duplex does not hear anything while it speaks), and never while its own reply contains
  the name.

Control from code (the app uses these): `ask(text)` (a typed question, answered aloud),
`listen_now()` (a listening window without the wake word, like saying "Marvin." alone),
`mute(True)` (the microphone is ignored until `mute(False)`), `stop_speaking()`. Several
listeners can follow the conversation with `add_listener(fn)`: `fn(kind, data)` receives
"status", "heard", "reply", "ignored" and "muted" (see `add_listener`).

It never answers itself (echo.py): in half duplex (the default) the microphone is muted from the
first word until `echo_tail_s` after the speaker has played the last one, and whatever is heard in
any state is ignored if it repeats what Marvin said recently.

Threads: a capture thread reads the source as it comes and applies the echo gate at capture
time; `run()` does the listening (VAD, Whisper) in the caller's thread (or a background one with
`start()`); a mouth thread asks the model and a speaker thread synthesises and plays, so the
first clause is heard while the model is still writing. Listening decisions use audio time
(seconds of audio captured), so a recording replays the same way at any speed.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import concurrent.futures
import copy
import importlib.util
import logging
import queue
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable

import numpy as np

from ..audio import FRAME_MS, SAMPLE_RATE, AudioSink, AudioSource
from . import filters, persona
from .echo import EchoFilter, EchoGate
from .llm import LLM, LLMUnavailable
from .stt import STT, Transcript
from .text import SentenceSplitter, clean_for_speech, guess_language
from .tts import TTS
from .vad import Segment, Segmenter, SegmenterConfig, Vad, make_vad
from .wake import TranscriptWakeWord, WakeMatch, WakeWordDetector, match_wake_word

log = logging.getLogger("marvin.voice")


class Status(str, Enum):
    IDLE = "idle"
    LISTENING = "listening"
    THINKING = "thinking"
    SPEAKING = "speaking"


@dataclass
class VoiceConfig:
    # languages
    language: str | None = None             # force one language (STT and replies); None = follow the speaker
    languages: tuple[str, ...] = ("fr", "en")   # languages the speaker is expected to use
    default_language: str = "fr"            # until someone speaks
    # models (used when the components are not passed in)
    stt: str = "auto"                       # auto (MLX on Apple Silicon if installed), mlx, faster-whisper
    stt_model: str | None = None            # None: the backend's default (turbo on MLX, small on CPU)
    llm_model: str = "qwen3:4b-instruct"
    ollama_host: str = "http://localhost:11434"
    tts: str = "auto"                       # auto, say, piper, espeak
    tts_voice: str | None = None
    vad: str = "auto"                       # auto, webrtc, energy
    # behaviour
    wake: bool = True                       # False: every utterance is a question
    listen_window_s: float = 6.0            # after "Marvin." alone
    follow_up_s: float = 5.0                # after an answer, a question without the name
    barge_in: bool = True
    chime: bool = True                      # soft chime when listening after "Marvin."
    memory_turns: int = 8                   # question/answer pairs kept (then the older half is dropped)
    memory_reset_s: float = 180.0           # forget the conversation after this much silence
    # echo and latency
    duplex: bool = False                    # True: keep listening while speaking (headset, echo-cancelling robot)
    echo_tail_s: float = 0.8                # half duplex: still deaf this long after the speaker stops
    speculative_stt: bool = True            # start Whisper during the pause that may end the question
    speculate_after_s: float = 0.25         # ...after this much silence
    segmenter: SegmenterConfig = field(default_factory=SegmenterConfig)


@dataclass
class _Job:
    kind: str                               # "ask" or "say"
    text: str
    language: str
    t_heard: float = 0.0                    # monotonic time the question's segment was complete
    cancel: threading.Event = field(default_factory=threading.Event)


def chime(rate: int = SAMPLE_RATE) -> np.ndarray:
    """Two soft notes, rising: "I'm listening"."""
    out = []
    for f in (587.3, 880.0):                # D5, A5
        t = np.arange(int(0.09 * rate)) / rate
        env = np.minimum(1, t / 0.01) * np.exp(-t / 0.05)
        out.append(np.sin(2 * np.pi * f * t) * env * 2500)
    return np.concatenate(out).astype(np.int16)


_DONE = object()


class VoiceAssistant:
    """See the module docstring. Components not passed in are built from `config`."""

    def __init__(self, source: AudioSource, sink: AudioSink, brain=None, config: VoiceConfig | None = None, *,
                 stt: STT | None = None, llm: LLM | None = None, tts: TTS | None = None,
                 vad: Vad | None = None, wake: WakeWordDetector | None = None,
                 on_status: Callable[[str], None] | None = None,
                 on_transcript: Callable[[str], None] | None = None,
                 on_reply: Callable[[str], None] | None = None):
        self.config = c = config or VoiceConfig()
        self.source, self.sink, self.brain = source, sink, brain
        languages = (c.language,) if c.language else c.languages
        self.language = c.language or c.default_language    # of the conversation
        if stt is None:
            from .stt import make_stt
            stt = make_stt(c.stt, c.stt_model, languages)
        if llm is None:
            from .llm import OllamaLLM
            llm = OllamaLLM(c.llm_model, c.ollama_host)
            if llm.available():     # rehearse a real first question: model loaded, prompt cached
                llm.warm_up(persona.persona_prompt(self.language), persona.user_message("Bonjour."))
        if tts is None:
            from .tts import make_tts
            tts = make_tts(c.tts, c.tts_voice, c.language or c.default_language)
            t = time.monotonic()
            from .tts import MacSayTTS
            if isinstance(tts, MacSayTTS) and importlib.util.find_spec("piper") is None:
                log.info("tip: pip install piper-tts for ~5x faster speech (Marvin uses it automatically)")
            try:                                # load the voice now (Piper: seconds), not at the first answer
                tts.synthesize("Bonjour." if self.language == "fr" else "Hello.", self.language)
                log.info("speech synthesis ready in %.1f s", time.monotonic() - t)
            except Exception as e:
                log.warning("speech synthesis failed to start: %s", e)
        self.stt, self.llm, self.tts = stt, llm, tts
        self.wake = wake or TranscriptWakeWord(stt)
        self.segmenter = Segmenter(vad or make_vad(c.vad), c.segmenter)
        self.on_status, self.on_transcript, self.on_reply = on_status, on_transcript, on_reply
        self._listeners: list[Callable[[str, dict], None]] = []
        self._muted = False

        self.echo = EchoFilter()
        self._cap_n = 0                                      # frames captured
        self._level_peak = 0.0                               # live signals for the app (_live)
        self._level_n = 0
        self._utt: int | None = None                         # utterance being heard (its uid)
        self._seg_started: float | None = None               # start of the utterance being judged
        self.gate = EchoGate(sink, c.echo_tail_s, enabled=not c.duplex, clock=self._capture_time)
        self.history: list[dict] = []
        self._last_reply = ""                                # for "oui" / "non" follow-ups
        self.last_latency: dict[str, float] = {}             # of the last answer, seconds
        self._status = Status.IDLE
        self._lock = threading.RLock()
        self._stt_lock = threading.Lock()
        self._listen_until: float | None = None              # audio time
        self._asked_to_listen = False    # the window was asked for (Talk now, "Marvin." alone): take what comes
        self._listen_from = 0.0                              # audio time: earlier utterances are not follow-ups
        self._audio_now = 0.0
        self._last_turn = 0.0                                # monotonic
        self._frames: queue.Queue = queue.Queue()            # (frame, gated, capture time) from the capture thread
        self._jobs: queue.Queue[_Job | None] = queue.Queue()
        self._pending = 0                                    # jobs queued or running
        self._current: _Job | None = None
        self._spec: tuple[int, int, concurrent.futures.Future] | None = None   # (uid, voiced, transcript)
        self._spec_pool = concurrent.futures.ThreadPoolExecutor(1, thread_name_prefix="marvin-voice-stt")
        self._closed = threading.Event()
        self._thread: threading.Thread | None = None
        self._capture: threading.Thread | None = None
        self._mouth = threading.Thread(target=self._mouth_loop, name="marvin-voice-mouth", daemon=True)
        self._mouth.start()

    # ------------------------------------------------------------ public API

    @property
    def status(self) -> str:
        return self._status.value

    def run(self) -> None:
        """Listens until the source ends or `close()`; then finishes what it was saying."""
        self._capture = threading.Thread(target=self._capture_loop, name="marvin-voice-capture", daemon=True)
        self._capture.start()
        try:
            while True:
                item = self._frames.get()
                try:
                    if item is None or self._closed.is_set():
                        break
                    self._hear(*item)
                finally:
                    self._frames.task_done()
            seg = self.segmenter.flush()
            if seg is not None and not self._closed.is_set():
                self._on_segment(seg, time.monotonic())
        finally:
            self.wait_idle()

    def start(self) -> "VoiceAssistant":
        """`run()` in a background thread."""
        self._thread = threading.Thread(target=self.run, name="marvin-voice-ears", daemon=True)
        self._thread.start()
        return self

    def close(self) -> None:
        """Stops listening and speaking, and closes the source and the sink."""
        if self._closed.is_set():
            return
        self._closed.set()
        self._interrupt()
        self._jobs.put(None)
        self._frames.put(None)
        self.source.close()
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join(timeout=2)
        self._spec_pool.shutdown(wait=False)
        if hasattr(self.sink, "close"):
            self.sink.close()

    def wait_idle(self, timeout: float | None = None) -> bool:
        """Blocks until everything captured has been heard and nothing is queued, thought or
        spoken. False on timeout."""
        end = None if timeout is None else time.monotonic() + timeout
        while self._pending > 0 or self._frames.unfinished_tasks > 0:
            if self._closed.is_set():
                return not self._mouth.is_alive() or self._pending == 0
            if end is not None and time.monotonic() > end:
                return False
            time.sleep(0.005)
        return True

    def say(self, text: str, language: str | None = None, force: bool = False) -> bool:
        """Speaks `text` (proactive speech). Skipped, returning False, while a conversation is going
        on, unless `force`."""
        with self._lock:
            if not force and (self._status != Status.IDLE or self._pending > 0 or self.segmenter.in_speech):
                return False
            self._enqueue(_Job("say", text, language or self.language))
        return True

    def ask(self, text: str, language: str | None = None) -> None:
        """Asks a question as if it had been heard (a typed question): stops what Marvin is saying,
        then answers aloud. The language is `language`, else the forced one, else guessed from the
        text, else the conversation's."""
        text = text.strip()
        if not text:
            return
        c = self.config
        lang = language or c.language or guess_language(text, c.languages) or self.language
        if self._pending > 0 or self._status in (Status.THINKING, Status.SPEAKING):
            self._interrupt()
        self._submit(text, lang, source="typed")

    def listen_now(self) -> bool:
        """Opens a listening window without the wake word, as if "Marvin." had been said alone: the
        next utterance within `listen_window_s` is the question. Stops what Marvin is saying and
        unmutes the microphone. False once closed."""
        if self._closed.is_set():
            return False
        if self._pending > 0 or self._status in (Status.THINKING, Status.SPEAKING):
            self._interrupt()
        if self._muted:
            self.mute(False)
        # from what the listening has reached (it may lag behind the capture while Whisper works),
        # so the window is never already over when it opens
        self._listen(max(self._audio_now, self._capture_time() - 0.3), with_chime=self.config.chime)
        return True

    def stop_listening(self) -> bool:
        """Closes the listening window (Talk now pressed again). False if there was none."""
        with self._lock:
            if self._status != Status.LISTENING:
                return False
            self._listen_until = None
            self._set_status(Status.IDLE)
        return True

    def listen_remaining(self) -> float | None:
        """Seconds left in the listening window (None outside one); it waits while someone talks."""
        with self._lock:
            if self._status != Status.LISTENING or self._listen_until is None:
                return None
            return max(0.0, self._listen_until - self._audio_now)

    @property
    def muted(self) -> bool:
        return self._muted

    def mute(self, muted: bool = True) -> None:
        """Muted: everything the microphone captures is ignored (proactive speech, `ask` and
        `say` still work). A listening window is closed."""
        muted = bool(muted)
        if muted == self._muted:
            return
        self._muted = muted
        log.info("microphone %s", "muted" if muted else "unmuted")
        if muted:
            with self._lock:
                self._listen_until = None
                if self._status == Status.LISTENING:
                    self._set_status(Status.IDLE)
        self._emit("muted", muted=muted)

    def stop_speaking(self) -> bool:
        """Stops the answer being thought or spoken, and drops what was queued. True if there was
        something to stop."""
        with self._lock:
            busy = self._pending > 0 or self._status in (Status.THINKING, Status.SPEAKING)
        if busy:
            self._interrupt()
        return busy

    def add_listener(self, fn: Callable[[str, dict], None]) -> None:
        """Calls `fn(kind, data)` from the assistant's threads (keep it quick) for:

        - "status": {"status": "idle" | "listening" | "thinking" | "speaking"}
        - "heard": {"text", "language", "source": "voice" | "typed"}: a question Marvin answers
        - "reply": {"text", "language", "latency": {stage: seconds}, "interrupted": bool,
          "proactive": bool, "error": None | "llm_down" | "error", "hint": str}
        - "ignored": {"text", "reason"}: an utterance Marvin chose not to answer
        - "muted": {"muted": bool}
        - "level": {"mic": 0..1, "speech": bool, "gated": bool}: the microphone's loudness, ~16 Hz
        - "utterance": {"state": "start" | "end" | "done", "uid"}: someone talks, stops, is judged
        - "partial": {"uid", "text"}: what is understood so far of the utterance being heard
        - "say": {"text", "seconds", "envelope": [0..1 at 20 Hz]}: a piece of the reply, as it is
          queued on the speaker (it plays after the pieces before it)

        Every `data` also has "t", the wall-clock time (Unix seconds)."""
        self._listeners.append(fn)

    def remove_listener(self, fn: Callable[[str, dict], None]) -> None:
        if fn in self._listeners:
            self._listeners.remove(fn)

    def _emit(self, kind: str, **data) -> None:
        data["t"] = time.time()
        for fn in list(self._listeners):
            try:
                fn(kind, data)
            except Exception:
                log.exception("voice listener failed")

    # ------------------------------------------------------------ capture

    def _capture_time(self) -> float:
        return self._cap_n * FRAME_MS / 1000

    def _capture_loop(self) -> None:
        """Reads the source at its own pace, so the echo gate is applied when the sound is captured,
        however far behind the listening (Whisper) is."""
        try:
            for frame in self.source.frames():
                if self._closed.is_set():
                    break
                gated = self._muted or self.gate.closed()     # judged at the frame's start time
                self._cap_n += 1
                self._frames.put((frame, gated, time.monotonic()))
        except Exception:
            log.exception("audio source failed")
        finally:
            self._frames.put(None)

    # ------------------------------------------------------------ listening

    def _hear(self, frame: np.ndarray, gated: bool, t_cap: float) -> None:
        if gated:
            self.segmenter.advance()          # deaf: time goes on, a started utterance is dropped
            self._spec = None
            self._utterance_done()
        else:
            seg = self.segmenter.feed(frame)
            if seg is not None:
                self._utterance("end", seg.uid)
                try:
                    self._on_segment(seg, t_cap)
                finally:
                    self._utterance_done()
            else:
                if self.segmenter.in_speech and self._utt != self.segmenter.utterance_key[0]:
                    self._utterance_done()
                    self._utterance("start", self.segmenter.utterance_key[0])
                elif not self.segmenter.in_speech:
                    self._utterance_done()      # too short: dropped by the segmenter
                self._speculate()
        self._live_level(frame, gated)
        self._audio_now = self.segmenter.time
        self._tick()

    # ------------------------------------------------------------ live signals for the app

    LEVEL_EVERY = 3                         # frames per "level" event: 60 ms, about 16 per second

    def _live_level(self, frame: np.ndarray, gated: bool) -> None:
        """Microphone loudness (0..1, -60..-10 dBFS) at about 16 Hz, for the app's animations."""
        if not self._listeners:
            return
        rms = float(np.sqrt(np.mean(frame.astype(np.float32) ** 2))) if len(frame) else 0.0
        db = 20 * np.log10(max(rms, 1.0) / 32768)
        self._level_peak = max(self._level_peak, min(1.0, max(0.0, (db + 60) / 50)))
        self._level_n += 1
        if self._level_n >= self.LEVEL_EVERY:
            self._emit("level", mic=round(0.0 if (gated or self._muted) else self._level_peak, 3),
                       speech=self.segmenter.in_speech and not gated, gated=bool(gated))
            self._level_peak, self._level_n = 0.0, 0

    def _utterance(self, state: str, uid: int) -> None:
        """"start": someone started talking; "end": they stopped, the words are being understood;
        "done": decided (a "heard" or "ignored" event may have come just before)."""
        self._utt = uid if state != "done" else None
        self._emit("utterance", state=state, uid=uid)

    def _utterance_done(self) -> None:
        if self._utt is not None:
            self._utterance("done", self._utt)

    def _speculate(self) -> None:
        """During a pause that may end the utterance, start transcribing it: if the speaker does
        not go on, the transcript is ready (or nearly) when the pause is long enough to end it."""
        c, sg = self.config, self.segmenter
        if not c.speculative_stt or not sg.in_speech or sg.silence_s < c.speculate_after_s:
            return
        if self._pending > 0:                 # busy: only a barge-in could come, no need to hurry
            return
        if self._spec is not None and self._spec[:2] == sg.utterance_key:
            return
        pend = sg.pending()
        if pend is None or filters.speech_evidence(pend) is not None:
            return
        if isinstance(self.wake, TranscriptWakeWord) and not self.wake.candidate(pend.pcm) and c.wake:
            return
        fut = self._spec_pool.submit(self._transcribe, pend.pcm)
        self._spec = (pend.uid, pend.voiced, fut)
        fut.add_done_callback(lambda f, uid=pend.uid: self._partial(uid, f))

    def _partial(self, uid: int, fut: concurrent.futures.Future) -> None:
        """What was understood so far of the utterance being heard, shown live in the app."""
        if not self._listeners or fut.cancelled() or fut.exception() is not None:
            return
        tr = fut.result()
        text = (tr.text or "").strip()
        if text and not tr.rejected and not filters.hallucination_reason(text):
            self._emit("partial", uid=uid, text=text)

    def _set_status(self, s: Status) -> None:
        with self._lock:
            if s == self._status:
                return
            self._status = s
        log.debug("voice: %s", s.value)
        self._emit("status", status=s.value)
        if self.on_status:
            try:
                self.on_status(s.value)
            except Exception:
                log.exception("on_status failed")

    def _tick(self) -> None:
        """Closes the listening window when it expires."""
        with self._lock:
            if (self._status == Status.LISTENING and self._listen_until is not None
                    and self._audio_now > self._listen_until and not self.segmenter.in_speech):
                self._listen_until = None
                self._set_status(Status.IDLE)

    def _transcribe(self, pcm: np.ndarray) -> Transcript:
        with self._stt_lock:
            return self.stt.transcribe(pcm, self.config.language)

    def _transcript_for(self, seg: Segment, lat: dict) -> Transcript | None:
        """The speculative transcript of `seg` if it is still valid, else None."""
        spec, self._spec = self._spec, None
        if spec is None or spec[:2] != (seg.uid, seg.voiced):
            return None
        t = time.monotonic()
        try:
            tr = spec[2].result()
        except Exception:
            log.exception("speculative transcription failed")
            return None
        lat["stt"] = time.monotonic() - t     # what was left to wait for
        lat["speculative"] = 1.0
        return tr

    def _full_transcript(self, seg: Segment, lat: dict) -> Transcript:
        tr = self._transcript_for(seg, lat)
        if tr is None:
            tr = self._transcribe(seg.pcm)
            lat["stt"] = tr.seconds
        return tr

    def _wake_check(self, seg: Segment, lat: dict) -> WakeMatch | None:
        if isinstance(self.wake, TranscriptWakeWord):
            if not self.wake.candidate(seg.pcm):
                self._spec = None
                return None
            return self.wake.check(seg.pcm, self.config.language, transcript=self._full_transcript(seg, lat))
        t = time.monotonic()
        m = self.wake.check(seg.pcm, self.config.language)
        lat["wake"] = time.monotonic() - t
        return m

    def _reply_language(self, tr: Transcript | None) -> str:
        c = self.config
        if c.language:
            return c.language
        if tr is not None and tr.confident and tr.language in c.languages:
            self.language = tr.language
        return self.language

    def _own_voice(self, text: str, started: float | None = None) -> bool:
        if self.echo.is_own_voice(text, started):
            log.info("heard: %s (ignored: own voice)", text)
            self._emit("ignored", text=text, reason="own voice")
            return True
        return False

    def _ignored(self, text: str, reason: str, quiet: bool = False) -> None:
        (log.debug if quiet else log.info)("heard: %s (ignored: %s)", text or "…", reason)
        if not quiet:
            self._emit("ignored", text=text, reason=reason)

    def _close_conversation(self, text: str, reason: str) -> None:
        log.info("heard: %s (conversation closed: %s)", text, reason)
        self._emit("ignored", text=text, reason=f"conversation closed: {reason}")
        with self._lock:
            self._listen_until = None
            if self._status == Status.LISTENING:
                self._set_status(Status.IDLE)

    def _on_segment(self, seg: Segment, t_cap: float) -> None:
        c = self.config
        now_audio = self.segmenter.time
        lat = {"endpoint": max(0.0, now_audio - seg.t_speech_end) if seg.t_speech_end else c.segmenter.end_silence_s,
               "queue": max(0.0, time.monotonic() - t_cap)}
        t0 = t_cap                              # when the end of the question was captured
        self._seg_started = t_cap - max(0.0, now_audio - seg.t_start)   # when it began (monotonic)
        with self._lock:
            busy = self._status in (Status.THINKING, Status.SPEAKING) or self._pending > 0
            late = seg.t_start < self._listen_from - 0.05   # captured while it was speaking
            window = (self._listen_until is not None and not late and seg.t_start <= self._listen_until)

        no_name = not c.wake or window          # no wake word will confirm it is for Marvin
        weak = filters.speech_evidence(seg)
        if weak:
            self._spec = None
            self._ignored("", weak, quiet=not (no_name and not busy and not late))
            return

        if busy or late:
            # only "Marvin" counts, and not if Marvin is saying its own name right now
            if not (c.wake and c.barge_in):
                self._spec = None
                return
            if self._status == Status.SPEAKING and self.echo.current_has_wake_word():
                self._spec = None
                return
            m = self._wake_check(seg, lat)
            if m is None or (m.transcript is not None and self._own_voice(m.transcript.text, self._seg_started)):
                return
            if busy:
                log.info("barge-in")
                self._interrupt()
            self._handle_match(m, seg, t0, lat)
            return

        if no_name:
            tr = self._full_transcript(seg, lat)
            if self._own_voice(tr.text, self._seg_started):
                return
            bad = tr.rejected or filters.decoder_reason(tr.no_speech_prob, tr.avg_logprob, tr.compression_ratio)
            if bad or not tr.text.strip():
                self._ignored(tr.text, bad or "nothing understood")
                return
            rest = match_wake_word(tr.text)
            text = tr.text.strip() if rest is None else rest
            if rest is not None and not text:   # "Marvin." again: keep listening
                self._listen(seg.t_end)
                return
            if rest is None and not (window and self._asked_to_listen):
                # no name, and nobody asked Marvin to listen: be strict (filters.follow_up_decision).
                # After Talk now or "Marvin." alone, what comes is the question.
                # the language rule applies in a follow-up window; with --no-wake, the person
                # may start in any language
                conversation = self.language if window else (tr.language or self.language)
                decision, why = filters.follow_up_decision(text, tr.language, tr.language_prob, conversation,
                                                           self._last_reply)
                if decision == filters.CLOSE:
                    self._close_conversation(text, why)
                    return
                if decision == filters.IGNORE:
                    self._ignored(text, why)
                    return
            bad = filters.hallucination_reason(text)
            if bad:
                self._ignored(tr.text, bad)
                return
            self._submit(text, self._reply_language(tr), tr, t0, lat)
            return

        m = self._wake_check(seg, lat)
        if m is not None and not (m.transcript is not None and self._own_voice(m.transcript.text, self._seg_started)):
            self._handle_match(m, seg, t0, lat)

    def _handle_match(self, m: WakeMatch, seg: Segment, t0: float, lat: dict) -> None:
        tr = m.transcript
        query = m.query
        if tr is not None and (tr.rejected or filters.decoder_reason(tr.no_speech_prob, tr.avg_logprob,
                                                                     tr.compression_ratio)):
            self._ignored(tr.text, tr.rejected or "low confidence")
            return
        if query and filters.hallucination_reason(query):
            query = ""                          # "Marvin. Thank you." -> just the name
        if query is None:                       # a keyword model: transcribe the utterance ourselves
            tr = self._full_transcript(seg, lat)
            query = match_wake_word(tr.text)
            query = tr.text if query is None else query
        if query:
            self._submit(query, self._reply_language(tr), tr, t0, lat)
        else:
            self._reply_language(tr)
            self._listen(seg.t_end, with_chime=self.config.chime)

    def _listen(self, t_audio: float, with_chime: bool = False) -> None:
        with self._lock:
            self._asked_to_listen = True
            self._listen_until = t_audio + self.config.listen_window_s
            self._set_status(Status.LISTENING)
        if with_chime:
            self.sink.play(chime())

    def _submit(self, text: str, language: str, tr: Transcript | None = None, t0: float | None = None,
                lat: dict | None = None, source: str = "voice") -> None:
        log.info("heard (%s): %s", language, text)
        self.last_latency = dict(lat or {})
        self._emit("heard", text=text, language=language, source=source)
        if self.on_transcript:
            try:
                self.on_transcript(text)
            except Exception:
                log.exception("on_transcript failed")
        with self._lock:
            self._listen_until = None
            self._set_status(Status.THINKING)
            self._enqueue(_Job("ask", text, language, t0 or time.monotonic()))

    def _enqueue(self, job: _Job) -> None:
        with self._lock:
            self._pending += 1
            self._jobs.put(job)

    def _interrupt(self) -> None:
        """Stops what is being said and drops what was queued."""
        with self._lock:
            job = self._current
            if job is not None:
                job.cancel.set()
            while True:
                try:
                    j = self._jobs.get_nowait()
                except queue.Empty:
                    break
                if j is None:
                    self._jobs.put(None)
                    break
                self._pending -= 1
            self.sink.stop()

    # ------------------------------------------------------------ thinking and speaking

    def _mouth_loop(self) -> None:
        while True:
            job = self._jobs.get()
            if job is None or self._closed.is_set():
                self._current = None
                return
            self._current = job
            try:
                if job.kind == "ask":
                    self._answer(job)
                else:
                    self._say(job)
            except Exception:
                log.exception("voice job failed")
            finally:
                with self._lock:
                    self._current = None
                    self._pending -= 1
                    if self._pending == 0 and self._status in (Status.THINKING, Status.SPEAKING):
                        c = self.config
                        # anything captured before now (plus the echo tail) is not a follow-up
                        self._listen_from = self._capture_time() + (0.0 if c.duplex else c.echo_tail_s)
                        if job.kind == "ask" and not job.cancel.is_set() and c.wake and c.follow_up_s > 0:
                            self._listen_until = self._listen_from + c.follow_up_s
                            self._asked_to_listen = False
                            self._set_status(Status.LISTENING)
                        elif self._listen_until is None:
                            self._set_status(Status.IDLE)
                        else:
                            self._set_status(Status.LISTENING)

    def _messages(self, job: _Job) -> tuple[list[dict], str]:
        state, events = None, ()
        if self.brain is not None:
            state = copy.copy(self.brain.state)
            try:
                events = list(getattr(self.brain, "events", ()))
            except RuntimeError:            # the brain appended an event meanwhile: skip them this time
                events = ()
        now = time.monotonic()
        if self.history and now - self._last_turn > self.config.memory_reset_s:
            log.debug("conversation forgotten after %.0f s of silence", now - self._last_turn)
            self.history.clear()
        user = persona.user_message(job.text, state, events, language=job.language)
        return [{"role": "system", "content": persona.persona_prompt(job.language)}, *self.history,
                {"role": "user", "content": user}], user

    def _speaker(self, job: _Job, sentences: queue.Queue, lat: dict) -> None:
        """Speaker thread of one reply: synthesises each chunk and queues it on the sink, in order,
        while the model keeps writing."""
        first = True
        while True:
            s = sentences.get()
            if s is _DONE or job.cancel.is_set():
                return
            text = clean_for_speech(s)
            if not text:
                continue
            t = time.monotonic()
            try:
                # the voice follows the language actually written: a model that answers in English
                # despite the instruction is still spoken with an English voice, not a French accent
                voice_language = guess_language(text, self.config.languages) or job.language
                pcm, rate = self.tts.synthesize(text, voice_language)
            except Exception:                               # noqa: BLE001 - never kill the speaker thread
                log.exception("speech synthesis failed for %r", text)
                continue
            if job.cancel.is_set():
                return
            if first:
                lat["tts"] = time.monotonic() - t
                lat["audio_start"] = time.monotonic() - job.t_heard
                self.gate.speaking(True)
                self._set_status(Status.SPEAKING)
                first = False
            self.sink.play(pcm, rate)
            if self._listeners:
                self._emit("say", text=text, seconds=round(len(pcm) / rate, 3), envelope=envelope(pcm, rate))

    def _speak_all(self, job: _Job, produce: Callable[[Callable[[str], None]], None], lat: dict) -> str:
        """Runs `produce(emit)` (which calls emit(chunk) as text becomes available) with a speaker
        thread, waits until everything is played, and returns what was said."""
        sentences: queue.Queue = queue.Queue()
        said: list[str] = []

        def emit(chunk: str) -> None:
            said.append(chunk)
            self.echo.speaking(" ".join(said))      # filter it before it is even played
            sentences.put(chunk)

        spk = threading.Thread(target=self._speaker, args=(job, sentences, lat), name="marvin-voice-speaker",
                               daemon=True)
        spk.start()
        try:
            produce(emit)
        finally:
            sentences.put(_DONE)
            spk.join()
            if not job.cancel.is_set():
                self.sink.wait()
            self.gate.speaking(False)
            self.echo.said(" ".join(said))
        return clean_for_speech(" ".join(said))

    def _say(self, job: _Job) -> None:
        text = self._speak_all(job, lambda emit: emit(job.text), {})
        if not job.cancel.is_set():
            self._emit("reply", text=text, language=job.language, latency={}, interrupted=False,
                       proactive=True, error=None, hint="")
            if self.on_reply:
                self.on_reply(text)

    def _answer(self, job: _Job) -> None:
        messages, user = self._messages(job)
        lat = self.last_latency
        t = time.monotonic()
        failure: list[str] = []
        hint: list[str] = []

        def produce(emit):
            splitter = SentenceSplitter()
            try:
                for piece in self.llm.stream_chat(messages):
                    if job.cancel.is_set():
                        return
                    lat.setdefault("llm_first_token", time.monotonic() - t)
                    for s in splitter.feed(piece):
                        lat.setdefault("first_chunk", time.monotonic() - t)
                        emit(s)
                for s in splitter.flush():
                    lat.setdefault("first_chunk", time.monotonic() - t)
                    emit(s)
            except LLMUnavailable as e:
                log.error("%s. %s", e, e.hint)
                failure.append("llm_down")
                hint.append(f"{e}. {e.hint}".strip())
                emit(persona.phrase("llm_down", job.language))
            except Exception:
                log.exception("the language model failed")
                failure.append("error")
                emit(persona.phrase("error", job.language))

        text = self._speak_all(job, produce, lat)
        lat["total"] = time.monotonic() - job.t_heard

        def emit_reply(**kw):
            self._emit("reply", text=text, language=job.language, latency=dict(lat), proactive=False,
                       **{"interrupted": False, "error": None, "hint": "", **kw})

        if failure:
            emit_reply(error=failure[0], hint=hint[0] if hint else "")
            return
        if job.cancel.is_set():
            log.info("interrupted")
            if text:
                self._remember(user, text + " …")
            emit_reply(interrupted=True)
            return
        log.info("said: %s", text)
        self._last_reply = text
        log.info("latency: %s", format_latency(lat))
        self._remember(user, text)
        emit_reply()
        if self.on_reply:
            try:
                self.on_reply(text)
            except Exception:
                log.exception("on_reply failed")

    def _remember(self, question: str, answer: str) -> None:
        """Keeps the conversation for the next question. The model server reuses its work on
        everything up to the first message that changed, so the history only ever grows at the end:
        dropping the oldest turn every time would change the start of it at each question and make
        the model read the whole conversation again (10 s and more with a large model). When it is
        full, the older half goes at once, so that happens once every few questions."""
        self.history += [{"role": "user", "content": question}, {"role": "assistant", "content": answer}]
        turns = len(self.history) // 2
        if turns > self.config.memory_turns:
            keep = max(1, self.config.memory_turns // 2)
            del self.history[:-2 * keep]
        self._last_turn = time.monotonic()


def envelope(pcm: np.ndarray, rate: int, hz: int = 20) -> list[float]:
    """Loudness of `pcm` (0..1) `hz` times per second: the app moves Marvin's mouth with it."""
    step = max(1, rate // hz)
    n = len(pcm) // step
    if n == 0:
        return []
    x = pcm[: n * step].astype(np.float32).reshape(n, step)
    rms = np.sqrt(np.mean(x * x, axis=1))
    db = 20 * np.log10(np.maximum(rms, 1.0) / 32768)
    return [round(float(v), 2) for v in np.clip((db + 50) / 40, 0.0, 1.0)]


def format_latency(lat: dict) -> str:
    """One line: where the time went between the end of the question and the first word."""
    first_word = lat.get("endpoint", 0.0) + lat.get("audio_start", 0.0)
    parts = [f"end of speech {lat.get('endpoint', 0.0):.2f}"]
    if lat.get("queue", 0.0) >= 0.05:
        parts.append(f"backlog {lat['queue']:.2f}")
    stt = f"speech recognition {lat.get('stt', 0.0):.2f}"
    parts.append(stt + (" (speculative)" if lat.get("speculative") else ""))
    if "llm_first_token" in lat:
        parts.append(f"model first token {lat['llm_first_token']:.2f}")
    if "first_chunk" in lat:
        parts.append(f"first chunk {lat['first_chunk']:.2f}")
    if "tts" in lat:
        parts.append(f"synthesis {lat['tts']:.2f}")
    return f"first word {first_word:.2f} s after you stopped talking ({', '.join(parts)} s)"
