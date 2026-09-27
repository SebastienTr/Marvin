"""Sensor extrinsics and conversions into the device frame.

Device frame (same as the CAD, see docs/architecture.md): origin on the body axis at desk level,
X to the right as seen by someone facing the robot, Y backwards (the face looks towards -Y), Z up.
Units: millimetres.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np


@dataclass
class LidarMount:
    """LDROBOT angles are clockwise seen from above. `yaw_deg` is the lidar angle that points to the front (-Y)."""

    z_mm: float = 133.0
    yaw_deg: float = 0.0          # to be calibrated once the head is built
    min_mm: float = 30.0          # ignore returns closer than this (the robot itself)
    max_mm: float = 12000.0


@dataclass
class RadarMount:
    """HLK-LD2450: x across the radar, y along its boresight. Mounted looking at -Y, tilted up."""

    y_mm: float = -33.1
    z_mm: float = 16.4
    tilt_deg: float = 10.0
    x_sign: int = 1               # flip if the radar is mounted the other way round


LIDAR = LidarMount()
LD2450 = RadarMount()


def lidar_to_device(angles_deg: np.ndarray, distances_mm: np.ndarray, mount: LidarMount = LIDAR) -> np.ndarray:
    """(N,) angles and distances -> (M, 3) points, invalid returns dropped.

    Clockwise from above starting at the front means: 0 deg -> -Y, 90 deg -> the robot's right, which is -X.
    """
    d = np.asarray(distances_mm, dtype=np.float32)
    keep = (d >= mount.min_mm) & (d <= mount.max_mm)
    th = np.radians(np.asarray(angles_deg, dtype=np.float32)[keep] - mount.yaw_deg)
    d = d[keep]
    return np.stack([-d * np.sin(th), -d * np.cos(th), np.full_like(d, mount.z_mm)], axis=1)


def device_to_lidar(x: float, y: float, mount: LidarMount = LIDAR) -> tuple[float, float]:
    """Inverse of lidar_to_device in the lidar plane: (angle deg, distance mm)."""
    ang = math.degrees(math.atan2(-x, -y)) + mount.yaw_deg
    return ang % 360.0, math.hypot(x, y)


def ld2450_to_device(x_mm: float, y_mm: float, mount: RadarMount = LD2450) -> tuple[float, float, float]:
    """One radar target -> device point. The radar reports positions in its tilted plane."""
    t = math.radians(mount.tilt_deg)
    return (mount.x_sign * x_mm, mount.y_mm - y_mm * math.cos(t), mount.z_mm + y_mm * math.sin(t))


def device_to_ld2450(x: float, y: float, mount: RadarMount = LD2450) -> tuple[float, float]:
    """Inverse of ld2450_to_device for a point in front of the robot (only X and Y are used)."""
    t = math.radians(mount.tilt_deg)
    return mount.x_sign * x, (mount.y_mm - y) / math.cos(t)
