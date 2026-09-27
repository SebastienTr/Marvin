"""Voice activity detection: which 20 ms frames contain speech, and where utterances start and end.

`WebRtcVad` (the `webrtcvad` module, from the `webrtcvad-wheels` package) is the default: tiny,
fast, no model file. `EnergyVad` is a dependency-free fallback that tracks the noise floor and
flags frames well above it; it is also what the tests use because it is deterministic.

`Segmenter` turns per-frame decisions into utterances: speech starts when most of the last few
frames are voiced, ends after a stretch of silence, and keeps a short pre-roll so the first
syllable ("Mar-") is not clipped.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import math
from collections import deque
from dataclasses import dataclass
from typing import Protocol

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


def make_vad(kind: str = "auto", aggressiveness: int = 2) -> Vad:
    """'webrtc', 'energy', or 'auto' (webrtc if installed)."""
    if kind in ("auto", "webrtc"):
        try:
            return WebRtcVad(aggressiveness)
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

    @property
    def duration(self) -> float:
        return len(self.pcm) / SAMPLE_RATE


@dataclass
class SegmenterConfig:
    start_window: int = 10          # frames looked at to decide that speech started (200 ms)...
    start_ratio: float = 0.6        # ...this share of them voiced
    end_silence_s: float = 0.7      # this much silence ends the utterance
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

    @property
    def in_speech(self) -> bool:
        """True while an utterance is going on."""
        return self._in_speech

    @property
    def time(self) -> float:
        """Audio time, seconds."""
        return self._n * FRAME_MS / 1000

    def feed(self, frame: np.ndarray) -> Segment | None:
        c = self.config
        voiced = self.vad.is_speech(frame)
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
                self._in_speech = True
                self._silence = 0
                self._ring.clear()
                self._voiced.clear()
            return None
        self._frames.append(frame)
        if voiced:
            self._silence = 0
            self._voiced_count += 1
        else:
            self._silence += 1
        if self._silence * FRAME_MS / 1000 >= c.end_silence_s:
            trim = max(0, self._silence - int(0.2 * 1000 / FRAME_MS))       # keep 200 ms of trailing silence
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
        self._ring.clear()
        self._voiced.clear()

    def _finish(self, trim: int) -> Segment | None:
        frames = self._frames[:len(self._frames) - trim] if trim else self._frames
        voiced = self._voiced_count
        self.reset()
        if voiced * FRAME_MS / 1000 < self.config.min_speech_s or not frames:
            return None
        pcm = np.concatenate(frames)
        t0 = self._start_n * FRAME_MS / 1000
        return Segment(pcm, t0, t0 + len(pcm) / SAMPLE_RATE)

