"""Live view in Rerun: the robot, the lidar scan and the radar targets in the device frame.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math

import numpy as np
import rerun as rr

from . import frames, protocol
from .receiver import Device, Sink

GREY = (190, 185, 175)
ANTHRACITE = (60, 62, 66)


def log_robot() -> None:
    """Static geometry: body, neck, head and the sensor positions (rev F, mm)."""
    rr.log("world", rr.ViewCoordinates.RIGHT_HAND_Z_UP, static=True)
    rr.log("world/robot/body", rr.Boxes3D(centers=[(0, 0, 35)], half_sizes=[(40, 37, 29)], colors=[GREY]), static=True)
    rr.log("world/robot/head", rr.Boxes3D(centers=[(0, 0, 96)], half_sizes=[(44, 40, 28)], colors=[GREY]), static=True)
    rr.log("world/robot/lidar", rr.Boxes3D(centers=[(0, 0, frames.LIDAR.z_mm)], half_sizes=[(19, 19, 9)],
                                           colors=[ANTHRACITE]), static=True)
    r = frames.LD2450
    t = math.radians(r.tilt_deg)
    rr.log("world/robot/ld2450", rr.Arrows3D(origins=[(0, r.y_mm, r.z_mm)], vectors=[(0, -300 * math.cos(t), 300 * math.sin(t))],
                                             colors=[(80, 160, 255)], labels=["LD2450"]), static=True)
    rr.log("world/robot/mr60bha2", rr.Arrows3D(origins=[(0, -28.4, 41.7)], vectors=[(0, -300 * math.cos(math.radians(20)), 300 * math.sin(math.radians(20)))],
                                               colors=[(255, 120, 80)], labels=["MR60BHA2"]), static=True)


class RerunSink(Sink):
    def __init__(self):
        self.t0: dict[tuple, int] = {}

    def _time(self, dev: Device, t_us: int) -> None:
        rr.set_time("device_time", duration=t_us / 1e6)

    def on_hello(self, dev: Device) -> None:
        h = dev.hello
        rr.log("log", rr.TextLog(f"{h.device_name}: {protocol.BOARDS.get(h.board, h.board)}, firmware {h.firmware}"
                                 f"{' (simulated)' if h.simulated else ''}, RSSI {h.rssi} dBm"))

    def on_scan(self, dev, t_us, points, intensities, speed_dps) -> None:
        self._time(dev, t_us)
        if len(points):
            c = np.clip(intensities.astype(np.float32) / 255.0, 0, 1)
            colors = np.stack([60 + 195 * c, 220 - 80 * c, 90 + 0 * c], axis=1).astype(np.uint8)
            rr.log("world/lidar/scan", rr.Points3D(points, colors=colors, radii=12))
        rr.log("stats/lidar_points", rr.Scalars(len(points)))
        rr.log("stats/lidar_hz", rr.Scalars(speed_dps / 360.0))
        s = dev.stats
        rr.log("stats/lost_datagrams", rr.Scalars(s.lost))
        rr.log("stats/crc_errors", rr.Scalars(s.crc_errors))

    def on_targets(self, dev, t_us, targets, points) -> None:
        self._time(dev, t_us)
        if not points:
            rr.log("world/ld2450/targets", rr.Clear(recursive=False))
            rr.log("presence/targets", rr.Scalars(0))
            return
        labels = [f"{math.hypot(t.x_mm, t.y_mm) / 1000:.2f} m, {t.speed_cms} cm/s" for t in targets]
        rr.log("world/ld2450/targets", rr.Points3D(points, radii=60, colors=[(80, 160, 255)] * len(points), labels=labels))
        rr.log("presence/targets", rr.Scalars(len(points)))
        near = min(targets, key=lambda t: math.hypot(t.x_mm, t.y_mm))
        rr.log("presence/nearest_m", rr.Scalars(math.hypot(near.x_mm, near.y_mm) / 1000))
        rr.log("presence/speed_cms", rr.Scalars(near.speed_cms))

    def on_log(self, dev, t_us, text) -> None:
        self._time(dev, t_us)
        rr.log("log", rr.TextLog(f"{dev.hello.device_name}: {text}"))


def start(save: str | None = None, spawn: bool = True) -> RerunSink:
    rr.init("marvin", spawn=spawn and not save)
    if save:
        rr.save(save)
    log_robot()
    return RerunSink()
