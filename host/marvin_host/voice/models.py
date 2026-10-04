"""The small model files of the ears: where they come from, where they are kept, and getting them.

| Id | What | File | Licence |
|---|---|---|---|
| `silero-vad` | Silero VAD v6 (speech or not, 32 ms at a time) | 2.3 MB ONNX | MIT |
| `smart-turn` | Smart Turn v3.2 (has the speaker finished their turn?) | 8.7 MB ONNX (int8) | BSD-2-Clause |
| `speaker` | WeSpeaker ECAPA-TDNN 512 trained on VoxCeleb2 (whose voice is it?) | 25 MB ONNX | Apache-2.0 |

They are downloaded once, from a pinned revision and checked against their SHA-256, into
`~/.local/share/marvin/models/` (`$MARVIN_MODELS_DIR` to put them elsewhere), by `./marvin up`
or `python -m marvin_host.voice.models --prefetch`. Nothing here downloads anything at run
time: a missing file means the ears fall back to what needs no model (the WebRTC VAD, the
silence timers, no speaker recognition) and say so in the log. Speech recognition's own models
(Parakeet, Whisper) are Hugging Face's cache's (stt.py).

    python -m marvin_host.voice.models --check       # one line per model; exit 3 if one is missing
    python -m marvin_host.voice.models --prefetch    # download what is missing (with progress)

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import logging
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("marvin.voice.models")


@dataclass(frozen=True)
class ModelFile:
    id: str
    label: str
    url: str
    filename: str
    sha256: str
    size: int                       # bytes, for the progress line
    licence: str
    source: str                     # where it comes from, for people

    @property
    def path(self) -> Path:
        return models_dir() / self.filename

    def present(self) -> bool:
        p = self.path
        return p.is_file() and p.stat().st_size == self.size


MODELS: dict[str, ModelFile] = {m.id: m for m in (
    ModelFile("silero-vad", "Silero VAD v6.2",
              "https://raw.githubusercontent.com/snakers4/silero-vad/v6.2.1/src/silero_vad/data/silero_vad.onnx",
              "silero_vad_v6.2.onnx", "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3",
              2327524, "MIT", "github.com/snakers4/silero-vad (tag v6.2.1)"),
    ModelFile("smart-turn", "Smart Turn v3.2 (CPU, int8)",
              "https://huggingface.co/pipecat-ai/smart-turn-v3/resolve/f766f81d3cfdf7737ac64aad813d91bbfd56bf93/"
              "smart-turn-v3.2-cpu.onnx",
              "smart-turn-v3.2-cpu.onnx", "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f",
              8679182, "BSD-2-Clause", "huggingface.co/pipecat-ai/smart-turn-v3"),
    ModelFile("speaker", "WeSpeaker ECAPA-TDNN 512 (VoxCeleb2)",
              "https://huggingface.co/Wespeaker/wespeaker-voxceleb-ecapa-tdnn512/resolve/"
              "89426e111abc9ffcc40279baf808e087693c6e9b/voxceleb_ECAPA512.onnx",
              "wespeaker_ecapa512.onnx", "f901ae3571912bad4e11e157744cb8b7d77a0b46923b83c410bdbb6c77d69736",
              24861931, "Apache-2.0", "huggingface.co/Wespeaker/wespeaker-voxceleb-ecapa-tdnn512"),
)}


def models_dir() -> Path:
    d = os.environ.get("MARVIN_MODELS_DIR")
    if d:
        return Path(d).expanduser()
    base = os.environ.get("XDG_DATA_HOME") or os.path.join(os.path.expanduser("~"), ".local", "share")
    return Path(base) / "marvin" / "models"


def onnx_available() -> bool:
    return importlib.util.find_spec("onnxruntime") is not None


def path_of(model_id: str) -> Path | None:
    """The model's file when it is here (and onnxruntime can run it), else None."""
    m = MODELS[model_id]
    return m.path if onnx_available() and m.present() else None


def onnx_session(path: Path, threads: int = 1):
    """An onnxruntime session on the CPU: one thread, sequential (these models are small; the
    GPU is for speech recognition and synthesis)."""
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.inter_op_num_threads = 1
    so.intra_op_num_threads = threads
    so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.log_severity_level = 3
    return ort.InferenceSession(str(path), sess_options=so, providers=["CPUExecutionProvider"])


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def download(m: ModelFile, out=sys.stderr, opener=None) -> Path:
    """Downloads `m` into models_dir() (a .part file, then renamed once its SHA-256 matches).
    `opener(url)` returns a readable response (tests); default: verified HTTPS."""
    if m.present():
        return m.path
    import urllib.request

    from .net import https_context
    m.path.parent.mkdir(parents=True, exist_ok=True)
    part = m.path.with_suffix(m.path.suffix + ".part")
    if opener is None:
        ctx = https_context()

        def opener(url):
            return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "marvin-host"}),
                                          timeout=60, context=ctx)
    t, done, shown = time.monotonic(), 0, -1
    with opener(m.url) as r, open(part, "wb") as f:
        while True:
            block = r.read(1 << 16)
            if not block:
                break
            f.write(block)
            done += len(block)
            pct = min(100, done * 100 // max(1, m.size))
            if out is not None and pct // 10 != shown:
                shown = pct // 10
                print(f"  {m.label}: {done / 1e6:.1f} / {m.size / 1e6:.1f} MB", file=out, flush=True)
    digest = _sha256(part)
    if digest != m.sha256:
        part.unlink(missing_ok=True)
        raise OSError(f"{m.label}: checksum mismatch ({digest[:12]}..., expected {m.sha256[:12]}...)")
    os.replace(part, m.path)
    log.info("%s downloaded in %.1f s", m.label, time.monotonic() - t)
    return m.path


def check_lines() -> tuple[list[str], bool]:
    """One line per model, and whether all of them are here."""
    lines, ok = [], True
    for m in MODELS.values():
        here = m.present()
        ok &= here
        lines.append(f"{m.label}: {'ready' if here else 'not downloaded'} ({m.licence})")
    if not onnx_available():
        lines.append("onnxruntime is not installed: these models cannot run (pip install -e \".[voice]\")")
        ok = False
    return lines, ok


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m marvin_host.voice.models",
                                 description="The ears' small models (VAD, end of turn, speaker).")
    ap.add_argument("--check", action="store_true", help="say which models are here (exit 3 if one is missing)")
    ap.add_argument("--prefetch", action="store_true", help="download the missing ones (once)")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    if args.prefetch:
        failed = 0
        for m in MODELS.values():
            if m.present():
                continue
            print(f"Downloading {m.label} ({m.size / 1e6:.1f} MB, {m.licence}) ...", file=sys.stderr, flush=True)
            try:
                download(m)
            except OSError as e:
                failed += 1
                print(f"  failed: {e}", file=sys.stderr)
        if failed:
            return 1
    lines, ok = check_lines()
    if args.check or not args.prefetch:
        print("; ".join(lines))
    return 0 if ok else 3


if __name__ == "__main__":
    sys.exit(main())
