"""Command-line glue for the voice pipeline, kept out of cli.py so it stays optional.

    marvin-host talk [--lang fr] [--no-wake] [--wav FILE] ...    talk to Marvin with the computer's mic
    marvin-host run --voice [...]                                 the same, next to the robot's brain

cli.py wires it in with `add_cli(sub)`, `add_run_arguments(run)` and `attach(brain, args)`.
Nothing heavy is imported until a command actually runs.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import logging
from typing import Callable

log = logging.getLogger("marvin.voice")

TTS_CHOICES = ("auto", "say", "piper", "espeak")


def _add_common(p: argparse.ArgumentParser, prefix: str = "") -> None:
    """Options shared by `talk` and `run --voice`. `prefix` is "" or "voice-"."""
    from .assistant import VoiceConfig
    d = VoiceConfig()
    p.add_argument(f"--{prefix}stt-model", default=d.stt_model,
                   help=f"Whisper model: tiny, base, small, medium, large-v3, turbo (default {d.stt_model})")
    p.add_argument(f"--{prefix}llm-model", default=d.llm_model, help=f"Ollama model (default {d.llm_model})")
    p.add_argument(f"--{prefix}ollama", default=d.ollama_host, metavar="URL", help="Ollama server")
    p.add_argument(f"--{prefix}tts", choices=TTS_CHOICES, default=d.tts,
                   help="speech synthesis: say (macOS), piper, espeak; auto picks the best available")
    p.add_argument(f"--{prefix}tts-voice", metavar="NAME", help="voice name for the TTS backend")
    p.add_argument(f"--{prefix}lang", metavar="CODE",
                   help="force a language (fr, en...); default: answer in the language spoken to")
    p.add_argument(f"--{prefix}no-wake", action="store_true",
                   help="answer every utterance, without waiting for 'Marvin'")
    p.add_argument(f"--{prefix}input-device", metavar="ID", help="microphone (see talk --list-devices)")
    p.add_argument(f"--{prefix}output-device", metavar="ID", help="speaker (see talk --list-devices)")


def _get(args, name: str, prefix: str):
    return getattr(args, (prefix + name).replace("-", "_"), None)


def _device(v):
    return int(v) if v is not None and str(v).isdigit() else v


def _config(args, prefix: str = ""):
    from .assistant import VoiceConfig
    c = VoiceConfig()
    for name, attr in (("stt-model", "stt_model"), ("llm-model", "llm_model"), ("ollama", "ollama_host"),
                       ("tts", "tts"), ("tts-voice", "tts_voice"), ("lang", "language")):
        v = _get(args, name, prefix)
        if v is not None:
            setattr(c, attr, v)
    if c.language:
        c.default_language = c.language
    c.wake = not _get(args, "no-wake", prefix)
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
    return talk


def talk(args) -> None:
    from . import io
    from .assistant import VoiceAssistant

    if getattr(args, "list_devices", False):
        print(io.list_devices())
        return
    config = _config(args)
    source = io.WavSource(args.wav, realtime=True) if args.wav else io.MicSource(_device(args.input_device))
    sink = io.WavSink(args.out) if args.out else io.SpeakerSink(_device(args.output_device))
    print(f"loading Whisper {config.stt_model}, {config.llm_model} via Ollama...", flush=True)
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


def attach(brain, args):
    """Starts the voice assistant next to `brain` (context for the model, proactive reminders).
    Returns the running `VoiceAssistant` (call `.close()` when done), or None without --voice."""
    if not getattr(args, "voice", False):
        return None
    from . import io
    from .assistant import VoiceAssistant
    from .proactive import ProactiveConfig, ProactiveSpeaker

    config = _config(args, "voice_")
    source = io.MicSource(_device(_get(args, "input-device", "voice_")))
    sink = io.SpeakerSink(_device(_get(args, "output-device", "voice_")))
    va = VoiceAssistant(source, sink, brain=brain, config=config, **_printer("  voice "))
    brain.add_listener(ProactiveSpeaker(va, ProactiveConfig(
        still_long=not getattr(args, "voice_no_reminders", False),
        welcome_back=getattr(args, "voice_welcome", False))))
    va.start()
    print("voice: say 'Marvin, ...'" if config.wake else "voice: listening", flush=True)
    return va

