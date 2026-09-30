"""The room around the robot: the latest readings of its room sensors (PIR, temperature and humidity, light).

A receiver sink that keeps the last `ENVIRONMENT` (protocol.Environment) and when it came, on the host's
clock, so the app and the voice know how fresh it is. The brain owns one (`brain.room`) and hands it every
`ENVIRONMENT`; the PIR's part in the presence is the brain's own (brain.py).

    room.state()      RoomState, or None before any reading: for the voice's context (voice/persona.py)
    room.snapshot()   the same for the app: the `room` of the live state, None before any reading

Readings older than FRESH_S are not given to the language model (the app shows them as old). With several
devices sending, the latest reading wins: one room.

The Java host does the same (`Room` and `RoomState` in the robot context).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Callable

from . import protocol
from .receiver import Sink

FRESH_S = 120.0                  # a reading this old is no longer the room "now"
JUST_NOW_S = 10.0                # motion this recent is "just now"
# light level (0..1, higher is brighter) -> a word
DARK_BELOW = 0.15
DIM_BELOW = 0.45


def light_word(light: float | None) -> str | None:
    """dark, dim or bright; None without a reading."""
    if light is None:
        return None
    return "dark" if light < DARK_BELOW else "dim" if light < DIM_BELOW else "bright"


@dataclass
class RoomState:
    """The room as last read. `motion_age_s` is on the host's clock: the robot's figure plus the reading's age."""

    age_s: float                         # seconds since the reading came
    device: str
    simulated: bool
    motion_sensor: bool
    warming_up: bool
    motion: bool
    motion_age_s: float | None           # seconds since the last motion; 0 while it lasts; None: none seen
    climate_sensor: bool
    temperature_c: float | None
    humidity: float | None
    light_sensor: bool
    light: float | None

    @property
    def fresh(self) -> bool:
        return self.age_s < FRESH_S

    @property
    def light_word(self) -> str | None:
        return light_word(self.light)


class Room(Sink):
    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self.clock = clock
        self._lock = threading.Lock()
        self._last: tuple[str, protocol.Environment, float] | None = None

    def on_environment(self, dev, t_us: int, env: protocol.Environment) -> None:
        hello = getattr(dev, "hello", None)
        name = hello.device_name if hello is not None else ""
        with self._lock:
            self._last = (name, env, self.clock())

    def state(self) -> RoomState | None:
        with self._lock:
            last = self._last
        if last is None:
            return None
        name, e, at = last
        age = max(0.0, self.clock() - at)
        motion_age = None
        if e.pir and not e.warming_up:
            motion_age = 0.0 if e.motion else None if e.motion_age_s is None else e.motion_age_s + age
        return RoomState(age, name, e.simulated, e.pir, e.pir and e.warming_up, e.pir and e.motion and not e.warming_up,
                         motion_age, e.climate, e.temperature_c, e.humidity, e.light_sensor, e.light)

    def snapshot(self) -> dict | None:
        """The app's `room`: None before any reading."""
        s = self.state()
        if s is None:
            return None
        return {
            "fresh": s.fresh,
            "age_s": round(s.age_s, 1),
            "device": s.device,
            "simulated": s.simulated,
            "temperature_c": None if s.temperature_c is None else round(s.temperature_c, 1),
            "humidity": None if s.humidity is None else round(s.humidity, 1),
            "light": None if s.light is None else round(s.light, 3),
            "light_word": s.light_word,
            "motion_sensor": s.motion_sensor,
            "climate_sensor": s.climate_sensor,
            "light_sensor": s.light_sensor,
            "warming_up": s.warming_up,
            "motion": s.motion,
            "motion_age_s": None if s.motion_age_s is None else round(s.motion_age_s, 1),
        }
