"""Marvin's ears and mouth: audio in and out, voice activity, the wake word, speech recognition and
speech synthesis. The voice sidecar (marvin_host.sidecar.voice) runs them for the Java host, which
owns the conversation (what to answer, the model, tools, memory, the settings).

Everything runs on the computer (Whisper, Qwen3-TTS, macOS `say` or Piper): no cloud API.
Install with `pip install -e ".[sidecar,voice]"` (./marvin up does it), see docs/voice.md.

Modules: engine (the audio machine: listening windows, barge-in, speaking), device (one route's
microphone, speaker and echo profile), io (mic, speakers, WAV), vad (voice activity, utterances),
wake (the wake word), stt (Whisper on GPU or CPU), echo (never answering itself), filters (what
Whisper invents), tts (Qwen3-TTS, say, Piper, espeak-ng), text (language guess), backends (what can
run here), net (HTTPS for downloads).

Importing this package is cheap: heavy dependencies load when a component is created.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

from .backends import VoiceUnavailable
from .echo import EchoFilter, EchoGate
from .engine import Status, VoiceConfig, VoiceEngine
from .io import ArraySource, MicSource, NullSink, SpeakerSink, WavSink, WavSource
from .stt import STT, FakeSTT, MlxWhisperSTT, Transcript, WhisperSTT, make_stt
from .tts import TTS, EspeakTTS, FakeTTS, MacSayTTS, PiperTTS, make_tts
from .vad import EnergyVad, Segmenter, SegmenterConfig, WebRtcVad, make_vad
from .wake import TranscriptWakeWord, WakeMatch, WakeWordDetector, match_wake_word

__all__ = [
    "VoiceEngine", "VoiceConfig", "Status", "VoiceUnavailable",
    "MicSource", "SpeakerSink", "WavSource", "ArraySource", "NullSink", "WavSink",
    "STT", "WhisperSTT", "MlxWhisperSTT", "make_stt", "FakeSTT", "Transcript",
    "EchoGate", "EchoFilter",
    "TTS", "MacSayTTS", "PiperTTS", "EspeakTTS", "FakeTTS", "make_tts",
    "Segmenter", "SegmenterConfig", "WebRtcVad", "EnergyVad", "make_vad",
    "WakeWordDetector", "TranscriptWakeWord", "WakeMatch", "match_wake_word",
]
