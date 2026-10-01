"""Marvin's own voice: Qwen3-TTS cloning a reference recording, in French and in English.

The voice ("marvin", the one the owner chose: "Malicieux") is a short bilingual recording and its
exact transcript, in voices/marvin/ (see its README). Qwen3-TTS's Base model clones it from those
(in-context: the reference's speech codes and text lead every sentence), so one voice says both
languages; each clause is synthesised with its own language (the core tags them, see host-java
`SpeechLanguage`), which sets the pronunciation, never the voice. The name "Marvin" needs no
respelling: the reference says it right.

Two backends, the first available:

- `mlx`: mlx-audio on Apple Silicon (the GPU through MLX), with streaming: the first sound of a
  clause plays while the rest is still being made. Model: mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16
  (1.7B with MARVIN_QWEN_TTS_SIZE=1.7b).
- `torch`: the official `qwen-tts` package (PyTorch; MPS on a Mac, CUDA, else the CPU, where a
  sentence takes tens of seconds: `auto` never picks it there). Model: Qwen/Qwen3-TTS-12Hz-0.6B-Base.
  Its voice-clone prompt (the reference encoded once) is cached in
  ~/.local/share/marvin/voices/, per model and package version.

Weights are downloaded once from Hugging Face (`python -m marvin_host.voice.qwen_tts --prefetch`,
which ./marvin up runs), then everything is offline. The model stays loaded while the voice runs
(the sidecar keeps it across restarts of the voice).

    python -m marvin_host.voice.qwen_tts --check              # backend, model, weights downloaded?
    python -m marvin_host.voice.qwen_tts --prefetch           # download the weights
    python -m marvin_host.voice.qwen_tts --say "Bonjour, je suis Marvin." --out marvin.wav

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import importlib.util
import logging
import os
import platform
import sys
import threading
import time
from pathlib import Path
from typing import Iterator

import numpy as np

log = logging.getLogger("marvin.voice.tts")

VOICES = Path(__file__).resolve().parent / "voices"
DEFAULT_VOICE = "marvin"
SIZES = ("0.6b", "1.7b")
MLX_MODELS = {"0.6b": "mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16",
              "1.7b": "mlx-community/Qwen3-TTS-12Hz-1.7B-Base-bf16"}
TORCH_MODELS = {"0.6b": "Qwen/Qwen3-TTS-12Hz-0.6B-Base", "1.7b": "Qwen/Qwen3-TTS-12Hz-1.7B-Base"}
# the language names Qwen3-TTS takes (it lowercases them itself)
LANGUAGES = {"fr": "french", "en": "english", "de": "german", "es": "spanish", "it": "italian",
             "pt": "portuguese"}
STREAM_INTERVAL_S = 0.32            # mlx: audio comes in pieces of about this much (4 codec frames)


def size() -> str:
    s = os.environ.get("MARVIN_QWEN_TTS_SIZE", "0.6b").lower()
    return s if s in SIZES else "0.6b"


def _apple_silicon() -> bool:
    return sys.platform == "darwin" and platform.machine() == "arm64"


def qwen_available() -> str | None:
    """The backend that can run here ("mlx" or "torch"), or None."""
    if _apple_silicon() and importlib.util.find_spec("mlx_audio") is not None:
        return "mlx"
    if importlib.util.find_spec("qwen_tts") is not None and importlib.util.find_spec("torch") is not None:
        return "torch"
    return None


def fast_enough(backend: str | None) -> bool:
    """Whether `backend` speaks in about real time here: MLX, or PyTorch on a GPU. A CPU takes tens of
    seconds a sentence: only asked for by name, never by "auto"."""
    if backend == "mlx":
        return True
    if backend == "torch":
        try:
            import torch
            return bool(torch.cuda.is_available() or torch.backends.mps.is_available())
        except Exception:                               # noqa: BLE001
            return False
    return False


def model_repo(backend: str, model_size: str | None = None) -> str:
    return (MLX_MODELS if backend == "mlx" else TORCH_MODELS)[model_size or size()]


def reference(voice: str = DEFAULT_VOICE) -> tuple[Path, str]:
    """The voice's reference recording and its exact transcript."""
    folder = VOICES / (voice or DEFAULT_VOICE)
    wav, txt = folder / "reference.wav", folder / "reference.txt"
    if not wav.exists() or not txt.exists():
        raise RuntimeError(f"no Qwen3-TTS voice {voice!r} (expected {wav} and {txt})")
    return wav, " ".join(txt.read_text(encoding="utf-8").split())


def voices() -> list[str]:
    try:
        return sorted(p.name for p in VOICES.iterdir() if (p / "reference.wav").exists())
    except OSError:
        return []


def cache_dir() -> Path:
    return Path(os.environ.get("MARVIN_VOICES_DIR", Path.home() / ".local" / "share" / "marvin" / "voices"))


def weights_downloaded(backend: str, model_size: str | None = None) -> bool:
    """Whether the model's weights are already on this computer (no network asked)."""
    try:
        from huggingface_hub import snapshot_download
        snapshot_download(model_repo(backend, model_size), local_files_only=True)
        return True
    except Exception:                                   # noqa: BLE001 - not there, or no huggingface_hub
        return False


def prefetch(backend: str, model_size: str | None = None) -> Path:
    """Downloads the model's weights (once; Hugging Face shows its progress)."""
    from huggingface_hub import snapshot_download
    return Path(snapshot_download(model_repo(backend, model_size)))


def to_int16(audio) -> np.ndarray:
    a = np.asarray(audio, dtype=np.float32).reshape(-1)
    return np.clip(np.round(a * 32767.0), -32768, 32767).astype(np.int16)


class QwenTTS:
    """Marvin's voice (see the module docstring): `synthesize(text, language)` -> (int16, 24000),
    and `stream(text, language)`, which yields the clause in pieces as they are made (mlx; the torch
    backend yields it whole)."""

    name = "qwen"
    multilingual = True             # one voice, every language: each clause is read in its own
    respells = False                # the reference says "Marvin" right: no "Marvine"

    def __init__(self, voice: str | None = None, backend: str | None = None, model_size: str | None = None):
        self.voice = voice if voice in voices() else DEFAULT_VOICE
        if voice and voice != self.voice:
            log.info("Qwen3-TTS has no voice %r: using %s", voice, self.voice)
        self.backend = backend or qwen_available()
        if self.backend is None:
            raise RuntimeError("Qwen3-TTS is not installed: on a Mac with Apple Silicon, pip install mlx-audio "
                               "(./marvin up does it); elsewhere pip install qwen-tts")
        self.size = model_size or size()
        self.repo = model_repo(self.backend, self.size)
        self.ref_wav, self.ref_text = reference(self.voice)
        self._lock = threading.Lock()
        t = time.monotonic()
        if self.backend == "mlx":
            self._load_mlx()
        else:
            self._load_torch()
        log.info("Qwen3-TTS %s (%s, voice %s) loaded in %.1f s", self.repo, self.backend, self.voice,
                 time.monotonic() - t)

    # ---------------------------------------------------------------- mlx-audio

    def _load_mlx(self) -> None:
        from mlx_audio.tts.utils import load_model
        from mlx_audio.utils import load_audio
        self.model = load_model(self.repo)
        self.rate = int(self.model.sample_rate)
        self._ref = load_audio(str(self.ref_wav), sample_rate=self.rate)    # once: its codes are cached too

    def _mlx(self, text: str, language: str, stream: bool) -> Iterator[np.ndarray]:
        for r in self.model.generate(text=text, ref_audio=self._ref, ref_text=self.ref_text,
                                     lang_code=LANGUAGES.get(language, "auto"), stream=stream,
                                     streaming_interval=STREAM_INTERVAL_S, verbose=False):
            yield to_int16(r.audio)

    # ---------------------------------------------------------------- qwen-tts (PyTorch)

    def _load_torch(self) -> None:
        import torch
        from qwen_tts import Qwen3TTSModel
        if torch.cuda.is_available():
            device, dtype = "cuda:0", torch.bfloat16
        elif torch.backends.mps.is_available():
            device, dtype = "mps", torch.float16
        else:
            device, dtype = "cpu", torch.bfloat16
        self.model = Qwen3TTSModel.from_pretrained(self.repo, device_map=device, dtype=dtype)
        self.rate = 24000
        self._prompt = self._clone_prompt(torch)

    def _clone_prompt(self, torch):
        """The reference encoded once, cached per model and package version (it is tied to both)."""
        from importlib.metadata import version
        slug = self.repo.replace("/", "--")
        path = cache_dir() / f"{self.voice}-{slug}-qwen-tts-{version('qwen-tts')}-torch-{torch.__version__}.pt"
        if path.exists():
            try:
                return torch.load(path, weights_only=False)
            except Exception as e:                      # noqa: BLE001 - made again below
                log.warning("ignoring the cached voice %s: %s", path, e)
        t = time.monotonic()
        prompt = self.model.create_voice_clone_prompt(ref_audio=str(self.ref_wav), ref_text=self.ref_text)
        path.parent.mkdir(parents=True, exist_ok=True)
        torch.save(prompt, path)
        log.info("voice %s prepared in %.1f s, kept in %s", self.voice, time.monotonic() - t, path)
        return prompt

    def _torch(self, text: str, language: str) -> np.ndarray:
        wavs, rate = self.model.generate_voice_clone(text=text, language=LANGUAGES.get(language, "auto"),
                                                     voice_clone_prompt=self._prompt)
        self.rate = int(rate)
        return to_int16(wavs[0])

    # ---------------------------------------------------------------- TTS

    def stream(self, text: str, language: str = "fr") -> Iterator[tuple[np.ndarray, int]]:
        with self._lock:
            if self.backend == "mlx":
                for pcm in self._mlx(text, language, stream=True):
                    yield pcm, self.rate
            else:
                yield self._torch(text, language), self.rate

    def synthesize(self, text: str, language: str = "fr") -> tuple[np.ndarray, int]:
        pieces = [pcm for pcm, _ in self.stream(text, language)]
        return (np.concatenate(pieces) if pieces else np.zeros(0, np.int16)), self.rate


# ---------------------------------------------------------------- command line (./marvin up and doctor)

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m marvin_host.voice.qwen_tts", description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="say which backend and model, and whether the weights are here")
    ap.add_argument("--prefetch", action="store_true", help="download the model's weights now (once)")
    ap.add_argument("--say", metavar="TEXT", help="synthesise TEXT (a smoke test)")
    ap.add_argument("--language", default="fr", help="with --say: fr or en (default fr)")
    ap.add_argument("--out", metavar="FILE.wav", help="with --say: where to write it (default: play nothing)")
    ap.add_argument("--backend", choices=("mlx", "torch"), help="force a backend")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    backend = args.backend or qwen_available()
    if backend is None:
        print("Qwen3-TTS: not installed (Apple Silicon: pip install mlx-audio; elsewhere: pip install qwen-tts)")
        return 1
    repo = model_repo(backend)
    if args.check:
        here = weights_downloaded(backend)
        print(f"Qwen3-TTS: {backend}, {repo}, weights {'downloaded' if here else 'not downloaded yet'}"
              f"{'' if fast_enough(backend) else ' (no GPU here: too slow to be picked automatically)'}")
        return 0 if here else 3
    if args.prefetch:
        print(f"Downloading Marvin's voice model {repo} (once, about 1-2 GB) ...", flush=True)
        path = prefetch(backend)
        print(f"Qwen3-TTS weights ready in {path}")
    if args.say:
        tts = QwenTTS(backend=backend)
        t = time.monotonic()
        first = None
        pieces = []
        for pcm, rate in tts.stream(args.say, args.language):
            first = first if first is not None else time.monotonic() - t
            pieces.append(pcm)
        pcm = np.concatenate(pieces) if pieces else np.zeros(0, np.int16)
        print(f"{len(pcm) / tts.rate:.2f} s of speech in {time.monotonic() - t:.2f} s "
              f"(first sound after {first or 0:.2f} s)")
        if args.out:
            from .io import write_wav
            write_wav(args.out, pcm, tts.rate)
            print(f"written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
