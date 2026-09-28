"""Marvin's voice: wake word, speech recognition, a local language model and speech synthesis.

Everything runs on the computer (faster-whisper, Ollama, macOS `say` or Piper): no cloud API.
Install with `pip install -e ".[voice]"`, see docs/voice.md.

    from marvin_host.voice import VoiceAssistant, VoiceConfig, MicSource, SpeakerSink
    VoiceAssistant(MicSource(), SpeakerSink(), brain=brain).start()

Modules: io (mic, speakers, WAV), vad (voice activity, utterances), wake (the wake word),
stt (Whisper on GPU or CPU), echo (never answering itself), filters (what Whisper invents),
llm (Ollama), tools (what the model can call: the weather), net (HTTPS),
persona (system prompt and context), text (sentence splitting),
tts (say, Piper, espeak-ng), assistant (the orchestrator), proactive (reminders),
control (on and off at run time, for the app), cli.

Importing this package is cheap: heavy dependencies load when a component is created.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

from .assistant import Status, VoiceAssistant, VoiceConfig
from .cli import add_cli, add_run_arguments, attach, make_controller
from .control import VoiceController, VoiceOff, VoiceUnavailable
from .echo import EchoFilter, EchoGate
from .io import ArraySource, MicSource, NullSink, SpeakerSink, WavSink, WavSource
from .llm import LLM, FakeLLM, LLMUnavailable, OllamaLLM
from .proactive import ProactiveConfig, ProactiveSpeaker
from .stt import STT, FakeSTT, MlxWhisperSTT, Transcript, WhisperSTT, make_stt
from .tts import TTS, EspeakTTS, FakeTTS, MacSayTTS, PiperTTS, make_tts
from .vad import EnergyVad, Segmenter, SegmenterConfig, WebRtcVad, make_vad
from .wake import TranscriptWakeWord, WakeMatch, WakeWordDetector, match_wake_word

__all__ = [
    "VoiceAssistant", "VoiceConfig", "Status",
    "add_cli", "add_run_arguments", "attach", "make_controller",
    "VoiceController", "VoiceOff", "VoiceUnavailable",
    "MicSource", "SpeakerSink", "WavSource", "ArraySource", "NullSink", "WavSink",
    "LLM", "OllamaLLM", "FakeLLM", "LLMUnavailable",
    "ProactiveSpeaker", "ProactiveConfig",
    "STT", "WhisperSTT", "MlxWhisperSTT", "make_stt", "FakeSTT", "Transcript",
    "EchoGate", "EchoFilter",
    "TTS", "MacSayTTS", "PiperTTS", "EspeakTTS", "FakeTTS", "make_tts",
    "Segmenter", "SegmenterConfig", "WebRtcVad", "EnergyVad", "make_vad",
    "WakeWordDetector", "TranscriptWakeWord", "WakeMatch", "match_wake_word",
]
