"""Test mode: a scripted microphone and a Whisper that understands it, without sound card or model.

`python -m marvin_host.sidecar.voice --fake --say "2:Marvin, quelle heure est-il ?"` gives a
sidecar whose computer microphone is `ScriptedMic` (quiet room noise, and each scripted sentence
spoken at its time as a buzzy synthetic voice), whose speech recognition is `ScriptedWhisper` and
whose speech synthesis is `FakeTTS` (a 220 Hz tone, 20 ms per character). End-to-end tests of the
core run the real sidecar this way, with the real VAD, wake word, listening windows, echo gate and
pacing.

How the fake Whisper knows what was said: phrase number k is spoken with the fundamental
frequency `phrase_hz(k)` = 110 + 20 k Hz (k < 15, and never 220 Hz, the fake voice's own tone), and
recognised from the loudest frequency of the audio it is given. A core that relays a robot's
microphone in a test can send the same audio (`speech(k, seconds)`) as AUDIO_IN frames.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import threading
import time
from typing import Iterator

import numpy as np

from ...audio import FRAME_MS, FRAME_SAMPLES, SAMPLE_RATE
from ...voice.stt import Transcript
from ...voice.text import guess_language

BASE_HZ = 110.0
STEP_HZ = 20.0
MAX_PHRASES = 15
OWN_VOICE_HZ = 220.0            # FakeTTS


def phrase_hz(k: int) -> float:
    if not 0 <= k < MAX_PHRASES:
        raise ValueError(f"the test mode knows at most {MAX_PHRASES} phrases")
    return BASE_HZ + STEP_HZ * k


def speech(k: int, seconds: float) -> np.ndarray:
    """Phrase `k` as audio: a harmonic tone with a syllable-like envelope, loud enough for the VAD."""
    t = np.arange(int(seconds * SAMPLE_RATE)) / SAMPLE_RATE
    f0 = phrase_hz(k)
    x = sum(np.sin(2 * np.pi * f0 * h * t) / h for h in range(1, 8))
    env = 0.6 + 0.4 * np.abs(np.sin(2 * np.pi * 3 * t))
    return (x * env * 5000).astype(np.int16)


def speech_seconds(text: str) -> float:
    """How long a phrase is said: about a normal speaking rate."""
    return float(np.clip(0.3 + 0.055 * len(text), 0.6, 4.0))


def dominant_hz(pcm: np.ndarray) -> float:
    if len(pcm) < 64:
        return 0.0
    spec = np.abs(np.fft.rfft(pcm.astype(np.float64) * np.hanning(len(pcm))))
    spec[: int(60 * len(pcm) / SAMPLE_RATE)] = 0            # no hum
    return float(np.argmax(spec) * SAMPLE_RATE / len(pcm))


class Script:
    """The phrases of the test: [(seconds from the start, text)]."""

    def __init__(self, lines: list[tuple[float, str]] | None = None):
        self.lines = sorted(lines or [], key=lambda x: x[0])
        if len(self.lines) > MAX_PHRASES:
            raise ValueError(f"the test mode knows at most {MAX_PHRASES} phrases")

    @classmethod
    def parse(cls, items: list[str]) -> "Script":
        """From "SECONDS:TEXT" items, e.g. "2:Marvin, quelle heure est-il ?"."""
        lines = []
        for item in items:
            at, sep, text = item.partition(":")
            try:
                t = float(at)
            except ValueError:
                t = -1
            if not sep or t < 0 or not text.strip():
                raise ValueError(f'expected SECONDS:TEXT, e.g. "2:Marvin, bonjour", not {item!r}')
            lines.append((t, text.strip()))
        return cls(lines)

    def text(self, k: int) -> str:
        return self.lines[k][1]

    def phrase_for(self, pcm: np.ndarray) -> int | None:
        """Which phrase `pcm` is, from its pitch; None for anything else (silence, its own voice)."""
        f = dominant_hz(pcm)
        if abs(f - OWN_VOICE_HZ) < STEP_HZ / 2 - 2:
            return None
        k = int(round((f - BASE_HZ) / STEP_HZ))
        if 0 <= k < len(self.lines) and abs(f - phrase_hz(k)) < STEP_HZ / 2 - 2:
            return k
        return None

    def audio(self, tail_s: float = 2.0) -> np.ndarray:
        """The whole script as one recording, with quiet room noise between the phrases."""
        end = max((t + speech_seconds(text) for t, text in self.lines), default=0.0) + tail_s
        rng = np.random.default_rng(1)
        out = rng.normal(0, 30, int(end * SAMPLE_RATE))
        for k, (t, text) in enumerate(self.lines):
            s = speech(k, speech_seconds(text))
            i = int(t * SAMPLE_RATE)
            out[i:i + len(s)] += s[: len(out) - i]
        return np.clip(out, -32768, 32767).astype(np.int16)


class ScriptedWhisper:
    """An STT that recognises the script's phrases by their pitch (see the module docstring)."""

    def __init__(self, script: Script, languages: tuple[str, ...] = ("fr", "en")):
        self.script = script
        self.languages = languages
        self.calls = 0

    def transcribe(self, pcm: np.ndarray, language: str | None = None) -> Transcript:
        t = time.monotonic()
        self.calls += 1
        k = self.script.phrase_for(pcm)
        if k is None:
            return Transcript("", language or self.languages[0], False, time.monotonic() - t)
        text = self.script.text(k)
        lang = language or guess_language(text, self.languages) or self.languages[0]
        return Transcript(text, lang, True, time.monotonic() - t, language_prob=0.99)


class ScriptedMic:
    """An AudioSource that plays the script at real time (`speed` 1) or faster, then quiet room
    noise until it is closed."""

    def __init__(self, script: Script, speed: float = 1.0):
        self.pcm = script.audio()
        self.speed = max(0.01, speed)
        self._closed = threading.Event()
        self._rng = np.random.default_rng(2)

    def frames(self) -> Iterator[np.ndarray]:
        t0 = time.monotonic()
        i = 0
        while not self._closed.is_set():
            due = t0 + i * FRAME_MS / 1000 / self.speed
            delay = due - time.monotonic()
            if delay > 0 and self._closed.wait(delay):
                return
            a = i * FRAME_SAMPLES
            if a + FRAME_SAMPLES <= len(self.pcm):
                yield self.pcm[a:a + FRAME_SAMPLES].copy()
            else:
                yield self._rng.normal(0, 30, FRAME_SAMPLES).astype(np.int16)
            i += 1

    def close(self) -> None:
        self._closed.set()
