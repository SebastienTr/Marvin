"""The voice's audio machine: hears "Marvin, ...", decides what is a question, and speaks answers.

    device.source -> [capture thread: echo gate] -> VAD segmenter -> wake word / Whisper -> question
           -> answer clauses (a subclass decides them) -> [speaker thread: TTS -> device.sink]

States: idle -> listening -> thinking -> speaking -> (listening for a follow-up) -> idle.

- "Marvin, what time is it?" in one breath: the name is stripped, the rest is the question.
- "Marvin." alone: the next utterance within `listen_window_s` is the question (the core plays
  the earcon: `earcon()`).
- After an answer, a follow-up question needs no name for `follow_up_s`.
- Barge-in: saying "Marvin" while it thinks stops it. While it speaks, only on a duplex device
  (half duplex does not hear anything while it speaks), and never while its own reply contains
  the name. Talk now (`listen_now`) stops it on any device: the speaker is stopped, and the
  microphone reopens as soon as it is really silent, keeping what was said meanwhile as pre-roll.
- One thought, one question: speech that resumes within `continue_grace_s` of the end of a
  question, before Marvin's first word, continues it. Marvin holds its answer while the person
  talks; if they did say something, the pending answer is cancelled and the two texts go as one
  question (its "heard" event lists the questions it `continues`). A question that interrupts an
  answer (barge-in, Talk now) within `continue_join_s` of the interrupted question joins it too.
- The end of a question: with Smart Turn (turn.py), a short silence (`turn_check_s`, 0.2 s) lets
  the model judge whether the turn is complete; if not, the voice waits up to `turn_max_s` (2 s).
  Without it, the pause is `end_silence_s`, or `end_silence_long_s` when the words so far announce
  more ("..., et", "parce que", "and", a lone "OK"). See `_endpoint`.
- Whose voice (speaker.py): with voiceprints, each question's speaker is recognised while its
  words are, and the core is told ("speaker"); `known_voices_only` ignores unknown voices.
- Calibration and enrollment (enroll.py, `enroll`): while it runs the voice answers nothing; the
  frames go to the calibration.

`VoiceEngine` is everything but the conversation: what to answer is decided by `_answer(job)`,
which a subclass implements. The voice sidecar's (marvin_host.sidecar.voice) sends the question to
Marvin's core (the Java host) and speaks the text the core streams back; the tests use scripted
answers.

Control from code (the app uses these): `ask(text)` (a typed question, answered aloud),
`listen_now()` (a listening window without the wake word, like saying "Marvin." alone),
`mute(True)` (the microphone is ignored until `mute(False)`), `stop_speaking()`. Several
listeners can follow the conversation with `add_listener(fn)`: `fn(kind, data)` receives
"status", "heard", "reply", "ignored" and "muted" (see `add_listener`).

It never answers itself (echo.py): on a half-duplex device (all of them today) the microphone is
muted from the first word until the device's echo tail after the speaker has played the last one,
and whatever is heard in any state is ignored if it repeats what Marvin said recently.

One device at a time (device.py): its source, its sink and its profile (duplex, echo tail, VAD
floor, "too quiet"). `swap_device()` moves the running voice to another one (a robot linked or
left): models, the conversation's state and the listening thread stay.

State: `_lock` guards the status, the jobs and their cancellation, and every sound that starts
playing: a cancelled answer can never play a sentence or set "speaking" after the cut (Talk now,
barge-in, Stop), and a cut waits for the speaker to be silent before the microphone reopens.

Threads: a capture thread reads the source as it comes and applies the echo gate at capture
time; `run()` does the listening (VAD, Whisper) in the caller's thread (or a background one with
`start()`); a mouth thread runs the answers and a speaker thread synthesises and plays, so the
first clause is heard while the rest of the answer is still being written (a speech synthesis that
streams starts playing a clause before it is fully synthesised). Listening decisions use
audio time (seconds of audio captured), so a recording replays the same way at any speed.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import concurrent.futures
import importlib.util
import itertools
import logging
import queue
import threading
import time
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Callable

import numpy as np

from ..audio import FRAME_MS
from . import filters
from .device import AudioDevice
from .echo import CLOSED, MASK, EchoFilter, EchoGate
from .enroll import NOISE_S, Enrollment
from .speaker import MIN_SPEECH_S, SpeakerEncoder, SpeakerId, SpeakerMatch, Voiceprint
from .stt import STT, Transcript
from .text import guess_language
from .tts import TTS, pronounce
from .turn import TurnDetector, make_turn
from .vad import EnergyVad, Segment, Segmenter, SegmenterConfig, Vad, make_vad
from .wake import TranscriptWakeWord, WakeMatch, WakeWordDetector, match_wake_word

log = logging.getLogger("marvin.voice")


class Status(str, Enum):
    IDLE = "idle"
    LISTENING = "listening"
    THINKING = "thinking"
    SPEAKING = "speaking"


@dataclass
class VoiceConfig:
    """The voice's settings. The Java host sends every one of them (contract version 2): these
    defaults are its defaults, for tests and tools (a test keeps them equal, see device.py)."""
    # languages
    language: str | None = None             # force one language (STT and replies); None = follow the speaker
    languages: tuple[str, ...] = ("fr", "en")   # languages the speaker is expected to use
    default_language: str = "fr"            # until someone speaks
    # models (used when the components are not passed in)
    stt: str = "auto"                       # auto (Parakeet where it runs, else Whisper), parakeet, mlx, faster-whisper
    stt_model: str | None = None            # None: the backend's default (Parakeet v3; turbo on MLX, small on CPU)
    tts: str = "auto"                       # auto, qwen, say, piper, espeak
    tts_voice: str | None = None
    vad: str = "auto"                       # auto (Silero when downloaded), silero, webrtc, energy
    vocabulary: tuple[str, ...] = ("Marvin",)   # names and terms speech recognition must get right (lexicon.py)
    # the end of a question (turn.py)
    turn: str = "auto"                      # auto (Smart Turn when downloaded), smart-turn, silence
    turn_check_s: float = 0.2               # Smart Turn judges the turn after this much silence...
    turn_max_s: float = 2.0                 # ...and an unfinished one ends after this much
    # whose voice (speaker.py)
    voiceprints: tuple = ()                 # ((name, (floats...), model), ...): the people Marvin knows
    speaker_threshold: float = 0.45
    known_voices_only: bool = False         # ignore questions from voices it does not know
    # behaviour
    wake: bool = True                       # False: every utterance is a question
    listen_window_s: float = 6.0            # after "Marvin." alone
    follow_up_s: float = 5.0                # after an answer, a question without the name
    barge_in: bool = True
    # latency
    speculative_stt: bool = True            # start Whisper during the pause that may end the question
    speculate_after_s: float = 0.25         # ...after this much silence
    # one thought, one question (see the module docstring)
    continue_grace_s: float = 1.5           # speech resuming this soon after a question, before the answer is heard, continues it (0: off)
    continue_join_s: float = 6.0            # a question interrupting the answer this soon after the one it cut joins it (0: off)
    segmenter: SegmenterConfig = field(default_factory=SegmenterConfig)


@dataclass
class _Job:
    kind: str                               # "ask" or "say"
    text: str
    language: str
    t_heard: float = 0.0                    # monotonic time the question's segment was complete
    cancel: threading.Event = field(default_factory=threading.Event)
    context: str = ""                       # the context block sent with the question (for the app)
    uid: int = 0                            # the question's id ("heard" events), 0 for "say"
    reply_id: int = 0                       # the answer's id when someone else writes it (the sidecar)
    source: str = "voice"                   # voice | typed
    cancel_reason: str = ""                 # why it was cancelled: "barge-in", "stop" or "merged" (continued)
    continues: tuple[int, ...] = ()         # the earlier questions (uids) this one continues
    audible: bool = False                   # its first sound was played (answer or filler)
    done: bool = False                      # finished (answered, failed or cancelled)
    gate_token: int = 0                     # the echo gate's token of its speech (0: no sound yet)
    voice_language: str = ""                # the voice it is said with (one voice per reply)
    clauses: list = field(default_factory=list)     # "say": (text, language) pieces, as the core cut them


@dataclass
class _Asked:
    """The last question heard: what a continuation joins."""
    uids: list[int]                         # its uid last, after the questions it already continues
    text: str
    raw: str
    language: str
    t_speech_end: float                     # audio time its speech ended
    job: _Job


_DONE = object()


class _Filler(str):
    """A few words said while a tool runs ("Let me check…"): spoken, but not the answer (not in the
    history, not counted as the first word of the answer)."""


class VoiceEngine:
    """See the module docstring. Components not passed in are built from `config`."""

    def __init__(self, device: AudioDevice, config: VoiceConfig | None = None, *,
                 stt: STT | None = None, tts: TTS | None = None,
                 vad: Vad | None = None, wake: WakeWordDetector | None = None,
                 turn: TurnDetector | None | str = "auto", speaker: SpeakerEncoder | None | str = "auto",
                 stt_lock: threading.Lock | None = None,
                 on_status: Callable[[str], None] | None = None,
                 on_transcript: Callable[[str], None] | None = None,
                 on_reply: Callable[[str], None] | None = None):
        self.config = c = config or VoiceConfig()
        self.device = device
        languages = (c.language,) if c.language else c.languages
        self.language = c.language or c.default_language    # of the conversation
        if stt is None:
            from .stt import make_stt, with_vocabulary
            stt = with_vocabulary(make_stt(c.stt, c.stt_model, languages), c.vocabulary)
        if tts is None:
            tts = load_tts(c)
        self.stt, self.tts = stt, tts
        self.wake = wake or TranscriptWakeWord(stt)
        self._own_vad = vad is None                          # made here: it follows the device's profile
        p = device.profile
        # the end of a question: Smart Turn after a short silence, else the silence timers
        self.turn = make_turn(c.turn) if turn == "auto" else turn
        seg_config = c.segmenter
        if self.turn is not None:
            seg_config = replace(c.segmenter, end_silence_s=c.turn_check_s,
                                 end_silence_long_s=max(c.turn_max_s, c.turn_check_s))
        self._turn_last = None                               # (utterance uid, TurnDecision) of the last judged pause
        self.segmenter = Segmenter(vad or make_vad(c.vad, p.vad_aggressiveness, p.vad_min_dbfs, p.vad_threshold),
                                   seg_config)
        self.segmenter.endpoint = self._endpoint
        # whose voice: an encoder (enrollment needs it even before anyone is known) and the voiceprints
        if speaker == "auto":
            from .speaker import make_encoder
            speaker = make_encoder()
        self.speaker_encoder = speaker
        prints = [Voiceprint(n, np.asarray(e, np.float32), m) for n, e, m in c.voiceprints]
        self.speaker = SpeakerId(speaker, prints, c.speaker_threshold) if speaker is not None else None
        self._spk: tuple[int, concurrent.futures.Future] | None = None     # (utterance uid, its SpeakerMatch)
        self._spk_done: tuple[int, SpeakerMatch] | None = None
        self._spk_pool = concurrent.futures.ThreadPoolExecutor(1, thread_name_prefix="marvin-voice-speaker")
        self._last_known: tuple[str, float] | None = None   # the last recognised speaker and when (audio time)
        self._enroll: Enrollment | None = None
        self.on_status, self.on_transcript, self.on_reply = on_status, on_transcript, on_reply
        self._listeners: list[Callable[[str, dict], None]] = []
        self._muted = False

        self.echo = EchoFilter()
        self._cap_n = 0                                      # frames captured
        self._level_peak = 0.0                               # live signals for the app (_live)
        self._level_n = 0
        self._utt: int | None = None                         # utterance being heard (its uid)
        self._seg_started: float | None = None               # start of the utterance being judged
        self._seg_dbfs: float | None = None                  # its loudness (for "ignored" events)
        self.gate = EchoGate(p.echo_tail_s, enabled=not p.duplex, output_latency_s=device.output_latency_s,
                             clock=self._capture_time)
        self._last_reply = ""                                # for "oui" / "non" follow-ups
        self.last_latency: dict[str, float] = {}             # of the last answer, seconds
        self._status = Status.IDLE
        self._lock = threading.RLock()
        self._stt_lock = stt_lock or threading.Lock()
        self._tts_lock = threading.Lock()                    # one synthesis at a time (a cut one may still run)
        self._swap_gen = 0                                   # moves on when the device is swapped
        self._first_play: float | None = None                # when the answer's first sound was queued
        self._reset_segmenter = False
        self._listen_until: float | None = None              # audio time
        self._asked_to_listen = False    # the window was asked for (Talk now, "Marvin." alone): take what comes
        self._listen_from = 0.0                              # audio time: earlier utterances are not follow-ups
        self._audio_now = 0.0
        self._question_ids = itertools.count(1)              # "heard" uids
        self._frames: queue.Queue = queue.Queue()            # (frame, gated, capture time) from the capture thread
        self._jobs: queue.Queue[_Job | None] = queue.Queue()
        self._pending = 0                                    # jobs queued or running
        self._current: _Job | None = None
        self._spec: tuple[int, int, concurrent.futures.Future] | None = None   # (uid, voiced, transcript)
        self._spec_pool = concurrent.futures.ThreadPoolExecutor(1, thread_name_prefix="marvin-voice-stt")
        self._asked: _Asked | None = None                    # the last question heard (continuations)
        self._lead_in: tuple[str, float] | None = None       # a lone "OK" heard in a window: (text, speech end)
        self._audio_ok = threading.Event()                   # cleared: an answer's first sound waits (someone goes on)
        self._audio_ok.set()
        self._hold_lock = threading.Lock()
        self._frozen_left: float | None = None               # listening window left when someone started talking
        self._heard_aloud: set[int] = set()                  # questions whose answer made a sound (recent ones)
        self._closed = threading.Event()
        self._thread: threading.Thread | None = None
        self._capture: threading.Thread | None = None
        self._mouth = threading.Thread(target=self._mouth_loop, name="marvin-voice-mouth", daemon=True)
        self._mouth.start()

    # ------------------------------------------------------------ public API

    @property
    def status(self) -> str:
        return self._status.value

    @property
    def source(self):
        return self.device.source

    @property
    def sink(self):
        return self.device.sink

    @property
    def listen_asked(self) -> bool:
        """Listening because it was asked to (Talk now, "Marvin." alone), not the follow-up window."""
        with self._lock:
            return self._status == Status.LISTENING and self._asked_to_listen

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

    def start(self) -> "VoiceEngine":
        """`run()` in a background thread."""
        self._thread = threading.Thread(target=self.run, name="marvin-voice-ears", daemon=True)
        self._thread.start()
        return self

    def close(self) -> None:
        """Stops listening and speaking, and closes the source and the sink."""
        if self._closed.is_set():
            return
        self._closed.set()
        self._audio_ok.set()
        self._interrupt()
        self._jobs.put(None)
        self._frames.put(None)
        self.source.close()
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join(timeout=2)
        self._spec_pool.shutdown(wait=False)
        self._spk_pool.shutdown(wait=False)
        if hasattr(self.sink, "close"):
            self.sink.close()

    def swap_device(self, device: AudioDevice) -> None:
        """Hears and speaks through `device` from now on (a robot linked or left). What is being said
        on the old device stops; models, windows and the conversation's state stay."""
        with self._lock:
            if self._closed.is_set():
                device.close()
                return
            old = self.device
            if self.gate.speaking or self._pending > 0 or old.sink.busy:
                self._interrupt("stop")
            enr, self._enroll = self._enroll, None
            if enr is not None:
                enr.cancel()                # it measured the old device
            self.device = device
            p = device.profile
            self.gate.configure(p.echo_tail_s, not p.duplex, device.output_latency_s)
            self.gate.cut()
            if self._own_vad:
                self.segmenter.vad = make_vad(self.config.vad, p.vad_aggressiveness, p.vad_min_dbfs, p.vad_threshold)
            self._swap_gen += 1
            self._reset_segmenter = True
        log.info("voice: now on %s", device.name)
        old.close()                     # its source ends: the capture thread moves on to the new one

    def earcon(self, sound: str) -> None:
        """Plays a short sound on the device (wake, done, error), which the microphone ignores. The
        core decides when (docs/voice.md, "Earcons")."""
        with self._lock:
            if self._closed.is_set():
                return
            seconds = self.device.earcon(sound)
        if seconds:
            self.gate.mask(seconds + 0.05)

    @property
    def closed(self) -> bool:
        return self._closed.is_set()

    # ------------------------------------------------------------ calibration and enrollment

    @property
    def enrolling(self) -> bool:
        return self._enroll is not None

    def enroll(self, name: str, sentences: list[str] | None = None, noise_s: float = NOISE_S) -> bool:
        """Starts a calibration (and, with a speaker model, an enrollment of `name`'s voice) on the
        current device (enroll.py): what Marvin says stops, the microphone is unmuted, nothing is
        answered until it ends. Progress comes as "enroll" events. False once closed."""
        if self._closed.is_set():
            return False
        p = self.device.profile
        vad = make_vad(self.config.vad, p.vad_aggressiveness, -90.0, 0.5) if self._own_vad \
            else EnergyVad(margin_db=10.0, min_dbfs=-90.0)
        with self._lock:
            if self._pending > 0 or self._status in (Status.THINKING, Status.SPEAKING):
                self._interrupt("stop")
            self._listen_until = None
            self._set_status(Status.IDLE)
            old, self._enroll = self._enroll, None
            if old is not None:
                old.cancel()
            enc = self.speaker_encoder
            self._enroll = Enrollment(name, list(sentences or []), vad=vad, transcribe=self._transcribe,
                                      embed=enc.embed if enc is not None else None, emit=self._enroll_event,
                                      noise_s=noise_s)
        if self._muted:
            self.mute(False)
        log.info("calibration started on %s%s", self.device.name, f" for {name}" if name else "")
        return True

    def enroll_cancel(self) -> bool:
        with self._lock:
            enr, self._enroll = self._enroll, None
        if enr is None:
            return False
        enr.cancel()
        return True

    def _enroll_event(self, state: str, **data) -> None:
        if state == "done":
            cal = data["calibration"]
            log.info("calibration on %s: noise %.1f dBFS, voice %.1f dBFS -> VAD floor %.1f, too quiet %.1f, "
                     "Silero %.2f%s", self.device.name, cal.noise_dbfs, cal.speech_dbfs, cal.vad_min_dbfs,
                     cal.speech_min_dbfs, cal.vad_threshold, "".join(f"; {w}" for w in cal.warnings))
            data["device"] = self.device.profile
            data["model"] = getattr(self.speaker_encoder, "model_id", "")
        self._emit("enroll", state=state, **data)

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

    def busy(self) -> bool:
        """A conversation is going on: proactive speech waits (see `say`)."""
        return self._status != Status.IDLE or self._pending > 0 or self.segmenter.in_speech

    def say(self, text: str, language: str | None = None, force: bool = False, *, reply_id: int = 0,
            clauses: list | None = None) -> bool:
        """Speaks `text` (proactive speech), or its `clauses` ((text, language) pieces). Skipped,
        returning False, while a conversation is going on, unless `force`."""
        with self._lock:
            if not force and self.busy():
                return False
            self._enqueue(_Job("say", text, language or self.language, reply_id=reply_id, clauses=list(clauses or [])))
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
        with self._lock:
            if self._pending > 0 or self._status in (Status.THINKING, Status.SPEAKING):
                self._interrupt()
        self._submit(text, lang, source="typed")

    TALK_NOW_LEAD_S = 0.5       # Talk now: what was said this long before the press counts

    def listen_now(self) -> bool:
        """Opens a listening window without the wake word, as if "Marvin." had been said alone: the
        next utterance within `listen_window_s` is the question. Stops what Marvin is saying or
        thinking (a question that follows may continue the one it cuts, see `_joinable`) and
        unmutes the microphone. The microphone is not deaf meanwhile: it is masked (kept for the
        pre-roll) until the speaker is really silent, then open. False once closed."""
        if self._closed.is_set():
            return False
        with self._lock:
            start = self._capture_time() - self.TALK_NOW_LEAD_S
            if self._pending > 0 or self._status in (Status.THINKING, Status.SPEAKING):
                if self.gate.speaking:
                    self.gate.soften()
                self._interrupt("barge-in", listen_from=start)
            if self._muted:
                self.mute(False)
            # from what the listening has reached (it may lag behind the capture while Whisper works),
            # so the window is never already over when it opens
            self._listen(max(self._audio_now, self._capture_time() - 0.3))
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
        """Seconds left in the listening window (None outside one, and while someone talks: the
        window waits for them, see `hearing`)."""
        with self._lock:
            if self._status != Status.LISTENING or self._listen_until is None or self.hearing:
                return None
            return max(0.0, self._listen_until - self._audio_now)

    @property
    def hearing(self) -> bool:
        """Someone is talking, or what they said is being understood: a listening window does not
        run out meanwhile (what they say counts if it started in the window)."""
        return self.segmenter.in_speech or self._utt is not None

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
        - "heard": {"text", "language", "source": "voice" | "typed", "uid", "raw"?, "continues"?,
          "cut"?}: a question Marvin answers ("raw": the full transcript, when it differs from the
          question, e.g. the name; "uid": the question's id, unique while the engine runs;
          "continues": the earlier questions it goes on from, their text first; "cut": those of them
          whose answer had started to be heard)
        - "reply": {"text", "language", "latency": {stage: seconds}, "interrupted": bool,
          "proactive": bool, "error": None | "llm_down" | "error", "hint": str}; answers also
          carry what the model was given: "context" (the context block), "prompt" (the whole
          user message) and "model"
        - "ignored": {"text", "reason", "dbfs"?}: an utterance Marvin chose not to answer, and how
          loud it was
        - "muted": {"muted": bool}
        - "level": {"mic": 0..1, "speech": bool, "gated": bool}: the microphone's loudness, ~16 Hz
        - "utterance": {"state": "start" | "end" | "done", "uid"}: someone talks, stops, is judged
        - "partial": {"uid", "text"}: what is understood so far of the utterance being heard
        - "say": {"text", "seconds", "envelope": [0..1 at 20 Hz]}: a piece of the reply, as it is
          queued on the speaker (it plays after the pieces before it); with "reply_id" when the
          answer has one

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
        """Reads the device's source at its own pace, so the echo gate is applied when the sound is
        captured, however far behind the listening (Whisper) is. A swapped device's source ends: the
        loop goes on with the new one; a source that ends by itself ends the listening."""
        try:
            while not self._closed.is_set():
                gen = self._swap_gen
                source = self.device.source
                try:
                    for frame in source.frames():
                        if self._closed.is_set() or gen != self._swap_gen:
                            break
                        state = CLOSED if self._muted else self.gate.state()   # judged at the frame's start time
                        self._cap_n += 1
                        self._frames.put((frame, state, time.monotonic()))
                except Exception:
                    if gen == self._swap_gen:
                        log.exception("audio source failed")
                        break
                if gen == self._swap_gen:
                    break
        finally:
            self._frames.put(None)

    # ------------------------------------------------------------ listening

    def _hear(self, frame: np.ndarray, state: str, t_cap: float) -> None:
        if self._reset_segmenter:             # another device: what the old one was hearing is gone
            self._reset_segmenter = False
            self.segmenter.reset()
            self._spec = None
            self._utterance_done()
        gated = state == CLOSED
        enr = self._enroll
        if enr is not None:                   # calibrating: the frames are the calibration's, nothing is judged
            enr.feed(frame, heard=not gated)
            if not enr.active:
                with self._lock:
                    if self._enroll is enr:
                        self._enroll = None
            self.segmenter.advance()
            self._spec = None
            self._utterance_done()
            self._live_level(frame, gated)
            self._audio_now = self.segmenter.time
            return
        if gated:
            self.segmenter.advance()          # deaf: time goes on, a started utterance is dropped
            self._spec = None
            self._utterance_done()
        else:
            seg = self.segmenter.feed(frame, masked=state == MASK)
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
                    self._maybe_hold()
                elif not self.segmenter.in_speech:
                    self._utterance_done()      # too short: dropped by the segmenter
                self._speculate()
        if not self._audio_ok.is_set() and not self.segmenter.in_speech:
            self._audio_ok.set()                # judged (or dropped): the answer, if still wanted, may play
        self._live_level(frame, gated)
        self._audio_now = self.segmenter.time
        self._tick()

    # ------------------------------------------------------------ one thought, one question

    def _continuable(self, t_speech: float | None) -> _Asked | None:
        """The question that speech starting at `t_speech` (audio time) continues, if any: asked
        less than `continue_grace_s` before, and its answer not heard yet."""
        a, grace = self._asked, self.config.continue_grace_s
        if a is None or grace <= 0 or t_speech is None:
            return None
        j = a.job
        if j.cancel.is_set() or j.done or j.audible or t_speech - a.t_speech_end > grace:
            return None
        return a

    def _joinable(self, seg: Segment) -> _Asked | None:
        """The question that `seg` joins after cutting its answer (barge-in, Talk now), if any: its
        speech started less than `continue_join_s` after that question ended."""
        a, span = self._asked, self.config.continue_join_s
        if a is None or span <= 0 or seg.speech_start - a.t_speech_end > span:
            return None
        j = a.job
        if j.cancel.is_set():
            return a if j.cancel_reason == "barge-in" else None
        return None if j.done else a

    def _maybe_hold(self) -> None:
        """Someone starts talking right after a question: its answer does not start playing until
        they stop (and is cancelled if they said something, see `_continue`)."""
        a = self._continuable(self.segmenter.speech_start)
        if a is None:
            return
        with self._hold_lock:
            if a.job.audible or a.job.cancel.is_set():
                return
            self._audio_ok.clear()
        log.debug("speech resumes %.2f s after the question: its answer waits",
                  (self.segmenter.speech_start or 0.0) - a.t_speech_end)

    ENDPOINT_WAIT_S = 1.5       # longest wait for the speculative transcript when deciding the endpoint

    def _endpoint(self) -> float | None:
        """The segmenter asks, once per pause of `end_silence_s`, whether to wait longer.

        With Smart Turn (`end_silence_s` is then `turn_check_s`): the model judges the utterance so
        far; an unfinished turn waits `turn_max_s` (`end_silence_long_s`) unless the person goes on.
        Without it: wait `end_silence_long_s` when the speculative transcript of the words so far
        announces more (filters.announces_more). The transcript was started at `speculate_after_s`
        and the answer would wait for it anyway, so asking costs nothing when the question is over."""
        c, sg = self.config, self.segmenter
        if self.turn is not None:
            return self._turn_endpoint()
        long_s = sg.config.end_silence_long_s
        if long_s <= sg.config.end_silence_s:
            return None
        spec = self._spec
        if spec is None or spec[:2] != sg.utterance_key:
            # no speculation (turned off, or skipped): transcribe now, the end of the question would
            # wait for it anyway, and the transcript is used if the question does end here
            pend = sg.pending()
            if pend is None or not self._may_matter() or not self._worth_transcribing(pend):
                return None
            fut = self._spec_pool.submit(self._transcribe, pend.pcm)
            spec = self._spec = (pend.uid, pend.voiced, fut)
        try:
            tr = spec[2].result(timeout=self.ENDPOINT_WAIT_S)
        except Exception:                               # noqa: BLE001 - a timeout or a failed transcription
            return None
        text = (tr.text or "").strip()
        if tr.rejected or not filters.announces_more(text):
            return None
        log.info("more to come after %r: waiting for %.2f s of silence", text, long_s)
        return long_s

    TURN_BUDGET_S = 0.05        # what the end-of-turn decision may add to the end of a question

    def _turn_endpoint(self) -> float | None:
        sg = self.segmenter
        pend = sg.pending()
        if pend is None or not self._may_matter():
            return None                         # Marvin is busy: only a barge-in could count, judged at once
        try:
            d = self.turn.decide(pend.pcm)
        except Exception:                       # noqa: BLE001 - never lose the utterance
            log.exception("end of turn decision failed")
            return None
        self._turn_last = (pend.uid, d)
        if d.seconds > self.TURN_BUDGET_S:
            log.info("end of turn decided in %.0f ms (budget %.0f ms)", d.seconds * 1000, self.TURN_BUDGET_S * 1000)
        if d.complete:
            log.debug("end of turn: complete (p=%.2f, %.0f ms)", d.probability, d.seconds * 1000)
            return None
        log.info("end of turn: not finished (p=%.2f, %.0f ms), waiting up to %.1f s of silence",
                 d.probability, d.seconds * 1000, sg.config.end_silence_long_s)
        return sg.config.end_silence_long_s

    def _continue(self, prev: _Asked, seg: Segment, t0: float, lat: dict) -> None:
        """`seg` continues the question `prev`, whose answer has not been heard: the answer is
        cancelled and both go as one question, unless `seg` was nothing (then the answer plays)."""
        tr = self._full_transcript(seg, lat)
        text = (tr.text or "").strip()
        if self._own_voice(text, self._seg_started):
            return
        bad = tr.rejected or filters.decoder_reason(tr.no_speech_prob, tr.avg_logprob, tr.compression_ratio)
        if bad or not text:
            self._ignored(text, bad or "nothing understood")
            return
        rest = match_wake_word(text)
        if rest is not None and not rest.strip():   # "Marvin." again: stop and listen, as a barge-in
            log.info("barge-in")
            self._interrupt("barge-in")
            self._reply_language(tr)
            self._listen(seg.t_end)
            return
        text = text if rest is None else rest.strip()
        why = filters.hallucination_reason(text)
        if why:
            self._ignored(text, why)
            return
        self._join(prev, text, tr, t0, lat, seg, "merged")

    def _join(self, prev: _Asked, text: str, tr: Transcript | None, t0: float, lat: dict, seg: Segment,
              reason: str) -> None:
        """Asks `prev`'s text and `text` as one question; `prev`'s answer is stopped (`reason`)."""
        joined = f"{prev.text.rstrip()} {text.strip()}"
        log.info("joined %d utterances (%s): %s", len(prev.uids) + 1,
                 "before the answer" if reason == "merged" else "after a barge-in", joined)
        if not prev.job.cancel.is_set():
            self._interrupt(reason)
        raw = f"{prev.raw} {(tr.text or '').strip() if tr is not None else text}".strip()
        self._submit(joined, prev.language, tr, t0, lat, seg=seg, continues=tuple(prev.uids), raw=raw)

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
        "done": decided (a "heard" or "ignored" event may have come just before). A listening window
        is frozen from "start" to "done": when nothing was asked, it goes on with at least the time
        it had left (and `continue_grace_s`, for the rest of a sentence that began with "OK")."""
        with self._lock:
            listening = self._status == Status.LISTENING and self._listen_until is not None
            if state == "start" and listening and self._frozen_left is None:
                self._frozen_left = max(0.0, self._listen_until - self._audio_now)
            elif state == "done":
                if listening and self._frozen_left is not None:
                    self._listen_until = max(self._listen_until,
                                             self._audio_now + max(self._frozen_left, self.config.continue_grace_s))
                self._frozen_left = None
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
        if not self._may_matter():
            return
        if self._spec is not None and self._spec[:2] == sg.utterance_key:
            return
        pend = sg.pending()
        if pend is None or not self._worth_transcribing(pend):
            return
        fut = self._spec_pool.submit(self._transcribe, pend.pcm)
        self._spec = (pend.uid, pend.voiced, fut)
        fut.add_done_callback(lambda f, uid=pend.uid: self._partial(uid, f))

    def _may_matter(self) -> bool:
        """Whether the utterance going on may be worth understanding early: not while Marvin is busy
        with an answer, unless it may be a barge-in (a duplex device) or continue the question."""
        c = self.config
        if not self._busy() or not self._audio_ok.is_set() or self._listen_until is not None:
            return True
        return self.device.profile.duplex and c.wake and c.barge_in and not self.echo.current_has_wake_word()

    def _worth_transcribing(self, seg: Segment) -> bool:
        """Speech, and (when only the name counts) maybe the name."""
        if filters.speech_evidence(seg, self.device.profile.speech_min_dbfs) is not None:
            return False
        window = self._listen_until is not None
        return not (self.config.wake and not window and isinstance(self.wake, TranscriptWakeWord)
                    and not self.wake.candidate(seg.pcm))

    def _busy(self) -> bool:
        """Thinking or speaking an answer that is still wanted (a cut one finishing does not count)."""
        with self._lock:
            if self._status in (Status.THINKING, Status.SPEAKING):
                return True
            cur = self._current
            return (cur is not None and not cur.cancel.is_set()) or not self._jobs.empty()

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

    def _level(self) -> dict:
        """The loudness of the utterance being judged, for the app (dBFS, loudest half)."""
        return {} if self._seg_dbfs is None else {"dbfs": round(self._seg_dbfs, 1)}

    def _own_voice(self, text: str, started: float | None = None) -> bool:
        if self.echo.is_own_voice(text, started):
            log.info("heard: %s (ignored: own voice)", text)
            self._emit("ignored", text=text, reason="own voice", **self._level())
            return True
        return False

    def _ignored(self, text: str, reason: str, quiet: bool = False) -> None:
        (log.debug if quiet else log.info)("heard: %s (ignored: %s)", text or "…", reason)
        if not quiet:
            self._emit("ignored", text=text, reason=reason, **self._level())

    def _close_conversation(self, text: str, reason: str) -> None:
        log.info("heard: %s (conversation closed: %s)", text, reason)
        self._emit("ignored", text=text, reason=f"conversation closed: {reason}", **self._level())
        with self._lock:
            self._listen_until = None
            if self._status == Status.LISTENING:
                self._set_status(Status.IDLE)

    def _on_segment(self, seg: Segment, t_cap: float) -> None:
        self._seg_dbfs = filters.loud_dbfs(seg.pcm) if len(seg.pcm) else None
        spk = self.speaker
        if spk is not None and spk.enrolled and seg.duration >= MIN_SPEECH_S:
            # whose voice, while the words are being recognised
            self._spk = (seg.uid, self._spk_pool.submit(spk.identify, seg.pcm))
        try:
            self._judge(seg, t_cap)
        finally:
            self._seg_dbfs = None

    def _judge(self, seg: Segment, t_cap: float) -> None:
        c = self.config
        now_audio = self.segmenter.time
        lat = {"endpoint": max(0.0, now_audio - seg.t_speech_end) if seg.t_speech_end
               else self.segmenter.config.end_silence_s,
               "queue": max(0.0, time.monotonic() - t_cap)}
        tl = self._turn_last
        if tl is not None and tl[0] == seg.uid:
            lat["turn"] = round(tl[1].seconds, 4)       # Smart Turn's decision, part of the endpoint
            lat["turn_p"] = round(tl[1].probability, 3)
        t0 = t_cap                              # when the end of the question was captured
        self._seg_started = t_cap - max(0.0, now_audio - seg.t_start)   # when it began (monotonic)
        with self._lock:
            busy = self._busy()
            late = seg.t_start < self._listen_from - 0.05   # captured while it was speaking
            window = (self._listen_until is not None and not late and seg.t_start <= self._listen_until)

        no_name = not c.wake or window          # no wake word will confirm it is for Marvin
        weak = filters.speech_evidence(seg, self.device.profile.speech_min_dbfs)
        if weak:
            self._spec = None
            self._ignored("", weak, quiet=not (no_name and not busy and not late))
            return

        prev = None if late else self._continuable(seg.speech_start)
        if prev is not None:                    # the question goes on, before its answer is heard
            self._continue(prev, seg, t0, lat)
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
            if self._refused(seg, m.transcript.text if m.transcript is not None else ""):
                return                          # a voice it does not know cannot cut it short
            join = self._joinable(seg)          # "Marvin, ... and also ...": the rest of the question it cuts
            if busy:
                log.info("barge-in")
                self._interrupt("barge-in")
            self._handle_match(m, seg, t0, lat, join=join)
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
            join = self._joinable(seg) if window and self._asked_to_listen else None
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
                    if filters.announces_more(text):    # "OK": the rest of the sentence may follow
                        self._lead_in = (text, seg.t_speech_end)
                    self._ignored(text, why)
                    return
            bad = filters.hallucination_reason(text)
            if bad:
                self._ignored(tr.text, bad)
                return
            if join is not None:                # Talk now (or "Marvin." alone) cut the answer: go on
                self._join(join, text, tr, t0, lat, seg, "barge-in")
                return
            self._submit(text, self._reply_language(tr), tr, t0, lat, seg=seg)
            return

        m = self._wake_check(seg, lat)
        if m is not None and not (m.transcript is not None and self._own_voice(m.transcript.text, self._seg_started)):
            self._handle_match(m, seg, t0, lat, join=self._joinable(seg) if late else None)

    def _handle_match(self, m: WakeMatch, seg: Segment, t0: float, lat: dict, join: _Asked | None = None) -> None:
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
        if query and join is not None:
            self._join(join, query, tr, t0, lat, seg, "barge-in")
        elif query:
            self._submit(query, self._reply_language(tr), tr, t0, lat, seg=seg)
        else:
            self._reply_language(tr)
            self._listen(seg.t_end)

    def _listen(self, t_audio: float) -> None:
        with self._lock:
            self._asked_to_listen = True
            self._listen_until = t_audio + self.config.listen_window_s
            self._set_status(Status.LISTENING)

    def _submit(self, text: str, language: str, tr: Transcript | None = None, t0: float | None = None,
                lat: dict | None = None, source: str = "voice", *, seg: Segment | None = None,
                continues: tuple[int, ...] = (), raw: str | None = None) -> None:
        raw = raw if raw is not None else ((tr.text or "").strip() if tr is not None else "")
        who = SpeakerMatch(None, None)
        if source == "voice" and seg is not None:
            who = self._speaker_of(seg)
            if self._refused(seg, text, who):
                return
        lead, self._lead_in = self._lead_in, None
        grace = self.config.continue_grace_s
        if lead is not None and seg is not None and grace > 0 and 0 <= seg.speech_start - lead[1] <= grace:
            text, raw = f"{lead[0].rstrip()} {text}", f"{lead[0].rstrip()} {raw or text}"   # "OK, j'ai faim."
        log.info("heard (%s): %s", language, text)
        self.last_latency = dict(lat or {})
        extra = {"raw": raw} if raw and raw != text else {}
        if who.score is not None:
            extra["speaker"] = who.label()
            extra["speaker_score"] = who.score
            lat = dict(lat or {})
            lat["speaker"] = round(who.seconds, 4)
            self.last_latency = dict(lat)
        if continues:
            extra["continues"] = list(continues)
            extra["cut"] = [u for u in continues if u in self._heard_aloud]     # answered (in part) in between
        uid = next(self._question_ids)
        job = _Job("ask", text, language, t0 or time.monotonic(), uid=uid, source=source, continues=tuple(continues))
        self._asked = (_Asked([*continues, uid], text, raw or text, language, seg.t_speech_end, job)
                       if seg is not None and source == "voice" else None)
        with self._lock:
            # the job exists before anyone hears of it: an answer may come back at once (the sidecar)
            self._prepare(job)
        self._emit("heard", text=text, language=language, source=source, uid=uid, **extra)
        if self.on_transcript:
            try:
                self.on_transcript(text)
            except Exception:
                log.exception("on_transcript failed")
        with self._lock:
            self._listen_until = None
            self._set_status(Status.THINKING)
            self._enqueue(job)

    SPEAKER_WAIT_S = 1.0        # longest wait for the speaker's recognition (it runs beside speech recognition)
    KNOWN_FOR_S = 60.0          # known_voices_only: a short reply (too short to judge) counts as the last known voice's

    def _speaker_of(self, seg: Segment) -> SpeakerMatch:
        """Whose voice `seg` is (computed since the utterance ended), or not judged."""
        done = self._spk_done
        if done is not None and done[0] == seg.uid:
            return done[1]
        spk = self._spk
        if spk is None or spk[0] != seg.uid:
            return SpeakerMatch(None, None)
        try:
            m = spk[1].result(timeout=self.SPEAKER_WAIT_S)
        except Exception:                           # noqa: BLE001 - a timeout or a failure: not judged
            log.warning("speaker recognition did not answer in time")
            m = SpeakerMatch(None, None)
        if m.score is not None:
            log.info("speaker: %s (%.2f%s, %.0f ms)", m.name or "unknown", m.score,
                     "" if m.name else f", closest {m.best}", m.seconds * 1000)
            if m.known:
                self._last_known = (m.name, seg.t_end)
        self._spk_done = (seg.uid, m)
        return m

    def _refused(self, seg: Segment, text: str, who: SpeakerMatch | None = None) -> bool:
        """`known_voices_only`: an unknown voice is ignored (logged and shown, never answered). A
        reply too short to judge counts as the voice recognised last, within `KNOWN_FOR_S`."""
        if not self.config.known_voices_only or self.speaker is None or not self.speaker.enrolled:
            return False
        who = who or self._speaker_of(seg)
        if who.known:
            return False
        if who.score is None:
            last = self._last_known
            if last is not None and seg.t_end - last[1] <= self.KNOWN_FOR_S:
                return False
            self._ignored(text, "voice not recognised (too short to tell)")
            return True
        self._ignored(text, f"unknown voice ({who.score:.2f}, closest {who.best})")
        return True

    def _prepare(self, job: _Job) -> None:
        """Called for a question before it is announced ("heard") and queued. Nothing by default."""

    def _enqueue(self, job: _Job) -> None:
        with self._lock:
            self._pending += 1
            self._jobs.put(job)

    def _interrupt(self, reason: str = "stop", listen_from: float | None = None) -> None:
        """Stops what is being said and drops what was queued. Utterances that started before
        `listen_from` (audio time; default: now) were heard while it spoke. The microphone, deaf
        while it spoke (half duplex), reopens once the speaker is really silent."""
        with self._lock:
            speaking = self.gate.speaking or bool(getattr(self.sink, "busy", False))
            job = self._current
            if job is not None:
                job.cancel_reason = job.cancel_reason or reason
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
                j.cancel_reason = j.cancel_reason or reason
                j.cancel.set()
                self._dropped(j)
            self._listen_from = self._capture_time() if listen_from is None else listen_from
            device = self.device
            device.sink.stop()
        if not speaking:
            self.gate.cut()
            return

        def reopen():
            device.stop_playback()
            self.gate.cut(mask_s=0.1)       # what the room still echoes is not speech
        threading.Thread(target=reopen, name="marvin-voice-cut", daemon=True).start()

    def _dropped(self, job: _Job) -> None:
        """A queued job was dropped before it started (interrupted). Nothing by default."""

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
                job.done = True
                with self._lock:
                    self._current = None
                    self._pending -= 1
                    if self._pending == 0 and self._status in (Status.THINKING, Status.SPEAKING):
                        c = self.config
                        if not job.cancel.is_set():
                            # anything captured before now (plus the echo tail) is not a follow-up; a cut
                            # job's interruption already said from when to listen
                            self._listen_from = self._capture_time() + (0.0 if not self.gate.enabled
                                                                        else self.gate.tail_s)
                        if job.kind == "ask" and not job.cancel.is_set() and c.wake and c.follow_up_s > 0:
                            self._listen_until = self._listen_from + c.follow_up_s
                            self._asked_to_listen = False
                            self._set_status(Status.LISTENING)
                        elif self._listen_until is None:
                            self._set_status(Status.IDLE)
                        else:
                            self._set_status(Status.LISTENING)

    def _answer(self, job: _Job) -> None:
        """Answers the question `job.text` aloud, with `_speak_all`. Subclasses decide how."""
        raise NotImplementedError

    def _speaker(self, job: _Job, sentences: queue.Queue, lat: dict) -> None:
        """Speaker thread of one reply: synthesises each clause and queues it on the sink, in order,
        while the answer is still being written. One voice per reply: a backend with a voice per
        language (say, Piper) keeps the voice of the reply's first clause; a multilingual one
        (Qwen3-TTS: one voice, every language) reads each clause in its own language."""
        first = True
        while True:
            item = sentences.get()
            if item is _DONE or job.cancel.is_set():
                return
            text, language = item
            filler = isinstance(text, _Filler)
            text = str(text).strip()
            if not text:
                continue
            language = language or job.language
            if not job.voice_language:
                job.voice_language = language
            voice_language = language if getattr(self.tts, "multilingual", False) else job.voice_language
            spoken = pronounce(text, voice_language) if getattr(self.tts, "respells", True) else text
            t = time.monotonic()
            pieces: list[np.ndarray] = []
            rate = 0
            with self._tts_lock:
                try:
                    for pcm, rate in self._synthesis(spoken, voice_language):
                        if not len(pcm):
                            continue
                        if job.cancel.is_set() or not self._first_sound(job):
                            return
                        with self._lock:
                            if job.cancel.is_set():
                                return          # cut meanwhile: never a sound, nor "speaking", after the cut
                            now = time.monotonic()
                            if filler:
                                lat.setdefault("filler_start", now - job.t_heard)
                            elif first:
                                lat["tts"] = now - t
                                lat["first_audio"] = now - job.t_heard
                                lat["audio_start"] = now - job.t_heard
                                first = False
                                self._first_play = now
                            if not job.gate_token:
                                job.gate_token = self.gate.speak()
                            self._set_status(Status.SPEAKING)
                            self.sink.play(pcm, rate)
                        pieces.append(pcm)
                except Exception:                               # noqa: BLE001 - never kill the speaker thread
                    log.exception("speech synthesis failed for %r", text)
                    continue
            if pieces and self._listeners:
                pcm = np.concatenate(pieces)
                extra = {"reply_id": job.reply_id} if job.reply_id else {}
                self._emit("say", text=text, seconds=round(len(pcm) / rate, 3), envelope=envelope(pcm, rate),
                           **extra)

    def _synthesis(self, text: str, language: str):
        """The clause as (pcm, rate) pieces: as they come from a backend that streams, else whole."""
        stream = getattr(self.tts, "stream", None)
        if stream is not None:
            yield from stream(text, language)
        else:
            yield self.tts.synthesize(text, language)

    def _first_sound(self, job: _Job) -> bool:
        """Before the first sound of an answer: waits while the person goes on talking right after
        their question (`_maybe_hold`). False if the answer was cancelled meanwhile."""
        while not job.audible:
            with self._hold_lock:
                if self._audio_ok.is_set():
                    job.audible = True
                    if job.uid:
                        if len(self._heard_aloud) > 256:
                            self._heard_aloud.clear()
                        self._heard_aloud.add(job.uid)
                    break
            if job.cancel.is_set():
                return False
            self._audio_ok.wait(0.02)
        return not job.cancel.is_set()

    def _speak_all(self, job: _Job, produce: Callable[[Callable[..., None]], None], lat: dict) -> str:
        """Runs `produce(emit)` (which calls emit(clause, language) as the answer comes, and
        emit.filler(text) for a few words said while a tool runs) with a speaker thread, waits until
        everything is played, and returns what was said."""
        sentences: queue.Queue = queue.Queue()
        said: list[str] = []

        def emit(chunk: str, language: str | None = None) -> None:
            said.append(str(chunk))
            self.echo.speaking(" ".join(said))      # filter it before it is even played
            sentences.put((chunk, language))

        emit.filler = lambda text, language=None: emit(_Filler(text), language)

        self._first_play = None
        spk = threading.Thread(target=self._speaker, args=(job, sentences, lat), name="marvin-voice-speaker",
                               daemon=True)
        spk.start()
        try:
            produce(emit)
        finally:
            sentences.put(_DONE)
            # a cut answer does not wait for a synthesis still running (it is never played)
            spk.join(None if not job.cancel.is_set() else 0.05)
            if not job.cancel.is_set():
                self.sink.wait()
                if self._first_play is not None and "audio_start" in lat:
                    # when the speaker started playing it (a sink that knows says so), plus its latency
                    began = getattr(self.sink, "playback_started", None)
                    began = began if began is not None and began >= self._first_play else self._first_play
                    lat["playback_start"] = began - job.t_heard + self.device.output_latency_s
                self.gate.done(job.gate_token)
            self.echo.said(" ".join(said))
        return " ".join(" ".join(said).split())

    def _say(self, job: _Job) -> None:
        clauses = job.clauses or [(job.text, job.language)]
        text = self._speak_all(job, lambda emit: [emit(t, lang) for t, lang in clauses], {})
        if not job.cancel.is_set():
            self._emit("reply", text=text, language=job.language, latency={}, interrupted=False,
                       proactive=True, error=None, hint="")
            if self.on_reply:
                self.on_reply(text)


def load_tts(c: VoiceConfig) -> TTS:
    """The speech synthesis `c` asks for, with its voice loaded now (Piper: seconds), not at the
    first answer."""
    from .tts import MacSayTTS, make_tts
    language = c.language or c.default_language
    tts = make_tts(c.tts, c.tts_voice, language)
    t = time.monotonic()
    if isinstance(tts, MacSayTTS) and importlib.util.find_spec("piper") is None:
        log.info("tip: pip install piper-tts for ~5x faster speech (Marvin uses it automatically)")
    try:
        tts.synthesize("Bonjour." if language == "fr" else "Hello.", language)
        if getattr(tts, "multilingual", False):         # one voice for both: warm the other language too
            other = "en" if language == "fr" else "fr"
            tts.synthesize("Hello." if other == "en" else "Bonjour.", other)
        log.info("speech synthesis ready in %.1f s", time.monotonic() - t)
    except Exception as e:
        log.warning("speech synthesis failed to start: %s", e)
    return tts


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
    if "turn" in lat:
        parts.append(f"end of turn {lat['turn']:.3f}")
    if lat.get("queue", 0.0) >= 0.05:
        parts.append(f"backlog {lat['queue']:.2f}")
    stt = f"speech recognition {lat.get('stt', 0.0):.2f}"
    parts.append(stt + (" (speculative)" if lat.get("speculative") else ""))
    if "llm_first_token" in lat:
        parts.append(f"model first token {lat['llm_first_token']:.2f}")
    if "tools" in lat:
        parts.append(f"tools {lat['tools']:.2f}")
    if "llm_first_token_2" in lat:
        parts.append(f"model again {lat['llm_first_token_2']:.2f}")
    if "first_chunk" in lat:
        parts.append(f"first chunk {lat['first_chunk']:.2f}")
    if "tts" in lat:
        parts.append(f"synthesis {lat['tts']:.2f}")
    return f"first word {first_word:.2f} s after you stopped talking ({', '.join(parts)} s)"
