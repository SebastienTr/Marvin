"""The brain: turns sensor frames into events and a live picture of the person in front of the robot.

The brain is a receiver `Sink`. It follows the nearest person seen by the HLK-LD2450 (10 Hz),
decides whether they are present, close, seated, still, and whether the MR60BHA2 vital signs
can be trusted, and emits `Event`s (see events.py) when that picture changes. Every decision is
debounced or has hysteresis, so events never flicker on noisy readings.

Time comes only from the frames' `t_us` (device clock), never from the wall clock, so a
recording replays to exactly the same events. If the device clock jumps backwards (the robot
rebooted), the brain starts over.

Assumptions:
- One robot. Frames from several devices would share one state (`dev` is not used).
- Head height: the radars do not measure height reliably, so `state.head` is the tracked position
  with Z set to a typical head height: `head_z_seated_mm` (550 mm, head about 1.3 m above the floor)
  or `head_z_standing_mm` (1000 mm, head about 1.75 m above the floor). Device frame: Z = 0 is the
  table top the robot stands on, the floor is at Z = -750.
- The lidar is not used. The LD2450 already tracks people, and a lidar slice at chest height
  cannot tell a person from a chair back or a coat rack without a background model of the room.
  It is a good candidate for later (confirming presence, following someone the radar misses).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import math
from collections import deque
from dataclasses import dataclass
from typing import Callable

import numpy as np

from . import ld2450, protocol
from .events import Event, EventKind, PresenceState
from .receiver import Device, Sink

log = logging.getLogger("marvin.brain")


@dataclass
class BrainConfig:
    """Thresholds. Distances are horizontal, from the robot's axis. Times in seconds."""

    # presence
    arrive_confirm_s: float = 0.5        # someone must be seen this long before ARRIVED
    leave_after_s: float = 3.0           # nobody seen this long -> LEFT
    # approach (hysteresis: fires below `approach_m`, re-armed beyond `approach_rearm_m`)
    approach_m: float = 0.6
    approach_rearm_m: float = 0.9
    # smoothing
    position_tau_s: float = 0.2          # exponential moving average of the position
    speed_tau_s: float = 0.5             # exponential moving average of the radar speed
    radar_speed_sign: int = -1           # LD2450 speed is positive when approaching; state speed is positive moving away
    # stillness: the position spread over a short window stays small and the speed stays low
    still_window_s: float = 1.0
    still_move_mm: float = 120.0
    still_speed_cms: float = 10.0
    # sitting
    sit_max_m: float = 1.3               # SAT_DOWN only this close to the robot...
    sit_still_s: float = 3.0             # ...after being still this long
    stand_move_mm: float = 300.0         # STOOD_UP: moved this far from where they sat down...
    stand_away_m: float = 0.3            # ...or this much further from the robot...
    stand_speed_cms: float = 30.0        # ...or moving faster than this...
    stand_speed_s: float = 0.5           # ...for this long
    still_long_s: float = 50 * 60        # STILL_LONG after sitting this long (once per sitting)
    # vital signs (MR60BHA2)
    vitals_acquire_s: float = 3.0        # valid, plausible readings on a still person for this long
    vitals_lose_s: float = 1.0           # bad readings for this long -> VITALS_LOST
    breath_range: tuple[float, float] = (6.0, 30.0)     # plausible breaths per minute
    heart_range: tuple[float, float] = (40.0, 140.0)    # plausible beats per minute
    # head height estimate, device frame (Z = 0 on the table top, floor at -750)
    head_z_seated_mm: float = 550.0
    head_z_standing_mm: float = 1000.0
    # misc
    clock_reset_s: float = 1.0           # t_us going back more than this = device restart
    max_events: int = 200                # size of `Brain.events`


class Brain(Sink):
    """Sensor frames in, `Event`s and `PresenceState` out. See the module docstring."""

    def __init__(self, config: BrainConfig | None = None):
        self.config = config or BrainConfig()
        self.state = PresenceState()
        self.events: deque[Event] = deque(maxlen=self.config.max_events)
        self._listeners: list[Callable[[Event], None]] = []
        self._reset(0)

    # ------------------------------------------------------------ public API

    def add_listener(self, fn: Callable[[Event], None]) -> None:
        """Call `fn(event)` for every event from now on."""
        self._listeners.append(fn)

    def remove_listener(self, fn: Callable[[Event], None]) -> None:
        self._listeners.remove(fn)

    # ------------------------------------------------------------ Sink

    def on_targets(self, dev: Device, t_us: int, targets: list[ld2450.Target], points: list[tuple]) -> None:
        if not self._clock(t_us):
            return
        t = t_us / 1e6
        dt = 0.0 if self._last_targets_t is None else max(0.0, t - self._last_targets_t)
        self._last_targets_t = t
        s = self.state
        s.targets = len(targets)

        if targets:
            i = min(range(len(points)), key=lambda k: math.hypot(points[k][0], points[k][1]))
            self._track(t, dt, points[i], targets[i])
        else:
            self._seen_since = None

        self._presence(t)
        if s.present:
            self._approach(t)
            self._sitting(t, dt)
        self._update_head()

    def on_vitals(self, dev: Device, t_us: int, vitals: protocol.Vitals) -> None:
        if not self._clock(t_us):
            return
        c, s, t = self.config, self.state, t_us / 1e6
        s.vitals_sensor = True
        good = (vitals.valid and s.present and self._still
                and c.breath_range[0] <= vitals.breath_rate <= c.breath_range[1]
                and c.heart_range[0] <= vitals.heart_rate <= c.heart_range[1])
        if good:
            self._vitals_bad_since = None
            if self._vitals_good_since is None:
                self._vitals_good_since = t
            if self._vitals_ok:
                s.breath_rate, s.heart_rate = vitals.breath_rate, vitals.heart_rate
            elif t - self._vitals_good_since >= c.vitals_acquire_s:
                self._vitals_ok = True
                s.breath_rate, s.heart_rate = vitals.breath_rate, vitals.heart_rate
                self._emit(EventKind.VITALS_ACQUIRED, t_us,
                           f"breath {s.breath_rate:.0f}/min, heart {s.heart_rate:.0f}/min",
                           breath_rate=s.breath_rate, heart_rate=s.heart_rate)
        else:
            self._vitals_good_since = None
            if self._vitals_bad_since is None:
                self._vitals_bad_since = t
            if self._vitals_ok and t - self._vitals_bad_since >= c.vitals_lose_s:
                self._lose_vitals(t_us, "movement" if s.present and not self._still else "no valid reading")

    def on_scan(self, dev: Device, t_us: int, points: np.ndarray, intensities: np.ndarray, speed_dps: int) -> None:
        pass            # not used yet, see the module docstring

    # ------------------------------------------------------------ internals

    def _reset(self, t_us: int) -> None:
        had = getattr(self, "state", None) is not None and self.state.vitals_sensor
        self.state = PresenceState(t_us=t_us, vitals_sensor=had)
        self._last_t_us: int | None = None
        self._last_targets_t: float | None = None
        self._seen_since: float | None = None       # first frame of the current run of sightings
        self._last_seen: float | None = None
        self._pos: tuple[float, float, float] | None = None   # smoothed nearest person
        self._speed = 0.0                           # smoothed, cm/s, positive = moving away
        self._window: deque[tuple[float, float, float]] = deque()   # (t, x, y) raw positions
        self._still = False
        self._still_since: float | None = None
        self._fast_since: float | None = None
        self._approach_armed = True
        self._seat: tuple[float, float, float] | None = None   # where they sat down
        self._seat_distance = 0.0
        self._seated_since = 0.0
        self._still_long_sent = False
        self._vitals_ok = False
        self._vitals_good_since: float | None = None
        self._vitals_bad_since: float | None = None

    def _clock(self, t_us: int) -> bool:
        """Checks the device clock. Returns False for a frame that should be dropped."""
        last = self._last_t_us
        if last is not None and t_us < last:
            if last - t_us < self.config.clock_reset_s * 1e6:
                return False                        # slightly out of order: drop it
            log.info("device clock went back %.1f s: restarting", (last - t_us) / 1e6)
            if self._vitals_ok:
                self._lose_vitals(t_us, "sensor restarted")
            if self.state.present:
                self._emit(EventKind.LEFT, t_us, "sensor restarted")
            self._reset(t_us)
        self._last_t_us = t_us
        self.state.t_us = t_us
        return True

    def _track(self, t: float, dt: float, point: tuple, target: ld2450.Target) -> None:
        c, s = self.config, self.state
        x, y, z = (float(v) for v in point)
        gap = self._last_seen is None or t - self._last_seen > c.still_window_s
        if self._pos is None or gap:                # first sighting, or back after a gap: no smoothing across it
            self._pos = (x, y, z)
            self._speed = c.radar_speed_sign * target.speed_cms
            self._window.clear()
        else:
            a = _alpha(dt, c.position_tau_s)
            self._pos = tuple(p + a * (v - p) for p, v in zip(self._pos, (x, y, z)))
            self._speed += _alpha(dt, c.speed_tau_s) * (c.radar_speed_sign * target.speed_cms - self._speed)
        if self._seen_since is None:
            self._seen_since = t
        self._last_seen = t

        self._window.append((t, x, y))
        while self._window[0][0] < t - c.still_window_s:
            self._window.popleft()
        xs = [w[1] for w in self._window]
        ys = [w[2] for w in self._window]
        spread = math.hypot(max(xs) - min(xs), max(ys) - min(ys))
        self._still = spread < c.still_move_mm and abs(self._speed) < c.still_speed_cms
        if not self._still or self._still_since is None:
            self._still_since = t
        if abs(self._speed) < c.stand_speed_cms:
            self._fast_since = None
        elif self._fast_since is None:
            self._fast_since = t

        s.position = self._pos
        s.distance_m = math.hypot(self._pos[0], self._pos[1]) / 1000
        s.speed_cms = self._speed
        s.still_s = t - self._still_since

    def _presence(self, t: float) -> None:
        c, s = self.config, self.state
        if not s.present:
            if self._seen_since is not None and t - self._seen_since >= c.arrive_confirm_s:
                s.present = True
                self._approach_armed = True
                self._emit(EventKind.ARRIVED, s.t_us, f"{s.distance_m:.2f} m away", distance_m=s.distance_m)
        elif self._last_seen is None or t - self._last_seen >= c.leave_after_s:
            if self._vitals_ok:
                self._lose_vitals(s.t_us, "person left")
            self._emit(EventKind.LEFT, s.t_us, f"not seen for {c.leave_after_s:.0f} s")
            t_us, targets = s.t_us, s.targets
            self._reset(t_us)                       # forget everything about them, keep the clock
            self._last_t_us, self._last_targets_t = t_us, t
            self.state.targets = targets

    def _approach(self, t: float) -> None:
        c, s = self.config, self.state
        if s.distance_m is None:
            return
        if self._approach_armed and s.distance_m < c.approach_m:
            self._approach_armed = False
            self._emit(EventKind.APPROACHED, s.t_us, f"{s.distance_m:.2f} m away", distance_m=s.distance_m)
        elif s.distance_m > c.approach_rearm_m:
            self._approach_armed = True

    def _sitting(self, t: float, dt: float) -> None:
        c, s = self.config, self.state
        if not s.seated:
            s.seated_s = 0.0
            if s.distance_m is not None and s.distance_m <= c.sit_max_m and s.still_s >= c.sit_still_s:
                s.seated = True
                self._seat, self._seat_distance, self._seated_since = s.position, s.distance_m, t
                self._still_long_sent = False
                self._emit(EventKind.SAT_DOWN, s.t_us, f"{s.distance_m:.2f} m away", distance_m=s.distance_m)
            return
        s.seated_s = t - self._seated_since
        moved = math.hypot(s.position[0] - self._seat[0], s.position[1] - self._seat[1])
        fast = self._fast_since is not None and t - self._fast_since >= c.stand_speed_s
        if moved > c.stand_move_mm or s.distance_m - self._seat_distance > c.stand_away_m or fast:
            s.seated = False
            self._emit(EventKind.STOOD_UP, s.t_us, f"after {_duration(s.seated_s)} seated", seated_s=s.seated_s)
            s.seated_s = 0.0
            self._still_since = t                   # standing up is movement
            s.still_s = 0.0
            if self._vitals_ok:
                self._lose_vitals(s.t_us, "movement")
        elif not self._still_long_sent and s.seated_s >= c.still_long_s:
            self._still_long_sent = True
            self._emit(EventKind.STILL_LONG, s.t_us, f"seated for {_duration(s.seated_s)}", seated_s=s.seated_s)

    def _update_head(self) -> None:
        s, c = self.state, self.config
        if s.present and s.position is not None:
            z = c.head_z_seated_mm if s.seated else c.head_z_standing_mm
            s.head = (s.position[0], s.position[1], z)
        else:
            s.head = None

    def _lose_vitals(self, t_us: int, why: str) -> None:
        self._vitals_ok = False
        self._vitals_good_since = None
        self.state.breath_rate = self.state.heart_rate = None
        self._emit(EventKind.VITALS_LOST, t_us, why)

    def _emit(self, kind: EventKind, t_us: int, detail: str = "", **data) -> None:
        ev = Event(kind, t_us, detail, data)
        self.events.append(ev)
        log.debug("%.2f s %s %s", t_us / 1e6, kind.value, detail)
        for fn in list(self._listeners):
            try:
                fn(ev)
            except Exception:
                log.exception("event listener %r failed", fn)


def _alpha(dt: float, tau: float) -> float:
    """Exponential moving average weight for a sample dt seconds after the previous one."""
    return 1.0 - math.exp(-dt / tau) if tau > 0 else 1.0


def _duration(s: float) -> str:
    if s < 90:
        return f"{s:.0f} s"
    if s < 90 * 60:
        return f"{s / 60:.0f} min"
    return f"{s / 3600:.1f} h"
