"""Command-line glue for the voice pipeline, kept out of cli.py so it stays optional.

    marvin-host talk [--lang fr] [--no-wake] [--wav FILE] ...    talk to Marvin with the computer's mic
    marvin-host run --voice [...]                                 the same, next to the robot's brain

cli.py wires it in with `add_cli(sub)`, `add_run_arguments(run)` and `make_controller(brain, args)`
(the voice that the app turns on and off; `attach(brain, args)` starts a bare assistant instead).
Nothing heavy is imported until a command actually runs.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path
from typing import Callable

log = logging.getLogger("marvin.voice")

TTS_CHOICES = ("auto", "say", "piper", "espeak")
STT_CHOICES = ("auto", "mlx", "faster-whisper")
SETTINGS_FILE = "voice.json"

# settings file key -> (VoiceConfig attribute, type); plus input_device / output_device
SETTINGS = {
    "stt": ("stt", str), "stt_model": ("stt_model", str), "llm_model": ("llm_model", str),
    "ollama_host": ("ollama_host", str), "tts": ("tts", str), "tts_voice": ("tts_voice", str),
    "language": ("language", str), "default_language": ("default_language", str),
    "wake": ("wake", bool), "duplex": ("duplex", bool), "echo_tail_s": ("echo_tail_s", float),
    "follow_up_s": ("follow_up_s", float), "listen_window_s": ("listen_window_s", float),
    "speculative_stt": ("speculative_stt", bool), "end_silence_ms": (None, float),
    # one thought, one question: a question that goes on is one question (voice/engine.py)
    "continue_grace_s": ("continue_grace_s", float), "end_silence_long_ms": (None, float),
    # run --voice only: spoken break reminders and "welcome back" (voice/proactive.py)
    "reminders": (None, bool), "welcome_back": (None, bool),
    # tools the model can call (voice/tools): all of them, the online ones, the weather's home
    "tools": ("tools", bool), "internet": ("internet", bool), "home_place": ("home_place", str),
    # the listening chime, and where the Java host's voice hears and speaks (computer, robot, auto)
    "chime": ("chime", bool), "audio_route": (None, str),
}
DEVICE_KEYS = ("input_device", "output_device")


def settings_path() -> Path:
    """~/.config/marvin/voice.json (or $MARVIN_CONFIG_DIR/voice.json), next to calibration.json."""
    from ..calibration import config_dir
    return config_dir() / SETTINGS_FILE


def load_settings(path: Path | None = None) -> dict:
    """The owner's defaults, e.g. {"llm_model": "qwen3:8b", "stt": "mlx"}. Missing file: {}."""
    path = path or settings_path()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as e:
        log.warning("ignoring %s: %s", path, e)
        return {}
    unknown = set(data) - set(SETTINGS) - set(DEVICE_KEYS)
    if unknown:
        log.warning("%s: unknown keys %s (known: %s)", path, sorted(unknown), sorted([*SETTINGS, *DEVICE_KEYS]))
    return data


def save_settings(values: dict, path: Path | None = None) -> Path:
    """Merges `values` into the settings file (other keys are kept)."""
    path = path or settings_path()
    data = {**load_settings(path), **values}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def _add_common(p: argparse.ArgumentParser, prefix: str = "") -> None:
    """Options shared by `talk` and `run --voice`. `prefix` is "" or "voice-". Defaults are None so
    that the settings file fills what the command line does not say."""
    from .assistant import VoiceConfig
    d = VoiceConfig()
    p.add_argument(f"--{prefix}stt", choices=STT_CHOICES,
                   help="speech recognition: mlx (Apple Silicon GPU), faster-whisper (CPU); "
                        "auto picks mlx when installed")
    p.add_argument(f"--{prefix}stt-model",
                   help="Whisper model: tiny, base, small, medium, large-v3, turbo "
                        "(default: turbo on MLX, small on CPU)")
    p.add_argument(f"--{prefix}llm-model", help=f"Ollama model (default {d.llm_model})")
    p.add_argument(f"--{prefix}ollama", metavar="URL", help=f"Ollama server (default {d.ollama_host})")
    p.add_argument(f"--{prefix}tts", choices=TTS_CHOICES,
                   help="speech synthesis: say (macOS), piper, espeak; auto picks the best available")
    p.add_argument(f"--{prefix}tts-voice", metavar="NAME", help="voice name for the TTS backend")
    p.add_argument(f"--{prefix}lang", metavar="CODE",
                   help="force a language (fr, en...); default: answer in the language spoken to")
    p.add_argument(f"--{prefix}no-wake", action="store_const", const=True,
                   help="answer every utterance, without waiting for 'Marvin' (use a headset)")
    p.add_argument(f"--{prefix}duplex", action="store_const", const=True,
                   help="keep listening while speaking (headset, or a speaker with echo cancellation); "
                        "default: the microphone is muted while Marvin speaks")
    p.add_argument(f"--{prefix}end-silence-ms", type=float, metavar="MS",
                   help=f"silence that ends a question (default {d.segmenter.end_silence_s * 1000:.0f})")
    p.add_argument(f"--{prefix}echo-tail", type=float, metavar="S",
                   help=f"half duplex: seconds still muted after speaking (default {d.echo_tail_s})")
    p.add_argument(f"--{prefix}input-device", metavar="ID", help="microphone (see talk --list-devices)")
    p.add_argument(f"--{prefix}output-device", metavar="ID", help="speaker (see talk --list-devices)")


# command-line option -> settings key
_FLAGS = {"stt": "stt", "stt-model": "stt_model", "llm-model": "llm_model", "ollama": "ollama_host",
          "tts": "tts", "tts-voice": "tts_voice", "lang": "language", "duplex": "duplex",
          "end-silence-ms": "end_silence_ms", "echo-tail": "echo_tail_s",
          "input-device": "input_device", "output-device": "output_device"}


def _get(args, name: str, prefix: str):
    return getattr(args, (prefix + name).replace("-", "_"), None)


def _device(v):
    return int(v) if v is not None and str(v).isdigit() else v


def _flags(args, prefix: str = "") -> dict:
    """The settings given on the command line."""
    out = {key: _get(args, flag, prefix) for flag, key in _FLAGS.items()}
    if _get(args, "no-wake", prefix):
        out["wake"] = False
    return {k: v for k, v in out.items() if v is not None}


def _settings(args, prefix: str = "") -> dict:
    """Settings file, overridden by the command line."""
    return {**load_settings(), **_flags(args, prefix)}


def _config(args, prefix: str = "", settings: dict | None = None):
    from .assistant import VoiceConfig
    c = VoiceConfig()
    values = _settings(args, prefix) if settings is None else settings
    for key, v in values.items():
        attr, kind = SETTINGS.get(key, (None, None))
        if key == "end_silence_ms":
            c.segmenter.end_silence_s = float(v) / 1000
        elif key == "end_silence_long_ms":
            c.segmenter.end_silence_long_s = float(v) / 1000
        elif attr is not None and v is not None:
            setattr(c, attr, kind(v))
    if c.language:
        c.default_language = c.language
    return c


def _printer(prefix: str = ""):
    def status(s):
        if s in ("listening", "thinking"):
            print(f"{prefix}[{s}]", flush=True)

    return dict(on_status=status,
                on_transcript=lambda t: print(f"{prefix}you: {t}", flush=True),
                on_reply=lambda t: print(f"{prefix}marvin: {t}", flush=True))


def add_cli(subparsers) -> Callable[[argparse.Namespace], None]:
    """Adds the `talk` subcommand. Returns its handler: call `handler(args)` when args.cmd == "talk"."""
    p = subparsers.add_parser("talk", help="talk to Marvin with the computer's microphone and speakers")
    _add_common(p)
    p.add_argument("--wav", metavar="FILE", help="read speech from a WAV file (in real time) instead of the mic")
    p.add_argument("--out", metavar="FILE", help="write what Marvin says to a WAV file instead of the speakers")
    p.add_argument("--list-devices", action="store_true", help="list the sound devices and exit")
    p.add_argument("--save-defaults", action="store_true",
                   help=f"remember the options given here as defaults (in ~/.config/marvin/{SETTINGS_FILE})")
    return talk


def talk(args) -> None:
    from . import io
    from .assistant import VoiceAssistant

    if getattr(args, "list_devices", False):
        print(io.list_devices())
        return
    if getattr(args, "save_defaults", False):
        print(f"saved {_flags(args)} to {save_settings(_flags(args))}")
    settings = _settings(args)
    config = _config(args, settings=settings)
    source = (io.WavSource(args.wav, realtime=True) if args.wav
              else io.MicSource(_device(settings.get("input_device"))))
    sink = io.WavSink(args.out) if args.out else io.SpeakerSink(_device(settings.get("output_device")))
    mode = "full duplex" if config.duplex else "half duplex: the microphone is muted while Marvin speaks"
    print(f"loading speech recognition and {config.llm_model} via Ollama ({mode})...", flush=True)
    va = VoiceAssistant(source, sink, config=config, **_printer())
    hint = "say 'Marvin, ...'" if config.wake else "just talk"
    print(f"ready: {hint} (Ctrl+C to quit)", flush=True)
    try:
        va.run()
    finally:
        va.close()


def add_run_arguments(parser: argparse.ArgumentParser) -> None:
    """Adds --voice and its options to the `run` subcommand."""
    g = parser.add_argument_group("voice")
    g.add_argument("--voice", action="store_true", help="talk to Marvin (computer mic and speakers)")
    _add_common(g, "voice-")
    g.add_argument("--voice-no-reminders", action="store_true", help="no break reminder after sitting long")
    g.add_argument("--voice-welcome", action="store_true", help="say 'welcome back' after a long absence")


def overrides(args, prefix: str = "voice_") -> dict:
    """The voice settings given on the `run` command line (they win over voice.json)."""
    out = _flags(args, prefix)
    if getattr(args, "voice_no_reminders", False):
        out["reminders"] = False
    if getattr(args, "voice_welcome", False):
        out["welcome_back"] = True
    return out


def make_controller(brain, args, **kw):
    """A `VoiceController` (voice/control.py) for `run`: the app turns the voice on and off, and
    changes its settings. It does not start by itself: call `.start()`."""
    from .control import VoiceController
    return VoiceController(brain, overrides=overrides(args), **kw)


def attach(brain, args):
    """Starts the voice assistant next to `brain` (context for the model, proactive reminders).
    Returns the running `VoiceAssistant` (call `.close()` when done), or None without --voice."""
    if not getattr(args, "voice", False):
        return None
    from . import io
    from .assistant import VoiceAssistant
    from .proactive import ProactiveConfig, ProactiveSpeaker

    settings = _settings(args, "voice_")
    config = _config(args, settings=settings)
    source = io.MicSource(_device(settings.get("input_device")))
    sink = io.SpeakerSink(_device(settings.get("output_device")))
    va = VoiceAssistant(source, sink, brain=brain, config=config, **_printer("  voice "))
    brain.add_listener(ProactiveSpeaker(va, ProactiveConfig(
        still_long=not getattr(args, "voice_no_reminders", False),
        welcome_back=getattr(args, "voice_welcome", False))))
    va.start()
    print("voice: say 'Marvin, ...'" if config.wake else "voice: listening", flush=True)
    return va

