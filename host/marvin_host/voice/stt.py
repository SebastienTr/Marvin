"""Speech to text, locally: Parakeet (the default) or Whisper, on the Apple Silicon GPU or the CPU.

- `ParakeetSTT`: NVIDIA's Parakeet TDT 0.6B v3 (CC-BY-4.0), a transducer that writes 25 European
  languages with punctuation and capitals, the best French word error rate of the open models of
  its size, and no Whisper-style inventions on silence. On Apple Silicon through MLX
  (`parakeet-mlx`, model `mlx-community/parakeet-tdt-0.6b-v3`, 2.5 GB once); elsewhere through
  onnxruntime (`onnx-asr`, int8, 670 MB once: about 0.6 s for a 4 s question on two CPU cores).
  It does not say which language it heard: the turn's language is read from its words
  (text.detect_language). It has no word biasing: the vocabulary is applied after (lexicon.py).
- `MlxWhisperSTT` (package `mlx-whisper`, Apple Silicon only): large-v3-turbo on the GPU.
- `WhisperSTT` (faster-whisper, CTranslate2): the CPU fallback; `small` by default.

`make_stt("auto")` picks Parakeet when it can run here (MLX on Apple Silicon, or onnx-asr when
installed), else MLX Whisper on Apple Silicon, else faster-whisper. `"parakeet"`, `"mlx"` and
`"faster-whisper"` ask for one.

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
    confidence: float | None = None     # Parakeet: the mean of its tokens' confidence (0..1), None otherwise


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


# ---------------------------------------------------------------- Parakeet

PARAKEET_MLX = "mlx-community/parakeet-tdt-0.6b-v3"
PARAKEET_ONNX = "nemo-parakeet-tdt-0.6b-v3"          # onnx-asr's name for it
PARAKEET_MIN_CONFIDENCE = 0.15                        # below: "low confidence" (to tune on real use)


def parakeet_backend() -> str | None:
    """"mlx" on an Apple Silicon Mac with parakeet-mlx, "onnx" with onnx-asr, else None."""
    import importlib.util
    import platform
    import sys
    if (sys.platform == "darwin" and platform.machine() == "arm64"
            and importlib.util.find_spec("parakeet_mlx") is not None):
        return "mlx"
    if importlib.util.find_spec("onnx_asr") is not None and importlib.util.find_spec("onnxruntime") is not None:
        return "onnx"
    return None


PARAKEET_ONNX_REPO = "istupakov/parakeet-tdt-0.6b-v3-onnx"
PARAKEET_ONNX_FILES = ["config.json", "vocab.txt", "nemo128.onnx", "*.int8.onnx"]     # int8 only: 670 MB


def _local_snapshot(repo: str, patterns: list[str] | None = None) -> str:
    """The Hugging Face repo's local copy (downloaded once), so loading never touches the network."""
    from huggingface_hub import snapshot_download
    try:
        return snapshot_download(repo, allow_patterns=patterns, local_files_only=True)
    except Exception:                                  # noqa: BLE001 - not cached yet
        log.info("downloading %s (once)...", repo)
        return snapshot_download(repo, allow_patterns=patterns)


def parakeet_prefetch(backend: str | None = None) -> str:
    """Downloads Parakeet's weights for `backend` (default: the one that runs here); their folder."""
    backend = backend or parakeet_backend()
    if backend == "mlx":
        return _local_snapshot(PARAKEET_MLX)
    return _local_snapshot(PARAKEET_ONNX_REPO, PARAKEET_ONNX_FILES)


def parakeet_downloaded(backend: str | None = None) -> bool:
    from huggingface_hub import snapshot_download
    backend = backend or parakeet_backend()
    try:
        if backend == "mlx":
            snapshot_download(PARAKEET_MLX, local_files_only=True)
        else:
            snapshot_download(PARAKEET_ONNX_REPO, allow_patterns=PARAKEET_ONNX_FILES, local_files_only=True)
        return True
    except Exception:                                  # noqa: BLE001
        return False


class ParakeetSTT:
    """Parakeet TDT 0.6B v3. `backend`: "mlx" or "onnx" (default: `parakeet_backend()`); `model`: a
    Hugging Face repo or a local folder with that backend's files (default: v3 for the backend)."""

    def __init__(self, model: str | None = None, languages: Iterable[str] = ("fr", "en"), backend: str | None = None,
                 min_confidence: float = PARAKEET_MIN_CONFIDENCE):
        import os
        self.backend = backend or parakeet_backend()
        if self.backend is None:
            raise RuntimeError("Parakeet needs parakeet-mlx (Apple Silicon) or onnx-asr: pip install -e \".[voice]\"")
        self.languages = tuple(languages)
        self.min_confidence = min_confidence
        local = model if model and os.path.isdir(os.path.expanduser(model)) else None
        t = time.monotonic()
        if self.backend == "mlx":
            from parakeet_mlx import from_pretrained
            from parakeet_mlx.audio import get_logmel
            import mlx.core as mx
            self.model_name = model or PARAKEET_MLX
            self._model = from_pretrained(local or _local_snapshot(self.model_name))
            self._mx, self._logmel = mx, get_logmel
        else:
            import onnx_asr
            self.model_name = model or PARAKEET_ONNX_REPO
            folder = local or _local_snapshot(self.model_name, PARAKEET_ONNX_FILES)
            self._model = onnx_asr.load_model(PARAKEET_ONNX, folder, quantization="int8")
        self._run(np.zeros(SAMPLE_RATE // 2, np.float32))         # compile and warm up now
        log.info("parakeet %s (%s) loaded in %.1f s", self.model_name, self.backend, time.monotonic() - t)

    def _run(self, audio: np.ndarray) -> tuple[str, float | None]:
        if self.backend == "mlx":
            mel = self._logmel(self._mx.array(audio), self._model.preprocessor_config)
            r = self._model.generate(mel)[0]
            tokens = [tok for sentence in r.sentences for tok in sentence.tokens]
            conf = float(np.mean([tok.confidence for tok in tokens])) if tokens else None
            return (r.text or "").strip(), conf
        out = self._model.recognize(audio, sample_rate=SAMPLE_RATE)
        return (out if isinstance(out, str) else getattr(out, "text", "") or "").strip(), None

    def transcribe(self, pcm: np.ndarray, language: str | None = None) -> Transcript:
        from .text import detect_language
        t = time.monotonic()
        text, conf = self._run(np.asarray(pcm, dtype=np.float32) / 32768.0)
        rejected = None
        if conf is not None and text and conf < self.min_confidence:
            rejected, text = f"low confidence ({conf:.2f})", ""
        if language:
            return Transcript(text, language, True, time.monotonic() - t, 1.0, rejected=rejected, confidence=conf)
        lang, share = detect_language(text, self.languages or ("fr", "en"))
        # as Whisper's detection: a short or mixed utterance is not a reason to switch languages
        confident = lang is not None and share >= 0.7 and len(text.split()) >= 2
        return Transcript(text, lang, confident, time.monotonic() - t, round(share, 3) if lang else None,
                          rejected=rejected, confidence=conf)


# ---------------------------------------------------------------- the vocabulary

class LexiconSTT:
    """Another STT, its transcripts corrected with the owner's vocabulary (lexicon.py)."""

    def __init__(self, stt: STT, terms: Iterable[str]):
        from .lexicon import Lexicon
        self.inner = stt
        self.lexicon = Lexicon(list(terms))
        self.model_name = getattr(stt, "model_name", None)

    def transcribe(self, pcm: np.ndarray, language: str | None = None) -> Transcript:
        tr = self.inner.transcribe(pcm, language)
        if tr.text and self.lexicon:
            fixed = self.lexicon.correct(tr.text)
            if fixed != tr.text:
                log.debug("vocabulary: %r -> %r", tr.text, fixed)
                tr.text = fixed
        return tr


def with_vocabulary(stt: STT, terms: Iterable[str]) -> STT:
    """`stt` biased towards `terms` where it can be (Whisper's hot words or initial prompt) and
    corrected after (all engines). The model is not reloaded."""
    terms = [t for t in terms if t]
    inner = stt.inner if isinstance(stt, LexiconSTT) else stt
    if isinstance(inner, WhisperSTT):
        inner.hotwords = ", ".join(terms) or None
    elif isinstance(inner, MlxWhisperSTT):
        inner.initial_prompt = (", ".join(terms) + ".") if terms else None
    return LexiconSTT(inner, terms) if terms else inner


STT_BACKENDS = ("auto", "parakeet", "mlx", "faster-whisper")


def make_stt(backend: str = "auto", model: str | None = None, languages: Iterable[str] = ("fr", "en")) -> STT:
    """'parakeet', 'mlx' (Whisper on Apple Silicon), 'faster-whisper', or 'auto' (Parakeet where it
    runs, else MLX Whisper on Apple Silicon, else faster-whisper). `model` None or "auto" picks
    the backend's default (Parakeet v3; turbo on MLX, small on faster-whisper)."""
    if backend not in STT_BACKENDS:
        raise ValueError(f"unknown STT backend {backend!r}, expected one of {STT_BACKENDS}")
    model = None if model in (None, "", "auto") else model
    if backend == "parakeet" or (backend == "auto" and parakeet_backend() is not None):
        try:
            # a Whisper model name chosen for the Whisper engines is not Parakeet's
            return ParakeetSTT(model if model and ("parakeet" in model or "/" in model) else None, languages)
        except Exception as e:
            if backend == "parakeet":
                raise
            log.warning("Parakeet failed (%s), falling back to Whisper", e)
        model = None if model and "parakeet" in model else model
    if backend == "mlx" or (backend == "auto" and mlx_available()):
        try:
            return MlxWhisperSTT(model or MLX_DEFAULT, languages)
        except Exception as e:
            if backend == "mlx":
                raise
            log.warning("MLX Whisper failed (%s), falling back to faster-whisper on the CPU", e)
    return WhisperSTT(model or "small", languages)


def main(argv: list[str] | None = None) -> int:
    """`python -m marvin_host.voice.stt --check | --prefetch`: Parakeet's weights (./marvin up)."""
    import argparse
    import sys
    ap = argparse.ArgumentParser(prog="python -m marvin_host.voice.stt", description="Speech recognition's model.")
    ap.add_argument("--check", action="store_true", help="say whether Parakeet can run here and is downloaded")
    ap.add_argument("--prefetch", action="store_true", help="download Parakeet's weights now (once)")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    backend = parakeet_backend()
    if backend is None:
        print("Parakeet: not installed (Apple Silicon: pip install parakeet-mlx; elsewhere: pip install onnx-asr); "
              "Whisper is used")
        return 1
    repo = PARAKEET_MLX if backend == "mlx" else PARAKEET_ONNX_REPO
    if args.prefetch and not parakeet_downloaded(backend):
        print(f"Downloading Parakeet {repo} (once, {'2.5 GB' if backend == 'mlx' else '670 MB'}) ...", flush=True)
        parakeet_prefetch(backend)
    here = parakeet_downloaded(backend)
    print(f"Parakeet: {backend}, {repo}, weights {'downloaded' if here else 'not downloaded yet'}")
    return 0 if here else 3


if __name__ == "__main__":
    raise SystemExit(main())
