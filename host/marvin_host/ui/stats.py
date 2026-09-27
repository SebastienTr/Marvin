"""Daily statistics from stored events: pure functions, no I/O, no clock.

The brain's events (events.py) are stamped with the robot's clock. The event store stamps each one
with the host's wall clock (Unix seconds) when it arrives, and adds a few events of its own to
mark when the history has holes:

    host_started   marvin-host started (after a stop or a crash)
    host_stopped   marvin-host stopped cleanly
    robot_offline  no sensor frame for a while (robot unplugged, Wi-Fi lost)
    robot_online   frames again; ``data`` says whether someone is present / seated right now

From that stream, ``fold`` rebuilds the intervals when someone was present and when they were
seated, and ``day_stats`` clips them to one local day and summarises them. An interval left open
by a crash is closed at the last sign of life before the restart (last event or vitals sample).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
from bisect import bisect_right
from dataclasses import dataclass, field
from datetime import date, datetime, time as dtime, timedelta
from typing import Iterable, Sequence

from ..events import EventKind

HOST_STARTED = "host_started"
HOST_STOPPED = "host_stopped"
ROBOT_OFFLINE = "robot_offline"
ROBOT_ONLINE = "robot_online"
SYSTEM_KINDS = (HOST_STARTED, HOST_STOPPED, ROBOT_OFFLINE, ROBOT_ONLINE)
VITALS_KINDS = (EventKind.VITALS_ACQUIRED.value, EventKind.VITALS_LOST.value)

MIN_BREAK_S = 60.0          # standing up for less than this does not end a sitting session


@dataclass(frozen=True)
class StoredEvent:
    """An event as stored: wall-clock time (Unix seconds) and the kind as a string."""

    ts: float
    kind: str
    detail: str = ""
    data: dict = field(default_factory=dict)
    id: int = 0

    def to_json(self) -> dict:
        return {"id": self.id, "ts": self.ts, "kind": self.kind, "detail": self.detail,
                "data": self.data, "text": describe(self)}


@dataclass
class Folded:
    present: list[tuple[float, float]]      # (start, end), wall clock
    seated: list[tuple[float, float]]
    reminders: list[float]                  # STILL_LONG times
    open_present: bool = False              # the last present interval is still going on
    open_seated: bool = False


# ------------------------------------------------------------------------------ intervals

def fold(events: Iterable[StoredEvent], now: float, live: bool = True,
         alive: Sequence[float] = ()) -> Folded:
    """Present / seated intervals from a time-ordered event stream.

    ``now`` closes intervals still open at the end when ``live`` (the host is running right now);
    otherwise they are closed at the last sign of life. ``alive`` are extra timestamps proving the
    host was running (the per-minute samples), used to close intervals cut short by a crash.
    """
    evs = sorted(events, key=lambda e: (e.ts, e.id))
    alive = sorted(alive)
    out = Folded([], [], [])
    p_start: float | None = None
    s_start: float | None = None
    last_ts: float | None = None            # last sign of life of the current run

    def last_alive(before: float) -> float:
        t = last_ts if last_ts is not None else before
        i = bisect_right(alive, before) - 1
        if i >= 0 and alive[i] > t:
            t = alive[i]
        return min(t, before)

    def close(at: float) -> None:
        nonlocal p_start, s_start
        if s_start is not None:
            out.seated.append((s_start, max(s_start, at)))
            s_start = None
        if p_start is not None:
            out.present.append((p_start, max(p_start, at)))
            p_start = None

    for e in evs:
        k = e.kind
        if k == HOST_STARTED:
            close(last_alive(e.ts))
        elif k in (HOST_STOPPED, ROBOT_OFFLINE):
            close(e.ts)
        elif k == ROBOT_ONLINE:                 # intervals were closed when it went offline
            if e.data.get("present") and p_start is None:
                p_start = e.ts
            if e.data.get("seated") and s_start is None:
                s_start = e.ts
        elif k == EventKind.ARRIVED.value:
            if p_start is None:
                p_start = e.ts
        elif k == EventKind.LEFT.value:
            close(e.ts)
        elif k == EventKind.SAT_DOWN.value:
            if p_start is None:             # a seated person is present, even if ARRIVED was missed
                p_start = e.ts
            if s_start is None:
                s_start = e.ts
        elif k == EventKind.STOOD_UP.value:
            if s_start is not None:
                out.seated.append((s_start, max(s_start, e.ts)))
                s_start = None
        elif k == EventKind.STILL_LONG.value:
            out.reminders.append(e.ts)
        last_ts = e.ts

    if p_start is not None or s_start is not None:
        out.open_present, out.open_seated = p_start is not None, s_start is not None
        end = now if live else last_alive(now)
        close(end)
        if not live:
            out.open_present = out.open_seated = False
    return out


def merge(intervals: Sequence[tuple[float, float]], gap: float) -> list[tuple[float, float]]:
    """Joins intervals separated by less than ``gap`` seconds."""
    out: list[list[float]] = []
    for a, b in sorted(intervals):
        if out and a - out[-1][1] < gap:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return [(a, b) for a, b in out]


def clip(intervals: Sequence[tuple[float, float]], lo: float, hi: float) -> list[tuple[float, float]]:
    return [(max(a, lo), min(b, hi)) for a, b in intervals if b > lo and a < hi and min(b, hi) > max(a, lo)]


def total(intervals: Sequence[tuple[float, float]]) -> float:
    return sum(b - a for a, b in intervals)


# ------------------------------------------------------------------------------ days

def day_bounds(day: date) -> tuple[float, float]:
    """Start and end of a local calendar day, Unix seconds (23 or 25 h long around DST changes)."""
    start = datetime.combine(day, dtime()).astimezone()
    end = datetime.combine(day + timedelta(days=1), dtime()).astimezone()
    return start.timestamp(), end.timestamp()


def local_day(ts: float) -> date:
    return datetime.fromtimestamp(ts).date()


def day_stats(events: Iterable[StoredEvent], day: date, now: float, live: bool = True,
              samples: Sequence[tuple[float, float | None, float | None]] = (),
              min_break_s: float = MIN_BREAK_S) -> dict:
    """What happened on one local day.

    ``events`` must start early enough to know the state at midnight (the store passes the last
    36 hours before the day). ``samples`` are the per-minute (ts, breath, heart) averages; breath
    and heart are None when not reliable. Durations in seconds, times in Unix seconds.

    - ``seated_s``: time at the desk, SAT_DOWN .. STOOD_UP / LEFT;
    - ``sessions``: sitting sessions, joining those separated by less than ``min_break_s``;
    - ``breaks``: the gaps between two sessions of the day, with ``break_s`` their total;
    - ``longest_s``: the longest session;
    - ``first_arrival`` / ``last_departure``: None if nobody came, or if they are still there.
    """
    lo, hi = day_bounds(day)
    end = min(hi, now)
    samples = list(samples)
    f = fold(events, now, live, alive=[s[0] for s in samples])
    present = clip(f.present, lo, end) if end > lo else []
    seated = clip(f.seated, lo, end) if end > lo else []
    sessions = merge(seated, min_break_s)
    gaps = [(a[1], b[0]) for a, b in zip(sessions, sessions[1:])]
    here_now = live and f.open_present and lo <= now < hi

    vit = [(b, h) for ts, b, h in samples if lo <= ts < hi and b is not None and h is not None]
    return {
        "date": day.isoformat(),
        "start": lo,
        "end": hi,
        "now": now if lo <= now < hi else None,
        "present_s": round(total(present), 1),
        "seated_s": round(total(seated), 1),
        "sessions": len(sessions),
        "breaks": len(gaps),
        "break_s": round(total(gaps), 1),
        "longest_s": round(max((b - a for a, b in sessions), default=0.0), 1),
        "reminders": len([t for t in f.reminders if lo <= t < end]),
        "first_arrival": present[0][0] if present else None,
        "last_departure": None if not present or here_now else present[-1][1],
        "breath_rate": round(sum(b for b, _ in vit) / len(vit), 1) if vit else None,
        "heart_rate": round(sum(h for _, h in vit) / len(vit), 1) if vit else None,
        "timeline": {
            "present": [[round(a, 1), round(b, 1)] for a, b in present],
            "seated": [[round(a, 1), round(b, 1)] for a, b in seated],
            "breaks": [[round(a, 1), round(b, 1)] for a, b in gaps],
            "reminders": [round(t, 1) for t in f.reminders if lo <= t < end],
        },
    }


# ------------------------------------------------------------------------------ words

def duration(s: float) -> str:
    """42 s, 12 min, 1 h 05, 3 h."""
    s = max(0.0, s)
    if s < 60:
        return f"{s:.0f} s"
    m = int(s // 60)
    if m < 60:
        return f"{m} min"
    h, m = divmod(m, 60)
    return f"{h} h {m:02d}" if m else f"{h} h"


def describe(e: StoredEvent) -> str:
    """One short sentence for the events list."""
    k, d = e.kind, e.data
    if k == EventKind.ARRIVED.value:
        return "You came in"
    if k == EventKind.LEFT.value:
        return "Marvin lost track of you (sensor restarted)" if e.detail == "sensor restarted" else "You left"
    if k == EventKind.APPROACHED.value:
        return "You came close to Marvin"
    if k == EventKind.SAT_DOWN.value:
        return "You sat down"
    if k == EventKind.STOOD_UP.value:
        s = d.get("seated_s")
        return f"You stood up after {duration(s)}" if isinstance(s, (int, float)) else "You stood up"
    if k == EventKind.STILL_LONG.value:
        s = d.get("seated_s")
        return (f"Time for a break: seated for {duration(s)}" if isinstance(s, (int, float))
                else "Time for a break")
    if k == EventKind.VITALS_ACQUIRED.value:
        b, h = d.get("breath_rate"), d.get("heart_rate")
        if isinstance(b, (int, float)) and isinstance(h, (int, float)):
            return f"Breathing {b:.0f}/min, heart {h:.0f}/min"
        return "Vital signs measured"
    if k == EventKind.VITALS_LOST.value:
        return f"Vital signs paused ({e.detail})" if e.detail else "Vital signs paused"
    if k == HOST_STARTED:
        return "Marvin started"
    if k == HOST_STOPPED:
        return "Marvin stopped"
    if k == ROBOT_OFFLINE:
        return "Lost contact with the robot"
    if k == ROBOT_ONLINE:
        return "Connected to the robot"
    return e.detail or k.replace("_", " ").capitalize()


def status(online: bool, present: bool, seated: bool, seated_s: float, away_s: float | None,
           breath: float | None, heart: float | None, break_s: float, ever_online: bool = True) -> dict:
    """The one-line status sentence at the top of the app, and an optional second line."""
    vitals = ""
    if breath is not None and heart is not None and math.isfinite(breath) and math.isfinite(heart):
        vitals = f"Breathing {breath:.0f}/min, heart {heart:.0f}/min"
    if not online:
        text = "Marvin is offline" if ever_online else "Waiting for Marvin to connect"
        return {"text": text, "detail": "", "mood": "offline"}
    if seated:
        if seated_s >= break_s:
            text = f"You've been sitting for {duration(seated_s)}. Time to stretch your legs."
            return {"text": text, "detail": vitals, "mood": "break"}
        text = ("You just sat down" if seated_s < 60
                else f"You've been at your desk for {duration(seated_s)}")
        return {"text": text, "detail": vitals, "mood": "seated"}
    if present:
        return {"text": "You're here, up and about", "detail": vitals, "mood": "present"}
    if away_s is not None and away_s < 60:
        return {"text": "You just left. Marvin is keeping an eye out.", "detail": "", "mood": "away"}
    return {"text": "Marvin is asleep. Nobody around.", "detail": "", "mood": "asleep"}

