"""The voice's state under pressure: Talk now and barge-in against an answer being synthesised or
played, the microphone reopening only once the speaker is silent, Talk now while thinking, the
device swapped under a running voice, earcons, and the voice chosen for each clause.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import threading
import time

import numpy as np
import pytest

from marvin_host.audio import SAMPLE_RATE
from marvin_host.voice.device import ROBOT_PROFILE, AudioDevice, earcon_pcm
from marvin_host.voice.echo import CLOSED, OPEN
from marvin_host.voice.engine import VoiceConfig
from marvin_host.voice.io import NullSink
from marvin_host.voice.stt import FakeSTT
from marvin_host.voice.tts import FakeTTS
from marvin_host.voice.vad import EnergyVad
from voicekit import (BlockingSink, OnceBlockingSink, Replies, ScriptedVoice, ScriptSource, device, make, silence, voice)


def _events(va):
    events = []
    va.add_listener(lambda kind, d: events.append((kind, d)))
    return events


def _wait(cond, timeout=5.0):
    end = time.monotonic() + timeout
    while not cond():
        assert time.monotonic() < end, "timed out"
        time.sleep(0.005)


class HeldTTS(FakeTTS):
    """A synthesis that waits for `release` before returning the sentences listed in `hold`."""

    def __init__(self, hold=()):
        super().__init__()
        self.hold = set(hold)
        self.entered = threading.Event()
        self.release = threading.Event()

    def synthesize(self, text, language="fr"):
        if text in self.hold:
            self.entered.set()
            self.release.wait(5)
        return super().synthesize(text, language)


class DrainingSink(NullSink):
    """A speaker that goes on sounding for `drain_s` after `stop()`, like a real device's buffer."""

    def __init__(self, drain_s=0.3):
        super().__init__()
        self.drain_s = drain_s
        self._quiet_at = 0.0
        self._playing = False

    def play(self, pcm, rate=SAMPLE_RATE):
        super().play(pcm, rate)
        self._playing = True

    def wait(self):
        while self._playing:
            time.sleep(0.01)

    def stop(self):
        super().stop()
        if self._playing:
            self._quiet_at = time.monotonic() + self.drain_s
        self._playing = False

    @property
    def busy(self):
        return self._playing or time.monotonic() < self._quiet_at


# ---------------------------------------------------------------- Talk now against the speaker thread

def test_talk_now_while_a_sentence_is_being_synthesised_plays_nothing_after():
    """The race of the audit: Talk now lands between the speaker thread's cancel check and its
    play. Nothing of the cut answer may play afterwards, nor turn the status back to speaking."""
    tts = HeldTTS(hold=["Il était une fois un phare."])
    box = {}
    va, t = make([("say", 1.0), ("quiet", 1), ("call", lambda: tts.entered.wait(5)),
                  ("call", lambda: box["talk"]()), ("quiet", 0.3), ("call", tts.release.set), ("quiet", 0.5)],
                 ["Marvin, raconte-moi une histoire."], ["Il était une fois un phare. Son gardien parlait peu."],
                 tts=tts, sink=BlockingSink())
    events = _events(va)
    played_at_talk = []

    def talk():
        played_at_talk.append(len(t.sink.played))
        va.listen_now()

    box["talk"] = talk
    va.run()
    assert played_at_talk == [0]
    assert len(t.sink.played) == 0                       # the held sentence never played after Talk now
    statuses = [d["status"] for k, d in events if k == "status"]
    assert statuses[-1] == "listening" and "speaking" not in statuses


def test_talk_now_while_speaking_never_clips_the_question():
    """Half duplex, an answer playing and its next sentence still being synthesised: Talk now and the
    question right away. The microphone must not wait for the cut answer's thread to finish."""
    tts = HeldTTS(hold=["Son gardien parlait peu."])
    box = {}
    va, t = make([("say", 1.0), ("quiet", 1), ("status", "speaking"), ("call", lambda: tts.entered.wait(5)),
                  ("call", lambda: box["va"].listen_now()), ("say", 1.2), ("quiet", 1.0),
                  ("call", tts.release.set), ("idle",)],
                 ["Marvin, raconte-moi une histoire.", "Avec des bateaux."],
                 ["Il était une fois un phare. Son gardien parlait peu.", "Il était une fois un bateau."],
                 tts=tts, sink=OnceBlockingSink())
    box["va"] = va
    events = _events(va)
    va.run()
    heard = [d for k, d in events if k == "heard"]
    assert heard[-1]["text"].endswith("Avec des bateaux.")        # heard whole, without the name
    assert heard[-1]["continues"] == [heard[0]["uid"]]            # and it goes on from the question it cut
    assert len(t.sink.played) == 2                                # the cut answer's second sentence never played


def test_the_microphone_reopens_only_once_the_speaker_is_silent():
    sink = DrainingSink(drain_s=0.3)
    tts = HeldTTS(hold=["Deuxième phrase."])
    seen = {}
    box = {}

    def stop():
        va = box["va"]
        seen["speaking"] = va.gate.state()
        va.stop_speaking()
        seen["stopped"] = (sink.busy, va.gate.state())
        # (the gate runs on audio time, which stands still in this step: open, or masked for the room's echo)
        _wait(lambda: va.gate.state() != CLOSED, 2.0)
        seen["open"] = sink.busy
        tts.release.set()

    va, t = make([("say", 1.0), ("quiet", 1), ("status", "speaking"), ("call", lambda: tts.entered.wait(5)),
                  ("call", stop), ("quiet", 0.2), ("idle",)],
                 ["Marvin, parle-moi."], ["Première phrase. Deuxième phrase."], tts=tts, sink=sink)
    box["va"] = va
    va.run()
    assert seen["speaking"] == CLOSED                     # speaking: deaf (half duplex)
    assert seen["stopped"] == (True, CLOSED)              # stopped, but the device still sounds
    assert seen["open"] is False                          # open once it is silent
    assert len(sink.played) == 1                          # the held sentence never played


def test_talk_now_while_thinking_restarts_cleanly():
    """Talk now before Marvin said anything: the question is cut (no reply at all), and what comes
    next continues it."""
    box = {}
    va, t = make([("say", 1.0), ("quiet", 1), ("status", "thinking"), ("call", lambda: box["va"].listen_now()),
                  ("quiet", 0.2), ("say", 1.2), ("quiet", 1.0), ("idle",)],
                 ["Marvin, quelle heure est-il ?", "À Tokyo."],
                 Replies(["Il est midi.", "À Tokyo, il est dix-neuf heures."], delay_s=0.6))
    box["va"] = va
    events = _events(va)
    va.run()
    replies = [d for k, d in events if k == "reply"]
    assert [r["text"] for r in replies] == ["À Tokyo, il est dix-neuf heures."]     # no empty, interrupted one
    heard = [d for k, d in events if k == "heard"]
    assert heard[-1]["text"] == "quelle heure est-il ? À Tokyo." and heard[-1]["continues"] == [heard[0]["uid"]]
    assert heard[-1]["cut"] == []                         # nothing of its answer had been heard


def test_the_long_endpoint_works_without_speculation():
    va, t = make([("quiet", 0.5), ("say", 1.0), ("quiet", 0.8), ("say", 1.0), ("quiet", 1.5), ("idle",)],
                 ["Marvin, je voudrais savoir et", "Marvin, je voudrais savoir et quelle heure il est."],
                 ["Bien."], continue_grace_s=0.0, speculative_stt=False)
    va.run()
    assert t.asked == ["je voudrais savoir et quelle heure il est."]


# ---------------------------------------------------------------- the device

class SwapSource:
    """A microphone that plays a script and then room noise until it is closed."""

    def __init__(self, script):
        self.inner = ScriptSource(script)
        self.closed = threading.Event()

    def frames(self):
        yield from self.inner.frames()
        while not self.closed.is_set():
            time.sleep(0.002)
            yield silence(0.02)

    def close(self):
        self.closed.set()
        self.inner.close()


def test_a_robot_linking_swaps_the_device_without_rebuilding_the_voice():
    computer = SwapSource([("quiet", 0.5)])
    robot = SwapSource([("quiet", 0.3), ("say", 1.0), ("quiet", 1.0)])
    sink1, sink2 = NullSink(), NullSink()
    stt = FakeSTT(["Marvin, bonjour."])
    va = ScriptedVoice(device(computer, sink1), VoiceConfig(follow_up_s=0), stt=stt, tts=FakeTTS(),
                       vad=EnergyVad(), replies=Replies(["Bonjour."]))
    computer.inner.assistant = robot.inner.assistant = va
    va.start()
    time.sleep(0.3)
    profile = ROBOT_PROFILE.__class__(**{**ROBOT_PROFILE.__dict__, "robot": "marvin-a1b2c3"})
    va.swap_device(AudioDevice(robot, sink2, profile))
    _wait(lambda: va.asked == ["bonjour."])
    assert va.wait_idle(5)
    assert computer.closed.is_set() and va.device.name == "marvin-a1b2c3"
    assert sink1.played == [] and len(sink2.played) == 1          # the answer on the robot's speaker
    assert va.gate.tail_s == pytest.approx(ROBOT_PROFILE.echo_tail_s + ROBOT_PROFILE.output_latency_s)
    assert va.stt is stt and va._thread.is_alive()                # same models, same listening thread
    va.close()


def test_earcons_play_on_the_device_and_are_not_speech():
    va, t = make([], [])
    va.earcon("wake")
    assert np.array_equal(t.sink.played[-1], earcon_pcm("wake"))
    assert va.gate.state() == "mask"
    calls = []

    class RobotSink(NullSink):
        def play_sound(self, sound):
            calls.append(sound)

    robot = ScriptedVoice(AudioDevice(ScriptSource([]), RobotSink(), ROBOT_PROFILE), VoiceConfig(), stt=FakeSTT(),
                          tts=FakeTTS(), vad=EnergyVad())
    robot.earcon("done")
    assert calls == ["done"] and robot.sink.played == []
    va.close()
    robot.close()


# ---------------------------------------------------------------- one voice per reply

class Clauses(Replies):
    """Replies given as (text, language) clauses, as the core sends them."""


def _speak(tts, clauses, language="fr"):
    va, t = make([], [], tts=tts)
    job = type("J", (), {})()
    from marvin_host.voice.engine import _Job
    job = _Job("say", "", language)
    va._speak_all(job, lambda emit: [emit(text, lang) for text, lang in clauses], {})
    va.close()
    return tts.said


def test_one_voice_per_reply_and_the_language_of_each_clause():
    clauses = [("C'est une bonne idée, Marvin,", "fr"), ("let me know when it's done.", "en")]
    # a voice per language (say, Piper): the reply's first voice for every clause, its name respelled for it
    said = _speak(FakeTTS(), clauses)
    assert said == [("C'est une bonne idée, Marvine,", "fr"), ("let me know when it's done.", "fr")]

    class Multilingual(FakeTTS):
        multilingual = True
        respells = False

    said = _speak(Multilingual(), clauses)
    assert said == [("C'est une bonne idée, Marvin,", "fr"), ("let me know when it's done.", "en")]


def test_a_streaming_synthesis_plays_as_it_comes():
    class Streaming(FakeTTS):
        def stream(self, text, language="fr"):
            pcm, rate = self.synthesize(text, language)
            half = len(pcm) // 2
            yield pcm[:half], rate
            yield pcm[half:], rate

    va, t = make([], [], tts=Streaming())
    events = _events(va)
    from marvin_host.voice.engine import _Job
    job = _Job("ask", "x", "fr", t_heard=time.monotonic(), uid=1)
    lat = {}
    va._speak_all(job, lambda emit: emit("Bonjour tout le monde.", "fr"), lat)
    assert len(t.sink.played) == 2                                 # two pieces, played as they came
    says = [d for k, d in events if k == "say"]
    assert len(says) == 1 and says[0]["text"] == "Bonjour tout le monde."
    assert {"tts", "first_audio", "audio_start", "playback_start"} <= set(lat)
    va.close()
