"""Voice activity detection: which 20 ms frames contain speech, and where utterances start and end.

`SileroVad` (Silero VAD v6, a 2 MB ONNX model run by onnxruntime, models.py) is the default when
its file is here: a neural network that tells speech from noise, keyboard and music far better
than energy or WebRTC's GMM. `WebRtcVad` (the `webrtcvad` module, from the `webrtcvad-wheels`
package) is the fallback: tiny, fast, no model file. `EnergyVad` is a dependency-free fallback
that tracks the noise floor and flags frames well above it; it is also what the tests use because
it is deterministic.

`Segmenter` turns per-frame decisions into utterances: speech starts when most of the last few
frames are voiced, ends after a stretch of silence, and keeps a short pre-roll so the first
syllable ("Mar-") is not clipped. The silence that ends an utterance is `end_silence_s`, unless
`endpoint` (a callback the engine sets) asks for a longer one for this pause: Smart Turn hears an
unfinished turn (turn.py), or without it the words so far announce more ("..., et", "parce que",
a lone "OK").

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import math
from collections import deque
from dataclasses import dataclass
from typing import Callable, Protocol

import numpy as np

from ..audio import FRAME_MS, SAMPLE_RATE

log = logging.getLogger("marvin.voice.vad")


class Vad(Protocol):
    def is_speech(self, frame: np.ndarray) -> bool: ...


def rms_dbfs(pcm: np.ndarray) -> float:
    """Level of an int16 buffer in dB relative to full scale (silence = -100)."""
    if len(pcm) == 0:
        return -100.0
    r = math.sqrt(float(np.mean(pcm.astype(np.float64) ** 2))) / 32768.0
    return 20 * math.log10(r) if r > 1e-5 else -100.0


class WebRtcVad:
    """Google's WebRTC VAD. `aggressiveness` 0 (keeps most) to 3 (drops most non-speech)."""

    def __init__(self, aggressiveness: int = 2, min_dbfs: float = -60.0):
        import webrtcvad
        self._vad = webrtcvad.Vad(aggressiveness)
        self.min_dbfs = min_dbfs            # below this, not speech whatever webrtcvad says

    def is_speech(self, frame: np.ndarray) -> bool:
        if rms_dbfs(frame) < self.min_dbfs:
            return False
        return self._vad.is_speech(np.asarray(frame, dtype="<i2").tobytes(), SAMPLE_RATE)


class EnergyVad:
    """Speech = louder than the tracked noise floor by `margin_db` (and above `min_dbfs`)."""

    def __init__(self, margin_db: float = 12.0, min_dbfs: float = -50.0, floor_tau_s: float = 3.0):
        self.margin_db = margin_db
        self.min_dbfs = min_dbfs
        self._a_up = FRAME_MS / 1000 / floor_tau_s      # the floor rises slowly...
        self.floor = -70.0                               # ...and falls at once

    def is_speech(self, frame: np.ndarray) -> bool:
        db = rms_dbfs(frame)
        speech = db > self.min_dbfs and db > self.floor + self.margin_db
        if db < self.floor:
            self.floor = db
        elif not speech:
            self.floor += self._a_up * (db - self.floor)
        return speech


class SileroVad:
    """Silero VAD v6: the probability that the last 32 ms (512 samples) are speech, from a small
    recurrent network. Frames of 20 ms are buffered into the model's 512-sample chunks (each with
    the 64 samples before it, as Silero's own wrapper does); a frame's decision is the latest
    chunk's, with hysteresis: speech from `threshold`, silence again below `threshold - 0.15`.
    Frames quieter than `min_dbfs` are never speech (the device's floor)."""

    CHUNK = 512
    CONTEXT = 64
    _sessions: dict = {}

    def __init__(self, threshold: float = 0.5, min_dbfs: float = -60.0, path=None, session=None):
        from . import models
        if session is None:
            path = path or models.path_of("silero-vad")
            if path is None:
                raise FileNotFoundError("Silero VAD is not downloaded (python -m marvin_host.voice.models --prefetch)")
            key = str(path)
            session = SileroVad._sessions.get(key)
            if session is None:
                session = SileroVad._sessions[key] = models.onnx_session(path)
        self._session = session
        self.threshold = float(threshold)
        self.min_dbfs = min_dbfs
        self.reset()

    def reset(self) -> None:
        self._state = np.zeros((2, 1, 128), np.float32)
        self._context = np.zeros(self.CONTEXT, np.float32)
        self._buf = np.zeros(0, np.float32)
        self._speech = False
        self.prob = 0.0                     # the latest chunk's probability

    def probability(self, chunk: np.ndarray) -> float:
        """Runs one 512-sample float chunk through the model (its state moves on)."""
        x = np.concatenate([self._context, chunk]).astype(np.float32)[None, :]
        out, self._state = self._session.run(None, {"input": x, "state": self._state,
                                                    "sr": np.array(16000, dtype=np.int64)})
        self._context = x[0, -self.CONTEXT:]
        return float(out[0][0])

    def is_speech(self, frame: np.ndarray) -> bool:
        self._buf = np.concatenate([self._buf, np.asarray(frame, np.float32) / 32768.0])
        while len(self._buf) >= self.CHUNK:
            chunk, self._buf = self._buf[:self.CHUNK], self._buf[self.CHUNK:]
            self.prob = self.probability(chunk)
            if self.prob >= self.threshold:
                self._speech = True
            elif self.prob < self.threshold - 0.15:
                self._speech = False
        return self._speech and rms_dbfs(frame) >= self.min_dbfs


VAD_CHOICES = ("auto", "silero", "webrtc", "energy")


def make_vad(kind: str = "auto", aggressiveness: int = 2, min_dbfs: float = -60.0, threshold: float = 0.5) -> Vad:
    """'silero', 'webrtc', 'energy', or 'auto' (Silero when its model is downloaded, else WebRTC
    if installed, else energy). `aggressiveness` (WebRTC), `min_dbfs` and `threshold` (Silero):
    the device's (device.py)."""
    if kind in ("auto", "silero"):
        try:
            return SileroVad(threshold or 0.5, min_dbfs)
        except (FileNotFoundError, ImportError) as e:
            if kind == "silero":
                raise
            log.info("Silero VAD unavailable (%s): using WebRTC VAD", e)
        except Exception as e:                          # noqa: BLE001 - a broken file or runtime
            if kind == "silero":
                raise
            log.warning("Silero VAD failed to load (%s): using WebRTC VAD", e)
    if kind in ("auto", "webrtc"):
        try:
            return WebRtcVad(aggressiveness, min_dbfs)
        except ImportError:
            if kind == "webrtc":
                raise
            log.warning("webrtcvad not installed, using the energy VAD (pip install webrtcvad-wheels)")
    return EnergyVad()


@dataclass
class Segment:
    """One utterance. Times are audio time: seconds of audio consumed since the start."""

    pcm: np.ndarray
    t_start: float
    t_end: float
    t_speech_end: float = 0.0       # end of the last voiced frame (t_end minus the kept trailing silence)
    uid: int = 0                    # utterance number, see `Segmenter.pending()`
    voiced: int = 0                 # voiced frames in it
    t_speech_start: float = -1.0    # start of the first voiced frame (t_start plus the pre-roll); -1: unknown

    @property
    def speech_start(self) -> float:
        """When the speaker started talking (audio time)."""
        return self.t_speech_start if self.t_speech_start >= 0 else self.t_start

    @property
    def duration(self) -> float:
        return len(self.pcm) / SAMPLE_RATE


@dataclass
class SegmenterConfig:
    start_window: int = 10          # frames looked at to decide that speech started (200 ms)...
    start_ratio: float = 0.6        # ...this share of them voiced
    end_silence_s: float = 0.55     # this much silence ends the utterance (endpointing delay)
    end_silence_long_s: float = 1.1  # ...or this much when the words so far announce more (0: never)
    tail_kept_s: float = 0.15       # trailing silence kept in the segment
    pre_roll_s: float = 0.3         # audio kept before the detected start
    min_speech_s: float = 0.25      # shorter utterances (clicks, coughs) are dropped
    max_segment_s: float = 15.0     # cut long monologues here


class Segmenter:
    """Feed 20 ms frames, get `Segment`s back when utterances end."""

    def __init__(self, vad: Vad, config: SegmenterConfig | None = None):
        self.vad = vad
        self.config = c = config or SegmenterConfig()
        self._ring: deque[tuple[np.ndarray, bool]] = deque(maxlen=c.start_window + int(c.pre_roll_s * 1000 / FRAME_MS))
        self._voiced: deque[bool] = deque(maxlen=c.start_window)
        self._frames: list[np.ndarray] = []
        self._in_speech = False
        self._silence = 0
        self._voiced_count = 0
        self._n = 0                     # frames consumed
        self._start_n = 0
        self._voice_n = 0               # first voiced frame of the utterance
        self._uid = 0
        self._end_s: float | None = None    # the silence that ends this pause, once decided
        # called once per pause, when the silence reaches end_silence_s: the silence that should end this
        # pause (seconds), or None for end_silence_s (voice/engine.py: the adaptive endpoint)
        self.endpoint: Callable[[], float | None] | None = None

    @property
    def in_speech(self) -> bool:
        """True while an utterance is going on."""
        return self._in_speech

    @property
    def time(self) -> float:
        """Audio time, seconds."""
        return self._n * FRAME_MS / 1000

    @property
    def speech_start(self) -> float | None:
        """When the current utterance's first voiced frame began (audio time), None outside utterances."""
        return self._voice_n * FRAME_MS / 1000 if self._in_speech else None

    @property
    def silence_s(self) -> float:
        """Silence since the last voiced frame of the current utterance (0 outside utterances)."""
        return self._silence * FRAME_MS / 1000 if self._in_speech else 0.0

    @property
    def utterance_key(self) -> tuple[int, int]:
        """(uid, voiced frames) of the current utterance: unchanged while the speaker pauses."""
        return self._uid, self._voiced_count

    def pending(self) -> Segment | None:
        """The current utterance as it would end now (for speculative transcription), or None.
        It is still valid when the segment really ends if `uid` and `voiced` are unchanged."""
        if not self._in_speech:
            return None
        keep = int(self.config.tail_kept_s * 1000 / FRAME_MS)
        frames = self._frames[:len(self._frames) - max(0, self._silence - keep)]
        pcm = np.concatenate(frames)
        t0 = self._start_n * FRAME_MS / 1000
        return Segment(pcm, t0, t0 + len(pcm) / SAMPLE_RATE, self.time - self.silence_s, self._uid, self._voiced_count,
                       self._voice_n * FRAME_MS / 1000)

    def advance(self) -> None:
        """A frame that was not heard (microphone muted while Marvin speaks): time moves on, and
        any utterance in progress is dropped."""
        self._n += 1
        self.reset()

    def feed(self, frame: np.ndarray, masked: bool = False) -> Segment | None:
        """`masked`: the frame is kept (pre-roll, the utterance going on) but never counts as speech
        (Marvin's own earcon, see echo.EchoGate)."""
        c = self.config
        voiced = self.vad.is_speech(frame) and not masked
        self._n += 1
        if not self._in_speech:
            self._ring.append((frame, voiced))
            self._voiced.append(voiced)
            if len(self._voiced) == self._voiced.maxlen and sum(self._voiced) >= c.start_ratio * len(self._voiced):
                pre = int(c.pre_roll_s * 1000 / FRAME_MS)
                ring = list(self._ring)
                first = next(i for i, (_, v) in enumerate(ring) if v)       # the first voiced frame...
                kept = ring[max(0, first - pre):]                           # ...plus the pre-roll
                self._frames = [f for f, _ in kept]
                self._voiced_count = sum(v for _, v in kept)
                self._start_n = self._n - len(kept)
                self._voice_n = self._start_n + (first - max(0, first - pre))
                self._in_speech = True
                self._uid += 1
                self._silence = 0
                self._ring.clear()
                self._voiced.clear()
            return None
        self._frames.append(frame)
        if voiced:
            self._silence = 0
            self._voiced_count += 1
            self._end_s = None
        else:
            self._silence += 1
        if self._silence * FRAME_MS / 1000 >= c.end_silence_s:
            if self._end_s is None:
                self._end_s = c.end_silence_s
                if self.endpoint is not None:
                    try:
                        self._end_s = max(c.end_silence_s, float(self.endpoint() or 0.0))
                    except Exception:                       # noqa: BLE001 - never lose the utterance
                        log.exception("endpoint decision failed")
            if self._silence * FRAME_MS / 1000 >= self._end_s - 1e-9:
                trim = max(0, self._silence - int(c.tail_kept_s * 1000 / FRAME_MS))
                return self._finish(trim)
        if len(self._frames) * FRAME_MS / 1000 >= c.max_segment_s:
            return self._finish(0)
        return None

    def flush(self) -> Segment | None:
        """Ends the current utterance, if any (end of the stream)."""
        return self._finish(self._silence) if self._in_speech else None

    def reset(self) -> None:
        self._in_speech = False
        self._frames = []
        self._silence = 0
        self._end_s = None
        self._ring.clear()
        self._voiced.clear()

    def _finish(self, trim: int) -> Segment | None:
        frames = self._frames[:len(self._frames) - trim] if trim else self._frames
        voiced = self._voiced_count
        speech_end = self.time - self._silence * FRAME_MS / 1000
        self.reset()
        if voiced * FRAME_MS / 1000 < self.config.min_speech_s or not frames:
            return None
        pcm = np.concatenate(frames)
        t0 = self._start_n * FRAME_MS / 1000
        return Segment(pcm, t0, t0 + len(pcm) / SAMPLE_RATE, speech_end, self._uid, voiced,
                       self._voice_n * FRAME_MS / 1000)
