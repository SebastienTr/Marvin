"""A simulated robot for the app: the brain fed by the simulated room, and a plausible past week.

``DemoRobot`` steps the simulator (sim.py / scene.py) frame by frame into a real ``Brain``, like
the brain tests do, with no sockets. The scene's person only sits for half a minute, so the demo
stretches each visit: the person sits for many minutes (swaying gently, vital signs readable),
then leaves for a while, and comes back. Time can run faster than real time (``speed``); the
demo's ``clock`` runs at the same pace, so the app's durations and timeline follow.

``seed_history`` writes a believable past week (and today until now) into a store, so the day
timeline and the week chart have something to show. Only ever use it on a throwaway database.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
import random
import threading
import time
from datetime import datetime, time as dtime, timedelta

from .. import frames, scene, sim
from ..events import EventKind as K
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

    def __init__(self, brain, speed: float = 10.0, visits=VISITS, wall0: float | None = None):
        self.brain = brain
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
        for t in self.scene_times():
            while not self._stop.is_set() and (time.monotonic() - self.m0) * self.speed < v:
                time.sleep(0.01)
            if self._stop.is_set():
                return
            step(self.brain, t, v)
            v += STEP_S


def step(brain, scene_t: float, device_t: float, dev=None) -> None:
    """One LD2450 frame and one vitals frame of the simulated room, stamped with ``device_t``."""
    t_us = round(device_t * 1e6)
    targets = sim.ld2450_targets(scene_t)
    brain.on_targets(dev, t_us, targets, [frames.ld2450_to_device(g.x_mm, g.y_mm) for g in targets])
    brain.on_vitals(dev, t_us, sim.vitals(scene_t))


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
