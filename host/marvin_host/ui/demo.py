"""A simulated robot for the app: the brain fed by the simulated room, and a plausible past week.

``DemoRobot`` steps the simulator (sim.py / scene.py) frame by frame into a real ``Brain``, like
the brain tests do, with no sockets. The scene's person only sits for half a minute, so the demo
stretches each visit: the person sits for many minutes (swaying gently, vital signs readable),
then leaves for a while, and comes back. Time can run faster than real time (``speed``); the
demo's ``clock`` runs at the same pace, so the app's durations and timeline follow.

``seed_history`` writes a believable past week (and today until now) into a store, so the day
timeline and the week chart have something to show; ``seed_conversations`` adds a few past days
of conversation with Marvin (History panel). Only ever use them on a throwaway database.

With a ``sink`` (the app's ``UISink``), ``DemoRobot`` also plays two devices for the Robot panel, in
real time whatever the speed: the robot (lidar scans, LD2450 frames, link counters) and the
MR60BHA2 vital signs radar. ``DemoVoice`` stands in for the voice assistant when there is no
language model: a scripted conversation and canned answers, so the Talk panel can be tried.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import math
import random
import threading
import time
from datetime import datetime, time as dtime, timedelta

import numpy as np

from .. import frames, protocol, scene, sim
from ..events import EventKind as K, PresenceState
from ..voice import persona
from ..receiver import Device
from .stats import local_day

STEP_S = 0.1                    # simulator frame period (LD2450, MR60BHA2)
SIT_AT = 32.0                   # scene time at which a visit is stretched (seated, before the first fidget)
SWAY_S = 6.0                    # period of the slow back-and-forth while stretched
# minutes: (away before the visit, seated), repeated
VISITS = [(0.2, 24.0), (6.0, 53.0), (12.0, 11.0), (3.0, 37.0)]


def _visit_segments(visits=VISITS):
    """Yields (kind, duration_s) forever: ("away", s), ("walk", 0..SIT_AT), ("sit", s), ("walk", SIT_AT..LOOP)."""
    k = 0
    while True:
        away, sit = visits[k % len(visits)]
        yield "away", away * 60
        yield "in", SIT_AT
        yield "sit", round(sit * 60 / SWAY_S) * SWAY_S      # whole sway periods: continuous at both ends
        yield "out", scene.LOOP - SIT_AT
        k += 1


class DemoRobot:
    """Feeds ``brain`` with simulated frames in a background thread. ``clock()`` is the demo's time."""

    def __init__(self, brain, speed: float = 10.0, visits=VISITS, wall0: float | None = None, sink=None):
        self.brain = brain
        self.sink = sink
        self.devices = DemoDevices(sink) if sink is not None else None
        # the brain is told the data is simulated, as a real simulated robot says in its HELLO
        self.dev = self.devices.robot if self.devices is not None else Device(
            protocol.Hello(bytes.fromhex("024d56a1b2c3"), 3, protocol.FLAG_SIMULATED, -58, 0, "0.6.0"),
            ("192.168.1.42", protocol.DEVICE_PORT))
        self.speed = float(speed)
        self.visits = visits
        self.wall0 = time.time() if wall0 is None else wall0
        self.m0 = time.monotonic()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def clock(self) -> float:
        return self.wall0 + (time.monotonic() - self.m0) * self.speed

    def start(self) -> DemoRobot:
        self.m0 = time.monotonic()
        self._thread = threading.Thread(target=self._run, name="demo-robot", daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)

    def scene_times(self):
        """Yields the scene time of every frame, one per STEP_S of demo time."""
        for kind, dur in _visit_segments(self.visits):
            n = round(dur / STEP_S)
            for i in range(n):
                u = i * STEP_S
                if kind == "away":
                    yield 0.0                              # at the door, out of the radar's view
                elif kind == "in":
                    yield u
                elif kind == "sit":
                    yield SIT_AT + 1.5 * (2 / math.pi) * math.asin(math.sin(2 * math.pi * u / SWAY_S))
                else:
                    yield SIT_AT + u

    def _run(self) -> None:
        v = 0.0
        if self.devices is not None:
            self.devices.hello()
        for t in self.scene_times():
            while not self._stop.is_set() and (time.monotonic() - self.m0) * self.speed < v:
                time.sleep(0.01)
            if self._stop.is_set():
                return
            step(self.brain, t, v, self.dev)
            if self.devices is not None:
                self.devices.step(t)
            v += STEP_S


class DemoDevices:
    """The robot and the vital signs radar as the Robot panel sees them, fed to ``sink`` at real-time
    rates (10 scans and 10 radar frames a second) from the scene time the demo is at."""

    def __init__(self, sink, seed: int = 3):
        self.sink = sink
        self.rng = np.random.default_rng(seed)
        flags = protocol.FLAG_SIMULATED | protocol.FLAG_CAMERA | protocol.FLAG_AUDIO
        self.robot = Device(protocol.Hello(bytes.fromhex("024d56a1b2c3"), 3, flags, -58, 0, "0.6.0"),
                            ("192.168.1.42", protocol.DEVICE_PORT))
        self.radar = Device(protocol.Hello(bytes.fromhex("024d56c6b0a2"), 4, protocol.FLAG_SIMULATED, -64, 0,
                                           "mr60-0.2.1"), ("192.168.1.57", protocol.DEVICE_PORT))
        self.angles = np.arange(0.0, 360.0, 0.8)           # a D500 revolution at 10 Hz
        self.t0 = time.monotonic()
        self.next = 0.0

    def hello(self) -> None:
        for d in (self.robot, self.radar):
            self.sink.on_hello(d)
        self.sink.on_log(self.robot, 0, "firmware 0.6.0 up: lidar D500, radar LD2450, camera, audio")
        self.sink.on_log(self.robot, 0, "wifi: connected, rssi -58 dBm")
        self.sink.on_log(self.radar, 0, "MR60BHA2 ready")

    def step(self, scene_t: float) -> None:
        now = time.monotonic() - self.t0
        if now < self.next:
            return
        self.next = now + 0.1
        up = int(now * 1000)
        self.robot.hello.uptime_ms = self.radar.hello.uptime_ms = 3_600_000 + up
        t_us = up * 1000
        d = sim.scan_distances(self.angles, scene_t)
        d = np.where(np.isfinite(d), d + self.rng.normal(0, 8.0, d.shape), np.inf)
        pts = frames.lidar_to_device(self.angles, np.nan_to_num(d, posinf=0.0))
        self.sink.on_scan(self.robot, t_us, pts, np.full(len(pts), 200), 3600)
        targets = sim.ld2450_targets(scene_t)
        self.sink.on_targets(self.robot, t_us, targets, [frames.ld2450_to_device(g.x_mm, g.y_mm) for g in targets])
        ok, _, _, _, _, dist = scene.vitals_at(scene_t)
        # rates and waves follow real time, so they change at a human pace whatever the demo's speed
        br = 14.0 + 1.5 * math.sin(2 * math.pi * now / 70.0) + 0.3 * math.sin(2 * math.pi * now / 9.0)
        hr = 66.0 + 3.0 * math.sin(2 * math.pi * now / 50.0) + 1.0 * math.sin(2 * math.pi * now / 7.0)
        bw, hw = math.sin(2 * math.pi * 14.0 / 60 * now), math.sin(2 * math.pi * 66.0 / 60 * now)
        self.sink.on_vitals(self.radar, t_us, protocol.Vitals(ok, br, hr, bw if ok else 0.0, hw if ok else 0.0,
                                                              int(dist)))
        s = self.robot.stats
        s.datagrams += 5
        s.lidar_packets += 38
        s.radar_frames += 1
        if self.rng.random() < 0.02:
            s.lost += 1
        if self.rng.random() < 0.002:
            s.crc_errors += 1
        self.radar.stats.datagrams += 1


def step(brain, scene_t: float, device_t: float, dev=None) -> None:
    """One LD2450 frame and one vitals frame of the simulated room, stamped with ``device_t``."""
    t_us = round(device_t * 1e6)
    targets = sim.ld2450_targets(scene_t)
    brain.on_targets(dev, t_us, targets, [frames.ld2450_to_device(g.x_mm, g.y_mm) for g in targets])
    brain.on_vitals(dev, t_us, sim.vitals(scene_t))


# ------------------------------------------------------------------------------ voice

class DemoVoice:
    """A stand-in for ``VoiceAssistant`` (same control API: ``ask``, ``listen_now``, ``mute``,
    ``stop_speaking``, ``add_listener``...): a scripted past conversation, and canned answers to
    typed questions, drawn from the brain. It hears nothing and says nothing aloud."""

    MODEL = "qwen3:4b-instruct"             # what the scripted answers pretend to come from
    SCRIPT = [   # minutes ago, kind, text, extra ("seated": minutes seated, for the context)
        (38, "heard", "Good morning. Anything I should know?",
         {"raw": "Marvin, good morning. Anything I should know?"}),
        (38, "reply", "Good morning. You sat down at nine and haven't moved much. The coffee is still warm, I assume.",
         {"latency": {"endpoint": 0.55, "stt": 0.18, "llm_first_token": 0.21, "first_chunk": 0.34, "tts": 0.14,
                      "audio_start": 0.71, "speculative": 1.0}, "seated": 12}),
        (31, "ignored", "Thanks for watching!", {"reason": "known hallucination", "dbfs": -41.5}),
        (30, "ignored", "", {"reason": "too quiet (-53 dBFS)", "dbfs": -53.2}),
        (30, "ignored", "", {"reason": "only 0.18 s of speech", "dbfs": -36.8}),
        (22, "heard", "What's my heart rate right now?", {"raw": "Marvin, what's my heart rate right now?"}),
        (22, "reply", "About 66 beats a minute, and you're breathing 14 times a minute. Calm, as far as I can tell.",
         {"latency": {"endpoint": 0.55, "stt": 0.09, "llm_first_token": 0.19, "first_chunk": 0.30, "tts": 0.12,
                      "audio_start": 0.62, "speculative": 1.0}, "seated": 28, "vitals": (14.0, 66.0)}),
        (21, "ignored", "Oui, je sais.", {"reason": "conversation closed: thanks", "dbfs": -33.9}),
        (4, "reply", "You've been sitting for 50 minutes. Time to stretch?", {"proactive": True}),
    ]

    def __init__(self, brain, clock=time.time, time_scale: float = 1.0):
        self.brain = brain
        self.clock = clock
        self.time_scale = time_scale          # demo seconds per real second
        self.status = "idle"
        self.muted = False
        self.language = "en"
        self._saying = ""
        self._listeners = []
        self._timer: threading.Timer | None = None
        self._lock = threading.Lock()
        self._seq = 0

    # the VoiceAssistant API used by VoiceController
    def add_listener(self, fn) -> None:
        self._listeners.append(fn)

    def remove_listener(self, fn) -> None:
        if fn in self._listeners:
            self._listeners.remove(fn)

    def start(self) -> DemoVoice:
        now = self.clock()
        question = ""
        for minutes, kind, text, extra in self.SCRIPT:
            extra = dict(extra)
            t = now - minutes * 60
            seated, vitals = extra.pop("seated", None), extra.pop("vitals", None)
            data = {"text": text, **extra}
            if kind == "heard":
                question = text
                data = {"language": "en", "source": "voice", **data}
            if kind == "reply":
                data = {"language": "en", "latency": {}, "interrupted": False, "proactive": False, "error": None,
                        "hint": "", **data}
                if not data["proactive"]:
                    data.update(scripted_prompt(question, t, seated or 0, vitals))
            self._emit(kind, t, **data)
        return self

    def close(self) -> None:
        self._cancel()

    def say(self, text: str, language: str | None = None, force: bool = False) -> bool:
        if self.status != "idle" and not force:
            return False
        self._speak(text, {}, proactive=True)
        return True

    def ask(self, text: str, language: str | None = None) -> None:
        self._cancel()
        self._emit("heard", text=text, language="en", source="typed")
        self._set("thinking")
        seq = self._seq
        if is_weather_question(text):           # the model calls get_weather (with demo data), then answers
            self._later(1.1, lambda: self._speak(WEATHER_ANSWER, dict(WEATHER_LATENCY), seq=seq, question=text,
                                                 tools=[dict(WEATHER_CALL)]))
            return
        self._later(0.7, lambda: self._speak(self.answer(text), {
            "endpoint": 0.0, "stt": 0.0, "llm_first_token": 0.24, "first_chunk": 0.38, "tts": 0.13,
            "audio_start": 0.52}, seq=seq, question=text))

    SPOKEN = "What time is it?"               # what the demo "hears" after Talk now

    def stop_listening(self) -> bool:
        if self.status != "listening":
            return False
        self._cancel()
        self._set("idle")
        return True

    def listen_remaining(self):
        if self.status != "listening":
            return None
        return max(0.0, getattr(self, "_listen_end", 0.0) - time.monotonic())

    def listen_now(self) -> None:
        """A listening window in which the demo hears someone ask the time, with the live signals
        (microphone level, utterance, partial transcript) the real assistant sends."""
        self._cancel()
        if self.muted:
            self.mute(False)
        self._listen_end = time.monotonic() + 6.0
        self._set("listening")
        seq = self._seq
        uid = seq
        talking = {"on": False}

        def levels():
            rnd = random.Random(seq)
            while seq == self._seq and self.status == "listening":
                t = time.monotonic()
                mic = (0.45 + 0.4 * abs(math.sin(t * 9.0)) * rnd.random()) if talking["on"] else 0.04 + 0.05 * rnd.random()
                self._emit("level", mic=round(mic, 3), speech=talking["on"], gated=False)
                time.sleep(0.06)

        def start():
            talking["on"] = True
            self._emit("utterance", state="start", uid=uid)

        def end():
            talking["on"] = False
            self._emit("utterance", state="end", uid=uid)

        def heard():
            self._emit("heard", text=self.SPOKEN, language="en", source="voice")
            self._emit("utterance", state="done", uid=uid)
            self._set("thinking")

        threading.Thread(target=levels, name="demo-voice-level", daemon=True).start()
        self._steps(seq, [(0.9, start), (1.5, lambda: self._emit("partial", uid=uid, text="What time")),
                          (1.2, end), (0.5, heard),
                          (0.7, lambda: self._speak(self.answer(self.SPOKEN), {
                              "endpoint": 0.5, "stt": 0.08, "llm_first_token": 0.22, "first_chunk": 0.35,
                              "tts": 0.12, "audio_start": 0.61, "speculative": 1.0}, seq=seq,
                              question=self.SPOKEN))])

    def mute(self, muted: bool = True) -> None:
        if muted != self.muted:
            self.muted = muted
            self._emit("muted", muted=muted)

    def stop_speaking(self) -> bool:
        busy = self.status in ("thinking", "speaking")
        if busy:
            self._cancel()
            self._set("idle")
        return busy

    def _interrupted(self) -> None:
        """What was being said is cut short, as the real assistant reports it."""
        if self.status == "speaking" and self._saying:
            self._emit("reply", text=self._saying + " …", language="en", latency={}, interrupted=True,
                       proactive=False, error=None, hint="")

    # scripted behaviour
    def answer(self, text: str) -> str:
        s = getattr(self.brain, "state", None)
        q = text.lower()
        if s is not None and any(w in q for w in ("sit", "seated", "long", "break")):
            if s.seated:
                return f"You've been seated for {max(1, round(s.seated_s / 60))} minutes. A short walk soon would do you good."
            return "You're not sitting right now. Good."
        if s is not None and any(w in q for w in ("heart", "breath", "pulse")):
            if s.heart_rate is not None:
                return (f"About {s.heart_rate:.0f} beats a minute, and {s.breath_rate:.0f} breaths a minute. "
                        "Calm, as far as I can tell.")
            return "I can only read that while you sit still in front of me."
        if "time" in q:
            return "It's " + time.strftime("%H:%M", time.localtime(self.clock())) + "."
        return ("This is the demo, so I have no language model to think with. Install Ollama and start "
                "marvin-host run to talk to me for real.")

    def _prompt(self, question: str) -> dict:
        """What a real assistant would have sent the model: the brain's context and the question."""
        state = getattr(self.brain, "state", None)
        try:
            events = list(getattr(self.brain, "events", ()))
        except RuntimeError:
            events = []
        context = persona.context_block(state, events)
        return {"context": context, "prompt": persona.user_message(question, language="en", context=context),
                "model": self.MODEL}

    def _speak(self, text: str, latency: dict, proactive: bool = False, seq: int | None = None,
               question: str = "", tools: list | None = None) -> None:
        if seq is not None and seq != self._seq:
            return
        self._set("speaking")
        self._saying = text
        seq = self._seq

        what = {} if proactive else self._prompt(question)
        if tools:
            what["tools"] = tools

        def done():
            if seq != self._seq:
                return
            self._saying = ""
            self._emit("reply", text=text, language="en", latency=latency, interrupted=False, proactive=proactive,
                       error=None, hint="", **what)
            self._set("listening")
            self._later(3.0, lambda: self._idle(seq))

        steps, total = [], 0.0
        for piece in _sentences(text):                  # "say" per sentence, like the real speaker
            seconds = 0.35 + 0.06 * len(piece)
            steps.append((0.0 if not steps else prev, lambda p=piece, d=seconds: self._emit(
                "say", text=p, seconds=round(d, 3), envelope=_envelope(p, d))))
            prev = seconds * 0.4                        # synthesis runs ahead of playback
            total += seconds
        steps.append((max(0.2, total - sum(d for d, _ in steps)), done))
        self._steps(seq, steps)

    def _steps(self, seq: int, steps) -> None:
        """Runs `(delay, fn)` steps one after the other on a thread, until the demo is interrupted."""
        def run():
            for delay, fn in steps:
                time.sleep(delay)
                if seq != self._seq:
                    return
                fn()
        threading.Thread(target=run, name="demo-voice", daemon=True).start()

    def _idle(self, seq: int) -> None:
        if seq == self._seq:
            self._set("idle")

    def _later(self, s: float, fn) -> None:
        with self._lock:
            self._timer = threading.Timer(s, fn)
            self._timer.daemon = True
            self._timer.start()

    def _cancel(self) -> None:
        self._interrupted()
        self._saying = ""
        with self._lock:
            self._seq += 1
            if self._timer is not None:
                self._timer.cancel()
                self._timer = None

    def _set(self, status: str) -> None:
        if status != self.status:
            self.status = status
            self._emit("status", status=status)

    def _emit(self, kind: str, t: float | None = None, **data) -> None:
        data["t"] = self.clock() if t is None else t
        for fn in list(self._listeners):
            fn(kind, data)


WEATHER_WORDS = ("weather", "météo", "meteo", "temperature", "température", "rain", "pluie", "forecast")
# the demo's pretend weather: clearly marked as such, never a real forecast
WEATHER_CALL = {"name": "get_weather", "arguments": {"place": "Nice", "day": "now"}, "ok": True, "seconds": 0.41,
                "result": json.dumps({"place": "Nice, France (demo data)", "when": "now (demo)",
                                      "conditions": "partly cloudy", "temperature_c": 21, "feels_like_c": 21,
                                      "wind_kmh": 12, "precipitation_mm": 0.0, "today_min_c": 17, "today_max_c": 24,
                                      "today_rain_chance_percent": 10}, ensure_ascii=False)}
WEATHER_ANSWER = ("Let me check… In Nice it's twenty-one degrees and partly cloudy, with a light breeze. "
                  "These are demo numbers, not a real forecast.")
WEATHER_LATENCY = {"endpoint": 0.0, "stt": 0.0, "llm_first_token": 0.23, "tools": 0.41, "llm_first_token_2": 0.2,
                   "first_chunk": 0.97, "tts": 0.13, "audio_start": 1.12, "filler_start": 0.38}


def is_weather_question(text: str) -> bool:
    q = text.lower()
    return any(w in q for w in WEATHER_WORDS)


def _sentences(text: str) -> list[str]:
    out, cur = [], ""
    for word in text.split():
        cur = f"{cur} {word}".strip()
        if word[-1] in ".?!":
            out.append(cur)
            cur = ""
    return out + ([cur] if cur else [])


def _envelope(text: str, seconds: float, hz: int = 20) -> list[float]:
    """A made-up loudness curve for `text`: one bump per syllable-ish, silence between words."""
    n = max(1, int(seconds * hz))
    rnd = random.Random(text)
    return [round(max(0.0, 0.25 + 0.6 * abs(math.sin(i * 1.7)) * rnd.random() - (0.2 if i % 7 == 6 else 0)), 2)
            for i in range(n)]


def scripted_prompt(question: str, t: float, seated_min: float, vitals=None) -> dict:
    """The context and message a scripted answer pretends to have been given, at time ``t``."""
    st = PresenceState(present=True, seated=seated_min > 0, seated_s=seated_min * 60, distance_m=0.9,
                       breath_rate=vitals[0] if vitals else None, heart_rate=vitals[1] if vitals else None,
                       vitals_sensor=True, simulated=True, targets=1)
    context = persona.context_block(st, now=datetime.fromtimestamp(t))
    return {"context": context, "prompt": persona.user_message(question, language="en", context=context),
            "model": DemoVoice.MODEL}


# a few past conversations: (question, answer, minutes seated) or ("ignored", text, reason, dbfs)
PAST_TALK = [
    ("Can you see the window from here?", "My lidar sees the wall it is in, not whether it's open. "
     "I'd trust your ears over mine on that.", 20),
    ("How long have I been sitting?", "About forty minutes. A short walk in ten would be a good idea.", 40),
    ("Remind me what I was doing before lunch?", "I only know when you sat and stood, not what you did. "
     "You worked for about two hours before lunch.", 5),
    ("Tell me something cheerful.", "Your breathing is slow and even. For a desk robot, that counts as good news.",
     30),
    ("Am I breathing normally?", "Fourteen breaths a minute, calm and regular. I'm not a doctor, though.", 25),
    ("ignored", "Thank you.", "known hallucination", -44.0),
    ("ignored", "", "mostly silence (0.31 of 1.80 s voiced)", -47.5),
    ("Should I take a break?", "You've been at it for fifty minutes. Yes, stand up and stretch.", 50),
    ("Thanks for the reminder earlier.", "Any time. That is rather the point of me.", 0),
]


def seed_conversations(store, now: float, days: int = 3, seed: int = 11) -> None:
    """Writes a few conversations with Marvin on the last ``days`` working days before ``now``'s
    day (someone was at the desk on those, see ``seed_history``), into ``store`` (History panel).
    Only ever use it on a throwaway database."""
    today = local_day(now)
    workdays = [d for d in (today - timedelta(days=k) for k in range(1, 3 * days + 3)) if d.weekday() < 5][:days]
    for day in reversed(workdays):
        rng = random.Random(f"{seed}-{day.isoformat()}")
        t = _at(day, rng.uniform(8.6, 9.3))
        n = 0

        def put(kind, ts, text, **data):
            nonlocal n
            store.add_conversation({"id": int(ts * 1000) + n, "t": ts, "kind": kind, "text": text, **data})
            n += 1

        put("note", t, "Voice on: say “Marvin, …”")
        for item in rng.sample(PAST_TALK, rng.randint(3, 5)):
            t += rng.uniform(25, 110) * 60
            if item[0] == "ignored":
                put("ignored", t, item[1], reason=item[2], dbfs=item[3])
                continue
            question, answer, seated = item
            put("heard", t, question, language="en", source="voice", raw=f"Marvin, {question[0].lower()}{question[1:]}")
            vitals = (round(rng.gauss(14, 0.8), 1), round(rng.gauss(66, 3), 1)) if seated >= 5 else None
            lat = {"endpoint": 0.55, "stt": round(rng.uniform(0.08, 0.2), 2),
                   "llm_first_token": round(rng.uniform(0.18, 0.3), 2), "tts": round(rng.uniform(0.1, 0.16), 2),
                   "speculative": 1.0}
            lat["first_chunk"] = round(lat["llm_first_token"] + rng.uniform(0.08, 0.2), 2)
            lat["audio_start"] = round(lat["stt"] + lat["first_chunk"] + lat["tts"] + 0.02, 2)
            put("reply", t + 2, answer, language="en", latency=lat,
                first_word_s=round(lat["endpoint"] + lat["audio_start"], 2), interrupted=False, proactive=False,
                error=None, hint="", **scripted_prompt(question, t, seated, vitals))
        if rng.random() < 0.7:
            t += rng.uniform(20, 60) * 60
            put("reply", t, "You've been sitting for 50 minutes. Time to stretch?", language="en", latency={},
                first_word_s=None, interrupted=False, proactive=True, error=None, hint="")


# ------------------------------------------------------------------------------ past week

def seed_history(store, now: float, days: int = 7, seed: int = 7, until: float | None = None,
                 break_interval_min: float = 50) -> None:
    """Writes simulated events and per-minute samples for the last ``days`` days, up to ``until``
    (default: a few minutes before ``now``)."""
    until = now - 300 if until is None else until
    today = local_day(now)
    samples = []
    for k in range(days - 1, -1, -1):
        day = today - timedelta(days=k)
        rng = random.Random(f"{seed}-{day.isoformat()}")
        # today is always a working day, so the demo has something to show on a Sunday too
        for kind, ts, detail, data in _day_plan(day, rng, until, break_interval_min, workday=k == 0):
            store.add(kind, ts, detail, data)
        samples += _day_samples(store, day, rng, until)
    store.add_samples(samples)


def _at(day, hours: float) -> float:
    return datetime.combine(day, dtime()).astimezone().timestamp() + hours * 3600


def _day_plan(day, rng: random.Random, until: float, interval_min: float, workday: bool = False):
    weekend = day.weekday() >= 5 and not workday
    if weekend and rng.random() < 0.5:
        return []
    start = _at(day, rng.uniform(9.8, 10.6) if weekend else rng.uniform(8.3, 9.2))
    end = _at(day, rng.uniform(12.0, 13.5) if weekend else rng.uniform(17.4, 18.8))
    lunch = None if weekend else _at(day, rng.uniform(12.1, 12.6))
    out = []
    t = start
    present = seated = False
    sat_at = 0.0

    def emit(kind, ts, detail="", **data):
        out.append((kind.value, ts, detail, data))

    def arrive(ts):
        nonlocal present
        emit(K.ARRIVED, ts, f"{rng.uniform(1.6, 2.4):.2f} m away", distance_m=round(rng.uniform(1.6, 2.4), 2))
        present = True

    def leave(ts):
        nonlocal present
        stand(ts)
        emit(K.LEFT, ts + 3, "not seen for 3 s")
        present = False

    def sit(ts):
        nonlocal seated, sat_at
        d = round(rng.uniform(0.8, 0.95), 2)
        emit(K.SAT_DOWN, ts, f"{d:.2f} m away", distance_m=d)
        seated, sat_at = True, ts

    def stand(ts):
        nonlocal seated
        if seated:
            s = ts - sat_at
            emit(K.VITALS_LOST, ts, "movement")
            emit(K.STOOD_UP, ts, f"after {s / 60:.0f} min seated", seated_s=round(s, 1))
            seated = False

    arrive(t)
    t += rng.uniform(60, 240)
    while t < end:
        sit(t)
        length = rng.choice([rng.uniform(15, 45), rng.uniform(35, 70), rng.uniform(50, 95)]) * 60
        length = min(length, max(60.0, end - t))
        if length >= interval_min * 60:
            emit(K.STILL_LONG, t + interval_min * 60, f"seated for {interval_min:.0f} min",
                 seated_s=interval_min * 60)
        if length > 180:
            b, h = rng.uniform(12, 16), rng.uniform(60, 72)
            emit(K.VITALS_ACQUIRED, t + rng.uniform(20, 90), f"breath {b:.0f}/min, heart {h:.0f}/min",
                 breath_rate=round(b, 1), heart_rate=round(h, 1))
        t = min(t + length, end)
        if lunch is not None and t >= lunch:
            leave(t)
            t += rng.uniform(40, 70) * 60
            lunch = None
            arrive(t)
            t += rng.uniform(60, 180)
        elif t < end and rng.random() < 0.35:
            leave(t)
            t += rng.uniform(8, 20) * 60
            arrive(t)
            t += rng.uniform(60, 150)
        else:
            stand(t)
            t += rng.uniform(2, 7) * 60
    if seated or present:
        leave(t)

    # cut at `until`: keep what already happened, and close what was going on
    out.sort(key=lambda e: e[1])
    kept = [e for e in out if e[1] < until - 3]
    if len(kept) < len(out):
        open_seat = sum(1 for e in kept if e[0] == K.SAT_DOWN.value) > sum(1 for e in kept if e[0] == K.STOOD_UP.value)
        open_here = sum(1 for e in kept if e[0] == K.ARRIVED.value) > sum(1 for e in kept if e[0] == K.LEFT.value)
        if open_seat:
            sat = max(e[1] for e in kept if e[0] == K.SAT_DOWN.value)
            s = until - 3 - sat
            kept.append((K.STOOD_UP.value, until - 3, f"after {s / 60:.0f} min seated", {"seated_s": round(s, 1)}))
        if open_here:
            kept.append((K.LEFT.value, until, "not seen for 3 s", {}))
    return kept


def _day_samples(store, day, rng: random.Random, until: float):
    """Per-minute samples consistent with the events already written for that day."""
    from .stats import day_bounds, fold
    lo, hi = day_bounds(day)
    f = fold(store.events(lo, hi), until, live=False)
    rows = []
    for a, b in f.present:
        m = math.ceil(a / 60) * 60
        while m < min(b, until):
            seated = any(s <= m < e for s, e in f.seated)
            ok = seated and rng.random() < 0.75
            rows.append((m, 1.0, 1.0 if seated else 0.0,
                         round(rng.gauss(14.0, 1.0), 1) if ok else None,
                         round(rng.gauss(66.0, 3.0), 1) if ok else None))
            m += 60
    return rows
