"""Text to speech, rendered to PCM so it can go to any `AudioSink` (the computer's speakers today,
the robot's speaker later).

Backends (all local):
- `MacSayTTS`: macOS `say`, nothing to install. Picks a voice for the language (Thomas for French,
  Daniel for English; the enhanced/premium versions if you downloaded them in System Settings).
- `PiperTTS`: Piper neural voices (`pip install piper-tts`), better than `say` on Linux and close
  to it on a Mac. Voices are downloaded once into ~/.local/share/marvin/piper.
- `EspeakTTS`: espeak-ng, robotic but everywhere on Linux. A last resort.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import importlib.util
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import Protocol

import numpy as np

from .io import read_wav

log = logging.getLogger("marvin.voice.tts")


class TTS(Protocol):
    def synthesize(self, text: str, language: str = "fr") -> tuple[np.ndarray, int]:
        """-> (mono int16 PCM, sample rate)."""
        ...


# ---------------------------------------------------------------- macOS say

def parse_say_voices(listing: str) -> list[tuple[str, str]]:
    """`say -v '?'` output -> [(voice name, locale)], e.g. ("Thomas (Enhanced)", "fr_FR")."""
    matches = (re.match(r"^(.+?)\s+([a-z]{2,3}_[A-Z0-9]{2,3})\s+#", line) for line in listing.splitlines())
    return [(m.group(1).strip(), m.group(2)) for m in matches if m]


class MacSayTTS:
    PREFERRED = {"fr": ["Thomas", "Audrey", "Aurélie", "Amélie"],
                 "en": ["Daniel", "Serena", "Samantha", "Alex"]}   # Daniel: British, like the android

    def __init__(self, voices: dict[str, str] | None = None, rate_wpm: int | None = None):
        if not shutil.which("say"):
            raise RuntimeError("`say` not found: MacSayTTS only works on macOS")
        self.rate_wpm = rate_wpm
        self._fixed = dict(voices or {})
        self._installed: list[tuple[str, str]] | None = None    # (name, locale)

    def installed_voices(self) -> list[tuple[str, str]]:
        if self._installed is None:
            out = subprocess.run(["say", "-v", "?"], capture_output=True, text=True, check=False).stdout
            self._installed = parse_say_voices(out)
        return self._installed

    def voice_for(self, language: str) -> str | None:
        if language in self._fixed:
            return self._fixed[language]
        voices = [(n, loc) for n, loc in self.installed_voices() if loc.split("_")[0] == language]
        if not voices:
            return None
        pref = self.PREFERRED.get(language, [])

        def rank(v):
            name, loc = v
            base = name.split(" (")[0]
            quality = 0 if ("Premium" in name or "Enhanced" in name or "Premium" in loc) else 1
            main_locale = 0 if loc in ("fr_FR", "en_GB", "en_US") else 1
            return (pref.index(base) if base in pref else len(pref), quality, main_locale)

        return min(voices, key=rank)[0]

    def synthesize(self, text: str, language: str = "fr") -> tuple[np.ndarray, int]:
        with tempfile.TemporaryDirectory(prefix="marvin-say-") as d:
            txt, wav = Path(d) / "in.txt", Path(d) / "out.wav"
            txt.write_text(text, encoding="utf-8")
            cmd = ["say", "-o", str(wav), "--file-format=WAVE", "--data-format=LEI16@16000", "-f", str(txt)]
            voice = self.voice_for(language)
            if voice:
                cmd[1:1] = ["-v", voice]
            if self.rate_wpm:
                cmd[1:1] = ["-r", str(self.rate_wpm)]
            subprocess.run(cmd, check=True, capture_output=True)
            return read_wav(wav)


# ---------------------------------------------------------------- Piper

def piper_dir() -> Path:
    return Path(os.environ.get("MARVIN_PIPER_DIR", Path.home() / ".local" / "share" / "marvin" / "piper"))


class PiperTTS:
    """Piper voices, one per language. A missing voice is downloaded on first use (from Hugging
    Face, once); after that everything runs offline."""

    DEFAULT_VOICES = {"fr": "fr_FR-siwis-medium", "en": "en_GB-alan-medium"}

    def __init__(self, voices: dict[str, str] | None = None, voice_dir: str | Path | None = None,
                 download: bool = True):
        if importlib.util.find_spec("piper") is None:
            raise RuntimeError("Piper is not installed: pip install piper-tts")
        self.voices = {**self.DEFAULT_VOICES, **(voices or {})}
        self.voice_dir = Path(voice_dir) if voice_dir else piper_dir()
        self.download = download
        self._loaded: dict[str, object] = {}
        self._lock = threading.Lock()

    def _voice(self, language: str):
        name = self.voices.get(language) or self.voices["en"]
        with self._lock:
            if name not in self._loaded:
                from piper import PiperVoice
                path = Path(name) if name.endswith(".onnx") else self.voice_dir / f"{name}.onnx"
                if not path.exists():
                    if not self.download:
                        raise RuntimeError(f"Piper voice {path} missing: python -m piper.download_voices "
                                           f"--download-dir {self.voice_dir} {name}")
                    from piper.download_voices import download_voice
                    log.info("downloading Piper voice %s to %s (once)", name, self.voice_dir)
                    self.voice_dir.mkdir(parents=True, exist_ok=True)
                    download_voice(name, self.voice_dir)
                self._loaded[name] = PiperVoice.load(str(path))
            return self._loaded[name]

    def synthesize(self, text: str, language: str = "fr") -> tuple[np.ndarray, int]:
        voice = self._voice(language)
        chunks = list(voice.synthesize(text))
        if not chunks:
            return np.zeros(0, np.int16), 16000
        return np.concatenate([c.audio_int16_array for c in chunks]).astype(np.int16), chunks[0].sample_rate


# ---------------------------------------------------------------- espeak-ng

class EspeakTTS:
    def __init__(self, voices: dict[str, str] | None = None, speed_wpm: int = 165):
        self.exe = shutil.which("espeak-ng") or shutil.which("espeak")
        if not self.exe:
            raise RuntimeError("espeak-ng not found (apt install espeak-ng / brew install espeak-ng)")
        self.voices = {"fr": "fr-fr", "en": "en-gb", **(voices or {})}
        self.speed_wpm = speed_wpm

    def synthesize(self, text: str, language: str = "fr") -> tuple[np.ndarray, int]:
        with tempfile.TemporaryDirectory(prefix="marvin-espeak-") as d:
            wav = Path(d) / "out.wav"
            subprocess.run([self.exe, "-v", self.voices.get(language, language), "-s", str(self.speed_wpm),
                            "-w", str(wav), "--", text], check=True, capture_output=True)
            return read_wav(wav)


# ---------------------------------------------------------------- tests / factory

class FakeTTS:
    """For tests: 20 ms of a quiet tone per character, and a record of what was said."""

    def __init__(self, ms_per_char: float = 20.0):
        self.ms_per_char = ms_per_char
        self.said: list[tuple[str, str]] = []

    def synthesize(self, text: str, language: str = "fr") -> tuple[np.ndarray, int]:
        self.said.append((text, language))
        n = int(len(text) * self.ms_per_char * 16)
        t = np.arange(n) / 16000
        return (np.sin(2 * np.pi * 220 * t) * 3000).astype(np.int16), 16000


BACKENDS = ("auto", "say", "piper", "espeak")


def make_tts(kind: str = "auto", voice: str | None = None, language: str | None = None) -> TTS:
    """'say', 'piper', 'espeak' or 'auto' (Piper if installed, else say on macOS, else espeak-ng).
    `voice` overrides the voice for `language` (a `say` voice name, a Piper voice name or .onnx
    path, an espeak voice)."""
    voices = {language or "fr": voice} if voice else None
    if kind == "say":
        return MacSayTTS(voices)
    if kind == "piper":
        return PiperTTS(voices)
    if kind == "espeak":
        return EspeakTTS(voices)
    if kind != "auto":
        raise ValueError(f"unknown TTS backend {kind!r}, expected one of {BACKENDS}")
    # Piper first when installed: it synthesises a sentence in a fraction of the time `say` needs
    # to render one to a file (about 0.1-0.2 s against 0.9 s on an M3), which is most of the
    # delay before Marvin's first word.
    try:
        return PiperTTS(voices)
    except RuntimeError:
        pass
    if sys.platform == "darwin" and shutil.which("say"):
        return MacSayTTS(voices)
    return EspeakTTS(voices)
