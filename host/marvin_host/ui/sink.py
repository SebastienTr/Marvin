"""What the app shows about the robot itself: devices, link quality, and a light picture of the sensors.

``UISink`` is a receiver ``Sink`` that only keeps the latest data and a few counters: every
callback is O(1) (a reference kept, a counter bumped, a short deque appended), with no rendering,
no I/O and no conversion, so it never slows the receiver down. The work happens when someone asks:

- ``tick()`` (about once a second, from ``start()``'s thread or the app's sampler) turns counters
  into rates, notices devices that stop sending (and come back), and passes device logs and
  connections on to listeners (the app's log, the terminal);
- ``devices()`` describes each device for the app (JSON);
- ``scene()`` gives the sensor mini-views: the last lidar scan reduced to 360 ranges (one per
  degree), the LD2450 targets with short trails, and the MR60BHA2 vital signs (rates over the last
  minutes, waves over the last seconds). The scan reduction is cached per scan.

Top view convention (for drawing): ``right`` is to the robot's right, ``fwd`` in front of it
(device frame: right = -X, fwd = -Y); lidar bearings are clockwise from the front, like the
LDROBOT lidars.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Callable

import numpy as np

from .. import protocol
from ..receiver import Sink

log = logging.getLogger("marvin.ui")

OFFLINE_AFTER_S = 6.0       # the robot sends a HELLO every 2 s: three missed heartbeats
RATE_WINDOW_S = 5.0         # rates and loss are averaged over this window
TRAIL_S = 5.0               # LD2450 trail length
TRAIL_STEP_S = 0.2
WAVES_S = 15.0              # vital sign waves kept
RATES_S = 5 * 60.0          # breathing and heart rate history
LIDAR_BINS = 360
RADAR_FOV = {"half_angle_deg": 60, "range_cm": 600}   # HLK-LD2450 field of view, for drawing


def rssi_bars(rssi: int | None) -> int | None:
    """Wi-Fi signal (dBm) as 0-4 bars, None when unknown."""
    if rssi is None:
        return None
    for bars, floor in ((4, -55), (3, -65), (2, -75), (1, -85)):
        if rssi >= floor:
            return bars
    return 0


def device_role(hello: protocol.Hello) -> tuple[str, str]:
    """("robot" | "vitals" | "simulator", a label for people)."""
    if hello.board == 255:
        return "simulator", "Simulated robot"
    if hello.board == 4:
        return "vitals", "Vital signs radar"
    return "robot", "Robot"


def reduce_scan(points: np.ndarray, bins: int = LIDAR_BINS) -> list[int]:
    """Device-frame points (N, 3), mm -> the nearest range in each of `bins` bearings (clockwise
    from the front), in cm; 0 where nothing was seen."""
    out = np.full(bins, np.inf)
    if points is not None and len(points):
        p = np.asarray(points, dtype=np.float64)
        x, y = p[:, 0], p[:, 1]
        bearing = np.degrees(np.arctan2(-x, -y)) % 360.0
        idx = (bearing * bins / 360.0).astype(np.int64) % bins
        np.minimum.at(out, idx, np.hypot(x, y))
    cm = np.where(np.isfinite(out), np.round(out / 10.0), 0)
    return [int(v) for v in cm]


@dataclass
class _Dev:
    key: str
    dev: object
    connected_at: float                     # wall clock
    last_seen: float                        # monotonic
    online: bool = True
    announced: bool = False
    scans: int = 0
    points: int = 0
    radar: int = 0
    vitals: int = 0
    audio: int = 0
    logs: int = 0
    seen_datagrams: int = -1
    history: deque = field(default_factory=lambda: deque(maxlen=64))   # (t, counters)
    rates: dict = field(default_factory=dict)
    loss_pct: float | None = None


def _shed(stats) -> int:
    """Frames the host dropped because it was behind (receiver ``Stats.shed``, by callback)."""
    shed = getattr(stats, "shed", 0) if stats is not None else 0
    return int(sum(shed.values())) if isinstance(shed, dict) else int(shed or 0)


def _key(dev) -> str:
    addr = getattr(dev, "addr", None)
    return f"{addr[0]}:{addr[1]}" if addr else "local"


class UISink(Sink):
    """See the module docstring. ``clock`` (monotonic) and ``wall`` can be replaced in tests."""

    def __init__(self, offline_after_s: float = OFFLINE_AFTER_S, clock: Callable[[], float] = time.monotonic,
                 wall: Callable[[], float] = time.time):
        self.offline_after_s = offline_after_s
        self.clock, self.wall = clock, wall
        self._devs: dict[str, _Dev] = {}
        self._lock = threading.Lock()
        self._notes: deque = deque(maxlen=1000)            # ("connected" | "log", key, text, wall)
        self._scan: tuple | None = None                    # (points, key, t, seq)
        self._scan_seq = 0
        self._scan_cache: tuple[int, list[int]] | None = None
        self._targets: tuple[float, list] | None = None    # (t, [(right, fwd, speed_cms)])
        self._trail: deque = deque(maxlen=int(TRAIL_S / TRAIL_STEP_S) + 1)   # (t, [(right, fwd)])
        self._vitals = None                                # (t, protocol.Vitals)
        self._waves: deque = deque(maxlen=1200)            # (t, breath_wave, heart_wave, valid)
        self._rates: deque = deque(maxlen=int(RATES_S) + 5)   # (t, breath, heart) once a second, None if invalid
        self._listeners: list[Callable[[str, dict], None]] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._devices_json: list[dict] = []

    # ------------------------------------------------------------ Sink (receiver thread: O(1))

    def _dev(self, dev) -> _Dev:
        key = _key(dev)
        st = self._devs.get(key)
        if st is None or st.dev is not dev:
            now = self.clock()
            with self._lock:
                st = self._devs[key] = _Dev(key, dev, self.wall(), now)
            self._notes.append(("connected", key, "", self.wall()))
        return st

    def on_hello(self, dev) -> None:
        self._dev(dev)

    def on_scan(self, dev, t_us, points, intensities, speed_dps) -> None:
        st = self._dev(dev)
        st.scans += 1
        st.points = len(points)
        self._scan_seq += 1
        self._scan = (points, st.key, self.clock(), self._scan_seq)

    def on_targets(self, dev, t_us, targets, points) -> None:
        st = self._dev(dev)
        st.radar += 1
        now = self.clock()
        pts = [(-p[0], -p[1], t.speed_cms) for p, t in zip(points, targets)]
        self._targets = (now, pts)
        if not self._trail or now - self._trail[-1][0] >= TRAIL_STEP_S:
            self._trail.append((now, [(r, f) for r, f, _ in pts]))

    def on_vitals(self, dev, t_us, vitals) -> None:
        st = self._dev(dev)
        st.vitals += 1
        now = self.clock()
        self._vitals = (now, vitals)
        self._waves.append((now, vitals.breath_wave, vitals.heart_wave, vitals.valid))
        if not self._rates or now - self._rates[-1][0] >= 1.0:
            ok = vitals.valid
            self._rates.append((now, vitals.breath_rate if ok else None, vitals.heart_rate if ok else None))

    def on_log(self, dev, t_us, text) -> None:
        st = self._dev(dev)
        st.logs += 1
        self._notes.append(("log", st.key, text, self.wall()))

    def on_audio(self, dev, t_us, seq, pcm) -> None:
        self._dev(dev).audio += 1

    # ------------------------------------------------------------ listeners and ticking

    def add_listener(self, fn: Callable[[str, dict], None]) -> None:
        """``fn(kind, info)`` from ``tick()``: "connected", "disconnected", "reconnected" (info: the
        device, as in ``devices()``) and "log" (info: {"device", "text", "ts"})."""
        self._listeners.append(fn)

    def remove_listener(self, fn) -> None:
        if fn in self._listeners:
            self._listeners.remove(fn)

    def _notify(self, kind: str, info: dict) -> None:
        for fn in list(self._listeners):
            try:
                fn(kind, info)
            except Exception:                   # noqa: BLE001
                log.exception("ui sink listener failed")

    def start(self, period_s: float = 1.0) -> UISink:
        """Ticks in a background thread."""
        def run():
            while not self._stop.wait(period_s):
                try:
                    self.tick()
                except Exception:               # noqa: BLE001
                    log.exception("ui sink tick failed")
        self._thread = threading.Thread(target=run, name="ui-sink", daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2)
        self.tick()

    def tick(self) -> None:
        now = self.clock()
        with self._lock:
            devs = list(self._devs.values())
        events = []
        for st in devs:
            s = getattr(st.dev, "stats", None)
            n = getattr(s, "datagrams", 0) if s is not None else st.scans + st.radar + st.vitals + st.audio
            if n != st.seen_datagrams:
                st.seen_datagrams = n
                st.last_seen = now
            counters = (n, st.scans, st.radar, st.vitals, st.audio, getattr(s, "lost", 0) if s else 0)
            st.history.append((now, counters))
            while len(st.history) > 2 and now - st.history[1][0] >= RATE_WINDOW_S:
                st.history.popleft()
            t0, c0 = st.history[0]
            dt = now - t0
            if dt > 0.5:
                d = [b - a for a, b in zip(c0, counters)]
                st.rates = {k: round(v / dt, 1) for k, v in zip(("datagrams", "scans", "radar", "vitals", "audio"), d)}
                st.loss_pct = round(100.0 * d[5] / (d[0] + d[5]), 1) if d[0] + d[5] > 0 else None
            online = now - st.last_seen < self.offline_after_s
            if online != st.online:
                st.online = online
                if st.announced:
                    events.append(("reconnected" if online else "disconnected", st.key))
        self._devices_json = [self._describe(st, now) for st in devs]
        by_key = {d["id"]: d for d in self._devices_json}
        while self._notes:
            kind, key, text, ts = self._notes.popleft()
            if kind == "connected":
                st = self._devs.get(key)
                if st is not None:
                    st.announced = True
                if key in by_key:
                    self._notify("connected", by_key[key])
            else:
                d = by_key.get(key, {})
                self._notify("log", {"device": d.get("name", key), "text": text, "ts": ts})
        for kind, key in events:
            self._notify(kind, by_key[key])

    # ------------------------------------------------------------ for the app

    def _describe(self, st: _Dev, now: float) -> dict:
        dev = st.dev
        hello = getattr(dev, "hello", None)
        s = getattr(dev, "stats", None)
        role, label = device_role(hello) if hello is not None else ("robot", "Robot")
        rssi = getattr(hello, "rssi", None) if hello is not None else None
        rssi = None if rssi in (None, 0) or role == "simulator" else int(rssi)
        addr = getattr(dev, "addr", None)
        return {
            "id": st.key,
            "name": hello.device_name if hello is not None else "marvin",
            "role": role, "label": label,
            "board": protocol.BOARDS.get(hello.board, f"board {hello.board}") if hello is not None else None,
            "firmware": hello.firmware if hello is not None else None,
            "ip": addr[0] if addr else None,
            "rssi": rssi, "rssi_bars": rssi_bars(rssi),
            "uptime_s": round(hello.uptime_ms / 1000) if hello is not None else None,
            "simulated": bool(hello is not None and hello.simulated),
            "camera": bool(hello is not None and hello.has_camera),
            "audio": bool(hello is not None and hello.has_audio),
            "online": st.online,
            "age_s": round(max(0.0, now - st.last_seen), 1),
            "connected_at": st.connected_at,
            "rates": dict(st.rates),
            "loss_pct": st.loss_pct,
            "datagrams": getattr(s, "datagrams", 0) if s is not None else 0,
            "lost": getattr(s, "lost", 0) if s is not None else 0,
            "crc_errors": getattr(s, "crc_errors", 0) if s is not None else 0,
            "bad": getattr(s, "bad", 0) if s is not None else 0,
            "shed": _shed(s),
            "points": st.points,
        }

    def devices(self) -> list[dict]:
        """Every device seen since start, as of the last ``tick()``."""
        return list(self._devices_json)

    def online(self) -> bool:
        return any(d["online"] for d in self._devices_json)

    def scene(self, history: bool = True) -> dict:
        """The sensor mini-views (see the module docstring). ``history``: include the vital sign
        rates over the last minutes (larger; the app asks for them once a second)."""
        now = self.clock()
        out: dict = {"lidar": None, "targets": [], "trail": [], "vitals": None, "radar": RADAR_FOV}
        scan = self._scan
        if scan is not None:
            points, key, t, seq = scan
            cache = self._scan_cache
            if cache is None or cache[0] != seq:
                cache = self._scan_cache = (seq, reduce_scan(points))
            out["lidar"] = {"age_s": round(now - t, 2), "ranges_cm": cache[1], "points": len(points)}
        tg = self._targets
        if tg is not None and now - tg[0] < 2.0:
            out["targets"] = [[round(r / 10), round(f / 10), int(v)] for r, f, v in tg[1]]
        out["trail"] = [[round(now - t, 1), round(r / 10), round(f / 10)]
                        for t, pts in list(self._trail) if now - t <= TRAIL_S for r, f in pts]
        vt = self._vitals
        if vt is not None and now - vt[0] < 10.0:
            v = vt[1]
            waves = [[round(now - t, 2), round(b, 3), round(h, 3)] for t, b, h, ok in list(self._waves)
                     if ok and now - t <= WAVES_S]
            out["vitals"] = {
                "valid": bool(v.valid),
                "breath_rate": round(v.breath_rate, 1) if v.valid else None,
                "heart_rate": round(v.heart_rate, 1) if v.valid else None,
                "distance_cm": round(v.distance_mm / 10) if v.distance_mm else None,
                "waves": waves,
            }
            if history:
                out["vitals"]["rates"] = [[round(now - t), None if b is None else round(b, 1),
                                           None if h is None else round(h, 1)]
                                          for t, b, h in list(self._rates) if now - t <= RATES_S]
        return out


__all__ = ["UISink", "reduce_scan", "rssi_bars", "device_role", "RADAR_FOV"]
