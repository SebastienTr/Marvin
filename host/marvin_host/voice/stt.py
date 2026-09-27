"""Speech to text, locally: Whisper on the Apple Silicon GPU (MLX) or on the CPU (faster-whisper).

- `MlxWhisperSTT` (package `mlx-whisper`, Apple Silicon only): large-v3-turbo on the GPU, a few
  hundred milliseconds per question and much better French than the small CPU models.
- `WhisperSTT` (faster-whisper, CTranslate2): everywhere else; `small` by default.

`make_stt("auto")` picks MLX on an Apple Silicon Mac when it is installed, faster-whisper otherwise.

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
    language_prob: float | None = None      # probability of `language`, None if the backend does not say
    # decoder scores of the kept Whisper segments (worst of them), None if unknown
    no_speech_prob: float | None = None
    avg_logprob: float | None = None
    compression_ratio: float | None = None
    rejected: str | None = None     # every segment looked like non-speech: why (the text is then "")


def _keep_segments(segments) -> tuple[str, dict, str | None]:
    """Drops the Whisper segments that look like non-speech (filters.decoder_reason).
    `segments`: objects or dicts with text, no_speech_prob, avg_logprob, compression_ratio.
    Returns (text of the kept ones, worst scores of the kept ones, reason if none was kept)."""
    from .filters import decoder_reason

    def get(s, k):
        return s.get(k) if isinstance(s, dict) else getattr(s, k, None)

    kept, reasons = [], []
    scores = {"no_speech_prob": None, "avg_logprob": None, "compression_ratio": None}
    for s in segments:
        ns, lp, cr = get(s, "no_speech_prob"), get(s, "avg_logprob"), get(s, "compression_ratio")
        why = decoder_reason(ns, lp, cr)
        if why:
            reasons.append(why)
            continue
        kept.append((get(s, "text") or "").strip())
        if ns is not None:
            scores["no_speech_prob"] = max(ns, scores["no_speech_prob"] or 0.0)
        if lp is not None:
            scores["avg_logprob"] = lp if scores["avg_logprob"] is None else min(lp, scores["avg_logprob"])
        if cr is not None:
            scores["compression_ratio"] = max(cr, scores["compression_ratio"] or 0.0)
    rejected = reasons[0] if reasons and not kept else None
    return " ".join(t for t in kept if t).strip(), scores, rejected


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
        # temperature 0 only: the fallback to higher temperatures is where most inventions come
        # from on short, unclear clips; a doubtful segment is dropped instead (filters.py)
        segments, info = self._model.transcribe(
            audio, language=language, beam_size=self.beam_size, hotwords=self.hotwords, temperature=0.0,
            without_timestamps=True, condition_on_previous_text=False, vad_filter=False)
        return _keep_segments(list(segments)), info

    def transcribe(self, pcm: np.ndarray, language: str | None = None) -> Transcript:
        t = time.monotonic()
        audio = np.asarray(pcm, dtype=np.float32) / 32768.0
        if language:
            (text, scores, rejected), _ = self._run(audio, language)
            return Transcript(text, language, True, time.monotonic() - t, 1.0, rejected=rejected, **scores)
        (text, scores, rejected), info = self._run(audio, None)
        probs = dict(info.all_language_probs or [(info.language, info.language_probability)])
        allowed = self.languages or (info.language,)
        best = max(allowed, key=lambda lang: probs.get(lang, 0.0))
        total = sum(probs.get(lang, 0.0) for lang in allowed)
        share = probs.get(best, 0.0) / total if total > 0 else 0.0
        confident = share >= self.min_language_prob and probs.get(best, 0.0) >= 0.3
        if info.language != best:           # decoded in a language we do not speak: decode again
            (text, scores, rejected), _ = self._run(audio, best)
        return Transcript(text, best, confident, time.monotonic() - t, probs.get(best, 0.0),
                          rejected=rejected, **scores)


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


# ---------------------------------------------------------------- MLX (Apple Silicon GPU)

MLX_MODELS = {
    "tiny": "mlx-community/whisper-tiny-mlx",
    "base": "mlx-community/whisper-base-mlx",
    "small": "mlx-community/whisper-small-mlx",
    "medium": "mlx-community/whisper-medium-mlx",
    "large-v3": "mlx-community/whisper-large-v3-mlx",
    "turbo": "mlx-community/whisper-large-v3-turbo",
    "large-v3-turbo": "mlx-community/whisper-large-v3-turbo",
}
MLX_DEFAULT = "turbo"


def mlx_available() -> bool:
    """True on an Apple Silicon Mac with `mlx-whisper` installed."""
    import importlib.util
    import platform
    import sys
    return (sys.platform == "darwin" and platform.machine() == "arm64"
            and importlib.util.find_spec("mlx_whisper") is not None)


class MlxWhisperSTT:
    """mlx-whisper. `model`: a short name (tiny ... turbo, see MLX_MODELS) or a Hugging Face repo /
    local path with MLX weights. Default: large-v3-turbo (1.6 GB download, once).

    mlx-whisper does not expose language probabilities: the detected language counts as confident
    when it is one of `languages` and the utterance is long enough (at least `min_confident_s`).
    """

    def __init__(self, model: str = MLX_DEFAULT, languages: Iterable[str] = ("fr", "en"),
                 initial_prompt: str | None = "Marvin.", min_confident_s: float = 1.0):
        try:
            import mlx_whisper
        except ImportError as e:
            raise RuntimeError("the MLX backend needs `pip install mlx-whisper` (Apple Silicon only)") from e
        self._mlx = mlx_whisper
        self.repo = MLX_MODELS.get(model, model)
        self.model_name = model
        self.languages = tuple(languages)
        self.initial_prompt = initial_prompt
        self.min_confident_s = min_confident_s
        t = time.monotonic()
        # load and compile now, not at the first question
        self._run(np.zeros(SAMPLE_RATE // 2, np.float32), self.languages[0] if self.languages else "en")
        log.info("whisper %s (MLX, GPU) loaded in %.1f s", self.repo, time.monotonic() - t)

    def _run(self, audio: np.ndarray, language: str | None) -> dict:
        return self._mlx.transcribe(audio, path_or_hf_repo=self.repo, language=language, temperature=0.0,
                                    condition_on_previous_text=False, initial_prompt=self.initial_prompt,
                                    verbose=None)

    @staticmethod
    def _text(r: dict) -> tuple[str, dict, str | None]:
        segments = r.get("segments")
        if segments:
            return _keep_segments(segments)
        return (r.get("text") or "").strip(), {}, None

    def transcribe(self, pcm: np.ndarray, language: str | None = None) -> Transcript:
        t = time.monotonic()
        audio = np.asarray(pcm, dtype=np.float32) / 32768.0
        r = self._run(audio, language)
        (text, scores, rejected), lang = self._text(r), r.get("language") or language
        if language:
            return Transcript(text, language, True, time.monotonic() - t, 1.0, rejected=rejected, **scores)
        confident = lang in self.languages and len(pcm) / SAMPLE_RATE >= self.min_confident_s
        if self.languages and lang not in self.languages:     # e.g. a short clip detected as Welsh
            lang = self.languages[0]
            text, scores, rejected = self._text(self._run(audio, lang))
        # mlx-whisper gives no language probability: None (filters treat it as "not sure")
        return Transcript(text, lang, confident, time.monotonic() - t, None, rejected=rejected, **scores)


STT_BACKENDS = ("auto", "mlx", "faster-whisper")


def make_stt(backend: str = "auto", model: str | None = None, languages: Iterable[str] = ("fr", "en")) -> STT:
    """'mlx', 'faster-whisper', or 'auto' (MLX on Apple Silicon when installed, else faster-whisper).
    `model` None or "auto" picks the backend's default (turbo on MLX, small on faster-whisper)."""
    if backend not in STT_BACKENDS:
        raise ValueError(f"unknown STT backend {backend!r}, expected one of {STT_BACKENDS}")
    model = None if model in (None, "", "auto") else model
    if backend == "mlx" or (backend == "auto" and mlx_available()):
        try:
            return MlxWhisperSTT(model or MLX_DEFAULT, languages)
        except Exception as e:
            if backend == "mlx":
                raise
            log.warning("MLX Whisper failed (%s), falling back to faster-whisper on the CPU", e)
    return WhisperSTT(model or "small", languages)
