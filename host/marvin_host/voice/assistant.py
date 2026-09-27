"""The voice assistant: hears "Marvin, ...", thinks with a local model, answers aloud.

    source -> VAD segmenter -> wake word (Whisper) -> question -> LLM (streamed)
           -> sentence splitter -> TTS -> sink

States: idle -> listening -> thinking -> speaking -> (listening for a follow-up) -> idle.

- "Marvin, what time is it?" in one breath: the name is stripped, the rest is the question.
- "Marvin." alone: a soft chime, then the next utterance within `listen_window_s` is the question.
- After an answer, a follow-up question needs no name for `follow_up_s`.
- Barge-in: saying "Marvin" while it thinks or speaks stops it (and asks the new question, if any).
  Without echo cancellation the microphone also hears Marvin itself: utterances that repeat what
  it is saying are ignored, and anything that does not start with the name is ignored while it
  speaks. Headphones, or the robot's own speaker away from the microphone, work best.

Threads: `run()` reads the source and does all the listening (VAD, Whisper) in the caller's thread
(or a background one with `start()`); a second thread does the thinking and speaking, so listening
continues during an answer. Listening decisions use audio time (seconds of audio read), so a
recording replays the same way at any speed.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import copy
import logging
import queue
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable

import numpy as np

from ..audio import SAMPLE_RATE, AudioSink, AudioSource
from . import persona
from .llm import LLM, LLMUnavailable
from .stt import STT, Transcript
from .text import SentenceSplitter, clean_for_speech
from .tts import TTS
from .vad import Segment, Segmenter, SegmenterConfig, Vad, make_vad
from .wake import TranscriptWakeWord, WakeMatch, WakeWordDetector, is_wake_word, match_wake_word, normalize

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
    stt_model: str = "small"
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
    memory_turns: int = 6                   # question/answer pairs kept
    memory_reset_s: float = 180.0           # forget the conversation after this much silence
    segmenter: SegmenterConfig = field(default_factory=SegmenterConfig)


@dataclass
class _Job:
    kind: str                               # "ask" or "say"
    text: str
    language: str
    t_heard: float = 0.0                    # monotonic time the question was understood
    cancel: threading.Event = field(default_factory=threading.Event)


def chime(rate: int = SAMPLE_RATE) -> np.ndarray:
    """Two soft notes, rising: "I'm listening"."""
    out = []
    for f in (587.3, 880.0):                # D5, A5
        t = np.arange(int(0.09 * rate)) / rate
        env = np.minimum(1, t / 0.01) * np.exp(-t / 0.05)
        out.append(np.sin(2 * np.pi * f * t) * env * 2500)
    return np.concatenate(out).astype(np.int16)


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
        if stt is None:
            from .stt import WhisperSTT
            stt = WhisperSTT(c.stt_model, languages=(c.language,) if c.language else c.languages)
        if llm is None:
            from .llm import OllamaLLM
            llm = OllamaLLM(c.llm_model, c.ollama_host)
            if llm.available():
                llm.warm_up()
        if tts is None:
            from .tts import make_tts
            tts = make_tts(c.tts, c.tts_voice, c.language or c.default_language)
        self.stt, self.llm, self.tts = stt, llm, tts
        self.wake = wake or TranscriptWakeWord(stt)
        self.segmenter = Segmenter(vad or make_vad(c.vad), c.segmenter)
        self.on_status, self.on_transcript, self.on_reply = on_status, on_transcript, on_reply

        self.language = c.language or c.default_language    # of the conversation
        self.history: list[dict] = []
        self.last_latency: dict[str, float] = {}             # of the last answer, seconds
        self._status = Status.IDLE
        self._lock = threading.RLock()
        self._listen_until: float | None = None              # audio time
        self._audio_now = 0.0
        self._last_turn = 0.0                                # monotonic
        self._jobs: queue.Queue[_Job | None] = queue.Queue()
        self._pending = 0                                    # jobs queued or running
        self._current: _Job | None = None
        self._speaking_text = ""
        self._closed = threading.Event()
        self._thread: threading.Thread | None = None
        self._mouth = threading.Thread(target=self._mouth_loop, name="marvin-voice-mouth", daemon=True)
        self._mouth.start()

    # ------------------------------------------------------------ public API

    @property
    def status(self) -> str:
        return self._status.value

    def run(self) -> None:
        """Listens until the source ends or `close()`; then finishes what it was saying."""
        try:
            for frame in self.source.frames():
                if self._closed.is_set():
                    break
                seg = self.segmenter.feed(frame)
                self._audio_now = self.segmenter.time
                if seg is not None:
                    self._on_segment(seg)
                self._tick()
            seg = self.segmenter.flush()
            if seg is not None and not self._closed.is_set():
                self._on_segment(seg)
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
        self.source.close()
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join(timeout=2)
        if hasattr(self.sink, "close"):
            self.sink.close()

    def wait_idle(self, timeout: float | None = None) -> bool:
        """Blocks until nothing is queued, thought or spoken. False on timeout."""
        end = None if timeout is None else time.monotonic() + timeout
        while self._pending > 0:
            if self._closed.is_set() and not self._mouth.is_alive():
                return True
            if end is not None and time.monotonic() > end:
                return False
            time.sleep(0.01)
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
        """Asks a question as if it had been heard."""
        self._submit(text, language or self.language)

    # ------------------------------------------------------------ listening

    def _set_status(self, s: Status) -> None:
        with self._lock:
            if s == self._status:
                return
            self._status = s
        log.debug("voice: %s", s.value)
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
        return self.stt.transcribe(pcm, self.config.language)

    def _reply_language(self, tr: Transcript | None) -> str:
        c = self.config
        if c.language:
            return c.language
        if tr is not None and tr.confident and tr.language in c.languages:
            self.language = tr.language
        return self.language

    def _on_segment(self, seg: Segment) -> None:
        c = self.config
        with self._lock:
            busy = self._status in (Status.THINKING, Status.SPEAKING) or self._current is not None
            window = self._listen_until is not None and seg.t_start <= self._listen_until
        t0 = time.monotonic()

        if busy:
            if not (c.wake and c.barge_in):
                return                          # half duplex: what it hears now is mostly itself
            m = self.wake.check(seg.pcm, c.language)
            if m is None or self._is_echo(m):
                return
            log.info("barge-in")
            self._interrupt()
            self._handle_match(m, seg, t0)
            return

        if not c.wake or window:
            tr = self._transcribe(seg.pcm)
            rest = match_wake_word(tr.text)
            text = tr.text.strip() if rest is None else rest
            if not text:
                if rest is not None:            # "Marvin." again: keep listening
                    self._listen(seg.t_end)
                return
            self._submit(text, self._reply_language(tr), tr, t0)
            return

        m = self.wake.check(seg.pcm, c.language)
        if m is not None:
            self._handle_match(m, seg, t0)

    def _handle_match(self, m: WakeMatch, seg: Segment, t0: float) -> None:
        tr = m.transcript
        query = m.query
        if query is None:                       # a keyword model: transcribe the utterance ourselves
            tr = self._transcribe(seg.pcm)
            query = match_wake_word(tr.text)
            query = tr.text if query is None else query
        if query:
            self._submit(query, self._reply_language(tr), tr, t0)
        else:
            self._reply_language(tr)
            self._listen(seg.t_end, with_chime=self.config.chime)

    def _listen(self, t_audio: float, with_chime: bool = False) -> None:
        with self._lock:
            self._listen_until = t_audio + self.config.listen_window_s
            self._set_status(Status.LISTENING)
        if with_chime:
            self.sink.play(chime())

    def _is_echo(self, m: WakeMatch) -> bool:
        """True if what was heard is Marvin's own voice coming back through the microphone."""
        if m.transcript is None or not self._speaking_text:
            return False
        heard = [w for w in normalize(m.transcript.text).split() if len(w) > 2 and not is_wake_word(w)]
        said = set(normalize(self._speaking_text).split())
        return bool(heard) and sum(w in said for w in heard) / len(heard) >= 0.6

    def _submit(self, text: str, language: str, tr: Transcript | None = None, t0: float | None = None) -> None:
        log.info("heard (%s): %s", language, text)
        if tr is not None:
            self.last_latency = {"stt": tr.seconds}
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
                    self._set_status(Status.SPEAKING)
                    self._speaking_text = job.text
                    self._speak(job, job.text)
                    self.sink.wait()
                    if not job.cancel.is_set() and self.on_reply:
                        self.on_reply(job.text)
            except Exception:
                log.exception("voice job failed")
            finally:
                self._speaking_text = ""
                with self._lock:
                    self._current = None
                    self._pending -= 1
                    if self._pending == 0 and self._status in (Status.THINKING, Status.SPEAKING):
                        c = self.config
                        if job.kind == "ask" and not job.cancel.is_set() and c.wake and c.follow_up_s > 0:
                            self._listen_until = self._audio_now + self.config.follow_up_s
                            self._set_status(Status.LISTENING)
                        elif self._listen_until is None:
                            self._set_status(Status.IDLE)
                        else:
                            self._set_status(Status.LISTENING)

    def _messages(self, job: _Job) -> list[dict]:
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
        system = persona.system_prompt(job.language, state, events)
        return [{"role": "system", "content": system}, *self.history, {"role": "user", "content": job.text}]

    def _speak(self, job: _Job, sentence: str) -> bool:
        text = clean_for_speech(sentence)
        if not text or job.cancel.is_set():
            return False
        pcm, rate = self.tts.synthesize(text, job.language)
        if job.cancel.is_set():
            return False
        if "first_audio" not in self.last_latency and job.kind == "ask":
            self.last_latency["first_audio"] = time.monotonic() - job.t_heard
        self._set_status(Status.SPEAKING)
        self.sink.play(pcm, rate)
        return True

    def _answer(self, job: _Job) -> None:
        messages = self._messages(job)
        splitter = SentenceSplitter()
        reply: list[str] = []
        t = time.monotonic()
        try:
            for piece in self.llm.stream_chat(messages):
                if job.cancel.is_set():
                    break
                if not reply and "llm_first_token" not in self.last_latency:
                    self.last_latency["llm_first_token"] = time.monotonic() - t
                for s in splitter.feed(piece):
                    reply.append(s)
                    self._speaking_text = " ".join(reply)
                    self._speak(job, s)
            if not job.cancel.is_set():
                for s in splitter.flush():
                    reply.append(s)
                    self._speaking_text = " ".join(reply)
                    self._speak(job, s)
        except LLMUnavailable as e:
            log.error("%s. %s", e, e.hint)
            self._speak(job, persona.phrase("llm_down", job.language))
            self.sink.wait()
            return
        except Exception:
            log.exception("the language model failed")
            self._speak(job, persona.phrase("error", job.language))
            self.sink.wait()
            return
        self.sink.wait()
        text = clean_for_speech(" ".join(reply))
        self.last_latency["total"] = time.monotonic() - job.t_heard
        if job.cancel.is_set():
            log.info("interrupted")
            if text:
                self._remember(job.text, text + " …")
            return
        lat = self.last_latency
        log.info("said: %s", text)
        log.info("latency: speech recognition %.2f s, first word %.2f s after the end of the question",
                 lat.get("stt", 0.0), lat.get("first_audio", 0.0))
        self._remember(job.text, text)
        if self.on_reply:
            try:
                self.on_reply(text)
            except Exception:
                log.exception("on_reply failed")

    def _remember(self, question: str, answer: str) -> None:
        self.history += [{"role": "user", "content": question}, {"role": "assistant", "content": answer}]
        del self.history[:-2 * self.config.memory_turns]
        self._last_turn = time.monotonic()
