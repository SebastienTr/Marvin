"""Speech to text: faster-whisper (CTranslate2 Whisper), running locally on the CPU.

`WhisperSTT` transcribes one utterance at a time. The language is either fixed or detected among a
short list (French and English by default): Whisper's detection is unreliable on a one-second
"Marvin?", so a detection that is not clearly one of the allowed languages is reported as
unconfident, and the assistant keeps the language of the conversation.

The name "Marvin" is passed as a hotword: without it, Whisper often hears "Marvain" or glues it to
the previous word ("Dimaervin").

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Callable, Iterable, Protocol

import numpy as np

from ..audio import SAMPLE_RATE

log = logging.getLogger("marvin.voice.stt")


@dataclass
class Transcript:
    text: str
    language: str | None = None     # ISO 639-1 code, None if unknown
    confident: bool = False         # the language detection is trustworthy
    seconds: float = 0.0            # time spent transcribing


class STT(Protocol):
    def transcribe(self, pcm: np.ndarray, language: str | None = None) -> Transcript:
        """`pcm`: 16 kHz mono int16. `language`: force a language, or None to detect it."""
        ...


class WhisperSTT:
    """faster-whisper. `model`: tiny, base, small, medium, large-v3, turbo, or a local path.
    Models are downloaded from Hugging Face on first use, then cached (~/.cache/huggingface)."""

    def __init__(self, model: str = "small", languages: Iterable[str] = ("fr", "en"),
                 device: str = "auto", compute_type: str = "int8", beam_size: int = 1,
                 hotwords: str | None = "Marvin", min_language_prob: float = 0.7, cpu_threads: int = 0):
        try:
            from faster_whisper import WhisperModel
        except ImportError as e:
            raise RuntimeError("speech recognition needs the 'voice' extra: pip install -e \".[voice]\"") from e
        logging.getLogger("faster_whisper").setLevel(logging.WARNING)
        t = time.monotonic()
        self.model_name = model
        kw = dict(device=device, compute_type=compute_type, cpu_threads=cpu_threads)
        try:        # once downloaded, never touch the network again
            self._model = WhisperModel(model, local_files_only=True, **kw)
        except Exception:
            log.info("downloading Whisper %s (once)...", model)
            self._model = WhisperModel(model, **kw)
        self.languages = tuple(languages)
        self.beam_size = beam_size
        self.hotwords = hotwords
        self.min_language_prob = min_language_prob
        log.info("whisper %s loaded in %.1f s", model, time.monotonic() - t)

    def _run(self, audio: np.ndarray, language: str | None):
        segments, info = self._model.transcribe(
            audio, language=language, beam_size=self.beam_size, hotwords=self.hotwords,
            without_timestamps=True, condition_on_previous_text=False, vad_filter=False)
        return " ".join(s.text.strip() for s in segments).strip(), info

    def transcribe(self, pcm: np.ndarray, language: str | None = None) -> Transcript:
        t = time.monotonic()
        audio = np.asarray(pcm, dtype=np.float32) / 32768.0
        if language:
            text, _ = self._run(audio, language)
            return Transcript(text, language, True, time.monotonic() - t)
        text, info = self._run(audio, None)
        probs = dict(info.all_language_probs or [(info.language, info.language_probability)])
        allowed = self.languages or (info.language,)
        best = max(allowed, key=lambda lang: probs.get(lang, 0.0))
        total = sum(probs.get(lang, 0.0) for lang in allowed)
        share = probs.get(best, 0.0) / total if total > 0 else 0.0
        confident = share >= self.min_language_prob and probs.get(best, 0.0) >= 0.3
        if info.language != best:           # decoded in a language we do not speak: decode again
            text, _ = self._run(audio, best)
        return Transcript(text, best, confident, time.monotonic() - t)


class FakeSTT:
    """For tests: returns scripted transcripts, one per call, or whatever `fn(pcm)` returns.
    Items can be strings or `Transcript`s."""

    def __init__(self, script: Iterable[str | Transcript] | Callable[[np.ndarray], str | Transcript] = (),
                 language: str = "fr"):
        self._fn = script if callable(script) else None
        self._script = [] if callable(script) else list(script)
        self.language = language
        self.calls: list[float] = []        # duration of each transcribed buffer, seconds

    def transcribe(self, pcm: np.ndarray, language: str | None = None) -> Transcript:
        self.calls.append(len(pcm) / SAMPLE_RATE)
        out = self._fn(pcm) if self._fn else (self._script.pop(0) if self._script else "")
        if isinstance(out, Transcript):
            return out
        return Transcript(out, language or self.language, True)
