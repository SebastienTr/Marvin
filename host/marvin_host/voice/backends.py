"""What the voice can run on this computer, and what is missing when it cannot start.

The voice sidecar asks this before it builds a voice (`audio_preflight`: the `voice` extra, a
microphone), and answers the settings panel with it (`stt_available`, `tts_available`, the voices).
Settings themselves are the Java host's (host-java `VoiceSettings`): nothing here has a default.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys

STT_CHOICES = ("auto", "parakeet", "mlx", "faster-whisper")
TTS_CHOICES = ("auto", "qwen", "say", "piper", "espeak")
STT_MODELS = ("tiny", "base", "small", "medium", "large-v3", "turbo")
# Piper voices offered for download (rhasspy/piper-voices); installed ones are listed too
PIPER_VOICES = ("fr_FR-siwis-medium", "fr_FR-tom-medium", "fr_FR-upmc-medium", "fr_FR-gilles-low",
                "en_GB-alan-medium", "en_GB-northern_english_male-medium", "en_US-lessac-medium",
                "en_US-ryan-high")


class VoiceUnavailable(RuntimeError):
    """Something the voice needs is missing. `fix` says what to do."""

    def __init__(self, message: str, fix: str = ""):
        super().__init__(message)
        self.fix = fix


def device_index(v):
    """A PortAudio device as voice.json names it: its number, or (part of) its name; None: the default."""
    if v in (None, ""):
        return None
    return int(v) if str(v).isdigit() else v


def say_voices() -> list[dict]:
    """macOS `say` voices, [{"name", "locale"}], French and English first; [] elsewhere."""
    if sys.platform != "darwin" or not shutil.which("say"):
        return []
    from .tts import parse_say_voices
    try:
        out = subprocess.run(["say", "-v", "?"], capture_output=True, text=True, timeout=5, check=False).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    voices = [{"name": n, "locale": loc} for n, loc in parse_say_voices(out)]
    return sorted(voices, key=lambda v: (v["locale"][:2] not in ("fr", "en"), v["locale"], v["name"]))


def piper_voices() -> list[dict]:
    """Piper voices: installed ones (in ~/.local/share/marvin/piper) and a few to download on first
    use, [{"name", "installed"}]."""
    from .tts import piper_dir
    try:
        installed = {p.name[:-5] for p in piper_dir().glob("*.onnx")}
    except OSError:
        installed = set()
    names = sorted(installed | set(PIPER_VOICES), key=lambda n: (n[:2] not in ("fr", "en"), n))
    return [{"name": n, "installed": n in installed} for n in names]


def tts_available() -> dict[str, bool]:
    """Which speech synthesis backends can run here."""
    from .qwen_tts import qwen_available
    return {"auto": True, "qwen": qwen_available() is not None, "say": sys.platform == "darwin" and bool(shutil.which("say")),
            "piper": importlib.util.find_spec("piper") is not None,
            "espeak": bool(shutil.which("espeak-ng") or shutil.which("espeak"))}


def stt_available() -> dict[str, bool]:
    """Which speech recognition backends can run here ("auto" when one of them can)."""
    from .stt import mlx_available, parakeet_backend
    out = {"parakeet": parakeet_backend() is not None, "mlx": mlx_available(),
           "faster-whisper": importlib.util.find_spec("faster_whisper") is not None}
    return {"auto": any(out.values()), **out}


def ears_available() -> list[dict]:
    """The ears' models (models.py and Parakeet): [{"name", "installed" (downloaded and runnable), "why"}]."""
    from . import models
    from .stt import parakeet_backend, parakeet_downloaded
    out = []
    onnx = models.onnx_available()
    for m in models.MODELS.values():
        here = onnx and m.present()
        why = "" if here else ("pip install onnxruntime (./marvin up does it)" if not onnx
                               else "not downloaded: ./marvin up (or python -m marvin_host.voice.models --prefetch)")
        out.append({"name": m.id, "installed": here, "why": why})
    backend = parakeet_backend()
    try:
        here = backend is not None and parakeet_downloaded(backend)
    except ImportError:
        here = False
    out.append({"name": "parakeet", "installed": here,
                "why": "" if here else ("Apple Silicon: pip install parakeet-mlx; elsewhere: pip install onnx-asr"
                                        if backend is None else "not downloaded: ./marvin up")})
    return out


def audio_preflight(input_device=None, microphone: bool = True) -> None:
    """Raises VoiceUnavailable (with a fix) if the voice's audio half cannot start: the `voice`
    extra (speech recognition, VAD) and, with `microphone`, the computer's microphone. The robot's
    microphone needs no sound card."""
    wanted = ("numpy", "sounddevice", "webrtcvad") if microphone else ("numpy", "webrtcvad")
    missing = [m for m in wanted if importlib.util.find_spec(m) is None]
    if not any(importlib.util.find_spec(m) is not None for m in ("faster_whisper", "mlx_whisper", "parakeet_mlx",
                                                                  "onnx_asr")):
        missing.append("faster-whisper")
    if missing:
        raise VoiceUnavailable(f"The voice extra is not installed (missing: {', '.join(missing)})",
                               'In the host folder: pip install -e ".[voice]" (./marvin up does it)')
    if not microphone:
        return
    try:
        import sounddevice as sd
        sd.query_devices(device_index(input_device), "input")
    except Exception as e:                     # noqa: BLE001 - PortAudio raises various things
        raise VoiceUnavailable(f"No microphone ({e})",
                               "Plug in a microphone, or pick one with input_device in voice.json.") from None
