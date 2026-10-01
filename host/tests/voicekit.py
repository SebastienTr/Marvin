"""Test kit for the voice engine: synthetic audio, scripted microphones and sinks, and an engine
whose answers are scripted (what Marvin's core streams to the sidecar, without the core).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import re
import threading
import time
from types import SimpleNamespace

import numpy as np

from marvin_host.audio import FRAME_SAMPLES, SAMPLE_RATE
from marvin_host.voice.engine import VoiceConfig, VoiceEngine
from marvin_host.voice.io import NullSink
from marvin_host.voice.stt import FakeSTT
from marvin_host.voice.tts import FakeTTS
from marvin_host.voice.vad import EnergyVad


def silence(s: float) -> np.ndarray:
    rng = np.random.default_rng(1)
    return (rng.normal(0, 30, int(s * SAMPLE_RATE))).astype(np.int16)        # quiet room noise


def voice(s: float, f0: float = 140.0) -> np.ndarray:
    """A buzzy harmonic tone with a syllable-like envelope: loud enough to be speech for the VAD."""
    t = np.arange(int(s * SAMPLE_RATE)) / SAMPLE_RATE
    x = sum(np.sin(2 * np.pi * f0 * k * t) / k for k in range(1, 8))
    env = 0.6 + 0.4 * np.abs(np.sin(2 * np.pi * 3 * t))
    return (x * env * 5000).astype(np.int16)


def sentences(text: str) -> list[str]:
    """A reply cut at its sentence ends (the core does it for real, with its first clause early)."""
    return [s for s in re.split(r"(?<=[.!?…])\s+", text.strip()) if s]


class Replies:
    """What the scripted core answers: a list (one reply per question, "" when it runs out) or a
    function of the question; `delay_s` before the first word, like a model thinking; `fail`: the
    answer fails with nothing said."""

    def __init__(self, replies=("D'accord.",), delay_s: float = 0.0, fail: bool = False):
        self.replies = replies if callable(replies) else list(replies)
        self.delay_s = delay_s
        self.fail = fail

    def __call__(self, question: str) -> str:
        if self.delay_s:
            time.sleep(self.delay_s)
        if callable(self.replies):
            return self.replies(question)
        return self.replies.pop(0) if self.replies else ""


class ScriptedVoice(VoiceEngine):
    """The engine answering with `Replies`: each question is recorded in `asked`, its answer spoken
    sentence by sentence. Reports "reply" events and `on_reply` as the core's answers do."""

    def __init__(self, *args, replies: Replies | None = None, **kw):
        self.replies = replies or Replies()
        self.asked: list[str] = []
        super().__init__(*args, **kw)

    def _answer(self, job) -> None:
        self.asked.append(job.text)
        lat = self.last_latency
        t = time.monotonic()
        answer: list[str] = []

        def produce(emit):
            if self.replies.fail:
                return
            text = self.replies(job.text)
            for s in sentences(text):
                if job.cancel.is_set():
                    return
                lat.setdefault("first_chunk", time.monotonic() - t)
                answer.append(s)
                emit(s)

        said = self._speak_all(job, produce, lat)
        lat["total"] = time.monotonic() - job.t_heard
        cancelled = job.cancel.is_set()
        if cancelled and job.cancel_reason == "merged" and not job.audible:
            return
        if answer and (said or cancelled):
            self._last_reply = said
            self._emit("reply", text=said + (" …" if cancelled else ""), language=job.language,
                       latency=dict(lat), interrupted=cancelled, proactive=False, error=None, hint="")
            if not cancelled and self.on_reply:
                self.on_reply(said)


class ScriptSource:
    """Plays a script of ("say", seconds), ("quiet", seconds), ("idle",), ("call", fn) and
    ("status", name) steps. "idle" waits until the engine has finished answering, like a person
    waiting for the reply."""

    def __init__(self, script):
        self.script = script
        self.assistant = None
        self._closed = False

    def frames(self):
        for step in self.script:
            if self._closed:
                return
            if step[0] == "idle":
                assert self.assistant.wait_idle(timeout=5)
                continue
            if step[0] == "call":
                assert self.assistant.wait_idle(timeout=0.3) or True
                step[1]()
                continue
            if step[0] == "status":
                end = time.monotonic() + 5
                while self.assistant.status != step[1]:
                    assert time.monotonic() < end, f"never reached {step[1]}"
                    time.sleep(0.005)
                continue
            audio = voice(step[1]) if step[0] == "say" else silence(step[1])
            for i in range(len(audio) // FRAME_SAMPLES):
                yield audio[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES]

    def close(self):
        self._closed = True


class BlockingSink(NullSink):
    """A speaker that never finishes on its own: it is only ever stopped."""

    def __init__(self):
        super().__init__()
        self._stop = threading.Event()

    def play(self, pcm, rate=SAMPLE_RATE):
        super().play(pcm, rate)
        self._stop.clear()

    def wait(self):
        self._stop.wait(5)

    def stop(self):
        super().stop()
        self._stop.set()

    @property
    def busy(self):
        return not self._stop.is_set()


class OnceBlockingSink(BlockingSink):
    """Blocks (a long answer being said) until it is stopped once; then plays at once."""

    def wait(self):
        if not self.stops:
            super().wait()


def make(script, stt_script, replies=("D'accord.",), sink=None, tts=None, **cfg):
    """A `ScriptedVoice` hearing `script`, its Whisper answering `stt_script` in turn."""
    config = VoiceConfig(**{"follow_up_s": 0.0, **cfg})
    src = ScriptSource(script)
    stt = FakeSTT(stt_script)
    replies = replies if isinstance(replies, Replies) else Replies(replies)
    tts = tts or FakeTTS()
    sink = sink or NullSink()
    log = {"status": [], "transcript": [], "reply": []}
    va = ScriptedVoice(src, sink, config, stt=stt, tts=tts, vad=EnergyVad(), replies=replies,
                       on_status=log["status"].append, on_transcript=log["transcript"].append,
                       on_reply=log["reply"].append)
    src.assistant = va
    return va, SimpleNamespace(stt=stt, tts=tts, sink=sink, log=log, asked=va.asked)
