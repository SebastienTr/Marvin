"""What the brain tells the rest of the robot: events and the current presence state.

This module is the contract between the brain (brain.py), which turns sensor frames into
events and state, and everything that reacts to them: the face (face.py), the viewer, and
later the voice. It holds data types only.

Device frame (see docs/architecture.md): millimetres, X to the right as seen by someone facing
the robot, Y backwards (the robot looks at -Y), Z up, Z = 0 on the surface the robot stands on.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class EventKind(str, Enum):
    ARRIVED = "arrived"                  # someone entered the field of view (nobody was there)
    LEFT = "left"                        # nobody seen for a while
    APPROACHED = "approached"            # came within arm's reach of the robot
    SAT_DOWN = "sat_down"                # settled in front of the robot
    STOOD_UP = "stood_up"                # was seated, is moving again
    STILL_LONG = "still_long"            # seated and still for a long time (time for a break)
    VITALS_ACQUIRED = "vitals_acquired"  # breathing / heart rate readings became reliable
    VITALS_LOST = "vitals_lost"          # readings no longer reliable (movement, out of range)


@dataclass(frozen=True)
class Event:
    kind: EventKind
    t_us: int                            # device clock, microseconds
    detail: str = ""                     # short human-readable note, English
    data: dict = field(default_factory=dict)


@dataclass
class PresenceState:
    """The brain's current belief about the person in front of the robot (the nearest one)."""

    t_us: int = 0
    present: bool = False
    seated: bool = False
    position: tuple[float, float, float] | None = None   # nearest person, device frame, mm (radar height)
    head: tuple[float, float, float] | None = None       # estimated head position, device frame, mm
    distance_m: float | None = None                      # horizontal distance to the robot
    speed_cms: float = 0.0                               # smoothed speed, cm/s (positive = moving away)
    still_s: float = 0.0                                 # seconds without significant movement
    seated_s: float = 0.0                                # seconds since SAT_DOWN
    breath_rate: float | None = None                     # per minute, None when not reliable
    heart_rate: float | None = None                      # per minute, None when not reliable
    vitals_sensor: bool = False                          # a vital-signs radar (MR60BHA2) has reported
    simulated: bool = False                              # the sensor data comes from a simulated person
    targets: int = 0                                     # people seen by the LD2450
