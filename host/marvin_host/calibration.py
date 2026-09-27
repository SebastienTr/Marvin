"""Per-robot calibration: stored once, applied at every host start.

The file is `~/.config/marvin/calibration.json` (the directory can be changed with the
MARVIN_CONFIG_DIR environment variable). Keys it does not know are kept when it is rewritten.

    {
      "version": 1,
      "lidar":  {"yaw_deg": 0.0},                    frames.LIDAR.yaw_deg: the lidar angle that points to the front
      "ld2450": {"x_sign": 1, "speed_sign": -1},     frames.LD2450.x_sign, BrainConfig.radar_speed_sign
      "camera": {"pitch_deg": null},                 not used yet
      "updated": "2026-09-27T18:00:00+02:00"
    }

Lidar yaw: the D800's 0 deg mark is wherever the lidar happened to be screwed in. Two ways to find it:

- automatic (`marvin-host calibrate lidar`): someone walks around in front of the robot for a
  while. The LD2450 gives the person's bearing in the robot frame (it is mounted facing the front),
  the lidar sees a person-sized cluster at the same range; the yaw is the robust circular mean of
  the angle between the two over many frames (a histogram mode first, then the mean of the angles
  near it), which throws out the frames where the lidar cluster was a chair or a door frame.
- manual (`--manual`): stand straight in front of the robot at about 1 m, nothing else within
  1.5 m; the nearest person-sized lidar cluster is taken as the front.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import logging
import math
import os
import threading
import time
from collections import deque
from dataclasses import dataclass, field, replace
from pathlib import Path

import numpy as np

from . import frames, protocol
from .brain import BrainConfig
from .receiver import Receiver, Sink

log = logging.getLogger("marvin.calibration")

ENV_DIR = "MARVIN_CONFIG_DIR"
FILE_NAME = "calibration.json"
VERSION = 1


# ------------------------------------------------------------------------------------------ file

def config_dir() -> Path:
    """$MARVIN_CONFIG_DIR, else $XDG_CONFIG_HOME/marvin, else ~/.config/marvin."""
    if os.environ.get(ENV_DIR):
        return Path(os.environ[ENV_DIR]).expanduser()
    base = os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config"
    return Path(base) / "marvin"


def default_path() -> Path:
    return config_dir() / FILE_NAME


@dataclass
class Calibration:
    lidar_yaw_deg: float = 0.0
    ld2450_x_sign: int = 1
    radar_speed_sign: int = -1
    camera_pitch_deg: float | None = None
    raw: dict = field(default_factory=dict, repr=False)     # the file as read, unknown keys included

    @classmethod
    def from_dict(cls, d: dict) -> Calibration:
        lidar, radar, cam = d.get("lidar") or {}, d.get("ld2450") or {}, d.get("camera") or {}
        c = cls(float(lidar.get("yaw_deg", 0.0)) % 360.0, int(radar.get("x_sign", 1)), int(radar.get("speed_sign", -1)),
                None if cam.get("pitch_deg") is None else float(cam["pitch_deg"]), raw=d)
        if not math.isfinite(c.lidar_yaw_deg):
            raise ValueError("lidar.yaw_deg must be a number")
        if c.ld2450_x_sign not in (1, -1) or c.radar_speed_sign not in (1, -1):
            raise ValueError("ld2450.x_sign and ld2450.speed_sign must be 1 or -1")
        return c

    def to_dict(self) -> dict:
        d = json.loads(json.dumps(self.raw))            # deep copy, keeps what we do not know about
        d["version"] = VERSION
        d.setdefault("lidar", {})["yaw_deg"] = round(self.lidar_yaw_deg % 360.0, 2)
        d.setdefault("ld2450", {}).update(x_sign=self.ld2450_x_sign, speed_sign=self.radar_speed_sign)
        d.setdefault("camera", {})["pitch_deg"] = self.camera_pitch_deg
        return d

    def apply(self) -> None:
        """Set the sensor extrinsics used by the receiver, the viewer and the simulator (frames.py)."""
        frames.LIDAR.yaw_deg = self.lidar_yaw_deg
        frames.LD2450.x_sign = self.ld2450_x_sign

    def brain_config(self, base: BrainConfig | None = None) -> BrainConfig:
        """A BrainConfig with the calibrated radar speed sign."""
        return replace(base or BrainConfig(), radar_speed_sign=self.radar_speed_sign)


def load(path: str | os.PathLike | None = None, apply: bool = True) -> Calibration:
    """Read the calibration file and (by default) apply it. No file or an unreadable one: defaults,
    nothing applied (a warning for an unreadable one)."""
    p = Path(path) if path else default_path()
    try:
        cal = Calibration.from_dict(json.loads(p.read_text()))
    except FileNotFoundError:
        return Calibration()
    except (OSError, ValueError, TypeError, AttributeError) as e:
        log.warning("ignoring calibration file %s: %s", p, e)
        return Calibration()
    if apply:
        cal.apply()
        log.info("calibration from %s: lidar yaw %.1f deg, LD2450 x sign %+d", p, cal.lidar_yaw_deg, cal.ld2450_x_sign)
    return cal


def save(cal: Calibration, path: str | os.PathLike | None = None) -> Path:
    """Write atomically; keys already in the file that this version does not know are kept."""
    p = Path(path) if path else default_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    d = cal.to_dict()
    d["updated"] = _dt.datetime.now().astimezone().isoformat(timespec="seconds")
    tmp = p.with_name(p.name + ".tmp")
    tmp.write_text(json.dumps(d, indent=2) + "\n")
    os.replace(tmp, p)
    return p


# ------------------------------------------------------------------------------------------ geometry

def wrap180(a):
    """Angle(s) in degrees to [-180, 180)."""
    return (np.asarray(a, dtype=np.float64) + 180.0) % 360.0 - 180.0


def bearing(x, y):
    """Bearing in the lidar convention (clockwise from above, 0 = the robot's front, -Y), degrees."""
    return np.degrees(np.arctan2(-np.asarray(x, dtype=np.float64), -np.asarray(y, dtype=np.float64)))


@dataclass
class Cluster:
    bearing_deg: float        # of the centroid, device frame, lidar convention
    range_mm: float           # median range of its points, from the lidar axis
    width_mm: float           # first to last point
    n: int


def clusters(points: np.ndarray, gap_mm: float = 150.0, min_points: int = 3) -> list[Cluster]:
    """Split a scan (device-frame points) into clusters of neighbouring points, in bearing order."""
    if len(points) < min_points:
        return []
    x, y = points[:, 0].astype(np.float64), points[:, 1].astype(np.float64)
    order = np.argsort(bearing(x, y))
    x, y = x[order], y[order]
    step = np.hypot(np.diff(x, append=x[:1]), np.diff(y, append=y[:1]))   # i -> i+1, the last one wraps
    breaks = np.flatnonzero(step > gap_mm)
    if len(breaks) == 0:
        return []                                  # one closed ring: nothing stands out
    start = breaks[-1] + 1                         # begin right after a gap, so no cluster straddles the wrap
    x, y, step = np.roll(x, -start), np.roll(y, -start), np.roll(step, -start)
    out = []
    ends = np.flatnonzero(step > gap_mm)
    i0 = 0
    for e in ends:
        n = e - i0 + 1
        if n >= min_points:
            cx, cy = x[i0:e + 1], y[i0:e + 1]
            out.append(Cluster(float(bearing(cx.mean(), cy.mean())), float(np.median(np.hypot(cx, cy))),
                               float(math.hypot(cx[-1] - cx[0], cy[-1] - cy[0])), int(n)))
        i0 = e + 1
    return out


def person_sized(c: Cluster, min_mm: float = 120.0, max_mm: float = 800.0) -> bool:
    return min_mm <= c.width_mm <= max_mm


@dataclass
class Estimate:
    angle_deg: float          # [0, 360)
    spread_deg: float         # standard deviation of the inliers
    inliers: int
    samples: int

    @property
    def inlier_ratio(self) -> float:
        return self.inliers / self.samples if self.samples else 0.0


def robust_circular_mean(angles_deg, bin_deg: float = 2.0, windows=(12.0, 6.0, 4.0)) -> Estimate | None:
    """Circular mean with outlier rejection: the histogram mode, then the mean of the angles within
    each window around the current estimate, narrowing."""
    a = np.asarray(angles_deg, dtype=np.float64) % 360.0
    if len(a) == 0:
        return None
    nb = int(round(360.0 / bin_deg))
    hist = np.bincount((a / (360.0 / nb)).astype(int) % nb, minlength=nb).astype(float)
    k = np.array([1.0, 2.0, 3.0, 2.0, 1.0])
    smooth = sum(w * np.roll(hist, s) for w, s in zip(k, range(-2, 3)))
    center = (np.argmax(smooth) + 0.5) * 360.0 / nb
    inl = np.ones(len(a), bool)
    for w in windows:
        inl = np.abs(wrap180(a - center)) <= w
        if not inl.any():
            return None
        r = np.radians(a[inl])
        center = math.degrees(math.atan2(np.sin(r).mean(), np.cos(r).mean())) % 360.0
    spread = float(np.sqrt(np.mean(wrap180(a[inl] - center) ** 2)))
    return Estimate(center, spread, int(inl.sum()), len(a))


# ------------------------------------------------------------------------------------------ estimators

class LidarYawEstimator(Sink):
    """Automatic lidar yaw from a person walking in front of the robot (LD2450 bearing vs lidar cluster).

    Feed it frames (it is a receiver Sink), then call `estimate()`. `yaw_deg` is frames.LIDAR.yaw_deg
    when the frames were converted (the receiver uses frames.LIDAR at conversion time).
    """

    def __init__(self, yaw_deg: float | None = None, range_tol_mm: float = 400.0, min_range_mm: float = 400.0,
                 max_range_mm: float = 5000.0, max_rate_dps: float = 60.0, max_gap_s: float = 0.25):
        self.yaw_deg = frames.LIDAR.yaw_deg if yaw_deg is None else yaw_deg
        self.range_tol_mm, self.min_range_mm, self.max_range_mm = range_tol_mm, min_range_mm, max_range_mm
        self.max_rate_dps, self.max_gap_s = max_rate_dps, max_gap_s
        self.radar: deque[tuple[float, float, float]] = deque(maxlen=64)   # (t s, x, y), device frame
        self.diffs: list[float] = []
        self.frames = 0                     # scans paired with a radar target
        self.t_first: float | None = None
        self.t_last: float | None = None

    @property
    def elapsed_s(self) -> float:
        return 0.0 if self.t_first is None else self.t_last - self.t_first

    def _clock(self, t: float) -> None:
        if self.t_last is not None and t < self.t_last - 1.0:     # device restarted (or a replay looped)
            self.radar.clear()
            self.t_first = None
        if self.t_first is None:
            self.t_first = t
        self.t_last = t

    def on_targets(self, dev, t_us, targets, points) -> None:
        t = t_us / 1e6
        self._clock(t)
        if len(points) == 1:                 # several people: which one is which in the lidar is ambiguous
            self.radar.append((t, float(points[0][0]), float(points[0][1])))
        else:
            self.radar.clear()

    def _radar_at(self, t: float) -> tuple[float, float, float] | None:
        """Radar target (x, y) interpolated at t, and its bearing rate (deg/s)."""
        r = self.radar
        for (t0, x0, y0), (t1, x1, y1) in zip(r, list(r)[1:]):
            if t0 <= t <= t1 and t1 - t0 <= self.max_gap_s:
                k = (t - t0) / (t1 - t0) if t1 > t0 else 0.0
                rate = float(wrap180(bearing(x1, y1) - bearing(x0, y0))) / max(t1 - t0, 1e-3)
                return x0 + (x1 - x0) * k, y0 + (y1 - y0) * k, rate
        return None

    def on_scan(self, dev, t_us, points, intensities, speed_dps) -> None:
        t_end = t_us / 1e6
        self._clock(t_end)
        period = 360.0 / speed_dps if speed_dps > 0 else 0.1
        r = self._radar_at(t_end - period / 2)          # the scan covers the last revolution
        if r is None:
            return
        x, y, rate = r
        if abs(rate) > self.max_rate_dps:
            return
        rng = math.hypot(x, y)
        if not self.min_range_mm <= rng <= self.max_range_mm:
            return
        b_radar = float(bearing(x, y))
        found = False
        for c in clusters(points):
            if person_sized(c) and abs(c.range_mm - rng) <= self.range_tol_mm:
                self.diffs.append(float(wrap180(c.bearing_deg - b_radar)))
                found = True
        self.frames += found

    def estimate(self) -> Estimate | None:
        """The lidar yaw (frames.LIDAR.yaw_deg to use), or None without data."""
        e = robust_circular_mean(self.diffs)
        if e is None:
            return None
        e.angle_deg = (self.yaw_deg + e.angle_deg) % 360.0
        return e


class ManualLidarYaw(Sink):
    """Manual lidar yaw: the nearest person-sized cluster within [min, max] range is the front."""

    def __init__(self, yaw_deg: float | None = None, min_range_mm: float = 400.0, max_range_mm: float = 1600.0):
        self.yaw_deg = frames.LIDAR.yaw_deg if yaw_deg is None else yaw_deg
        self.min_range_mm, self.max_range_mm = min_range_mm, max_range_mm
        self.angles: list[float] = []       # raw lidar angles of the chosen cluster, one per scan
        self.frames = 0
        self.t_first: float | None = None
        self.t_last: float | None = None

    @property
    def elapsed_s(self) -> float:
        return 0.0 if self.t_first is None else self.t_last - self.t_first

    def on_scan(self, dev, t_us, points, intensities, speed_dps) -> None:
        t = t_us / 1e6
        if self.t_first is None or t < self.t_first:
            self.t_first = t
        self.t_last = t
        near = [c for c in clusters(points) if person_sized(c) and self.min_range_mm <= c.range_mm <= self.max_range_mm]
        if near:
            c = min(near, key=lambda c: c.range_mm)
            self.angles.append((c.bearing_deg + self.yaw_deg) % 360.0)
            self.frames += 1

    def estimate(self) -> Estimate | None:
        return robust_circular_mean(self.angles)


# ------------------------------------------------------------------------------------------ command line

MIN_INLIERS = 20


def add_cli(subparsers) -> argparse.ArgumentParser:
    """`marvin-host calibrate lidar [--manual] [--seconds N] [--save] [--from FILE.mvrec] [--port P]`."""
    p = subparsers.add_parser("calibrate", help="calibrate the sensors (lidar yaw)")
    p.add_argument("what", choices=["lidar"], help="what to calibrate")
    p.add_argument("--manual", action="store_true",
                   help="stand straight in front of the robot at ~1 m instead of walking around")
    p.add_argument("--seconds", type=float, help="how long to measure (default 30 live, the whole file with --from)")
    p.add_argument("--save", action="store_true", help=f"write the result to the calibration file ({default_path()})")
    p.add_argument("--from", dest="source", metavar="FILE.mvrec", help="use a recording instead of the live robot")
    p.add_argument("--port", type=int, default=protocol.HOST_PORT)
    return p


def run_cli(args: argparse.Namespace) -> Estimate | None:
    cal = load(apply=False)
    est = ManualLidarYaw() if args.manual else LidarYawEstimator()
    seconds = args.seconds if args.seconds is not None else (None if args.source else 30.0)
    if args.manual:
        print("Stand straight in front of the robot, about 1 m away; keep the space within 1.5 m clear.")
    else:
        print("Walk slowly in front of the robot (within 60 deg of its front, 0.5 to 4 m away), alone.")
    stop = threading.Event()

    class _Progress(Sink):
        def __init__(self):
            self.next = time.monotonic() + 1.0

        def _tick(self):
            if seconds is not None and est.elapsed_s >= seconds:
                stop.set()
            if not args.source and time.monotonic() >= self.next:
                self.next += 1.0
                e = est.estimate()
                cur = f", estimate {e.angle_deg:.1f} deg (+/- {e.spread_deg:.1f}, {e.inliers} inliers)" if e else ""
                print(f"  {est.elapsed_s:4.0f} s, {est.frames} usable scans{cur}")

        def on_hello(self, dev):
            print(f"{dev.hello.device_name} connected, measuring...")

        def on_scan(self, dev, t_us, points, intensities, speed_dps):
            est.on_scan(dev, t_us, points, intensities, speed_dps)
            self._tick()

        def on_targets(self, dev, t_us, targets, points):
            est.on_targets(dev, t_us, targets, points)

    sink = _Progress()
    if args.source:
        from . import record
        record.replay(args.source, sink, speed=None, stop=stop)
    else:
        print(f"listening on UDP {args.port}...")
        Receiver(sink, port=args.port).serve(stop=stop)

    e = est.estimate()
    if e is None or e.inliers < MIN_INLIERS:
        print(f"not enough data ({0 if e is None else e.inliers} usable measurements, {MIN_INLIERS} needed): "
              "nothing saved. Is someone in front of the robot?")
        return None
    print(f"lidar yaw {e.angle_deg:.1f} deg (+/- {e.spread_deg:.1f} deg, {e.inliers}/{e.samples} measurements kept), "
          f"was {cal.lidar_yaw_deg:.1f} deg")
    if e.spread_deg > 5.0 or e.inlier_ratio < 0.3:
        print("  warning: noisy result. Walk alone and slowly; if it stays noisy, check the LD2450 x sign.")
    if args.save:
        cal.lidar_yaw_deg = e.angle_deg
        print(f"saved to {save(cal)}")
    else:
        print("not saved (add --save)")
    return e
