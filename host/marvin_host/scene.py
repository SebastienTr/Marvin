"""The simulated room: one source of truth for the host simulator, the simulated camera
and the firmware simulator (firmware/src/scene_data.h is generated from this file).

A small home office, about 3.8 x 3.6 m: an L-shaped desk against one wall, a workbench under
the windows, a sofa, a wardrobe, a built-in closet and the entrance door. The robot stands on
the workbench and looks at the person sitting at the L-shaped desk.

Boxes are written in room coordinates (floor plan, floor at 0, mm) and converted to the device
frame: X to the right as seen facing the robot, Y backwards (the robot looks at -Y), Z = 0 on the
workbench top, the floor at Z = -750. The lidar only sees what crosses its plane (Z = 133):
walls, tall furniture, the chair back, people. Low furniture (sofa, desks) is invisible to it
but visible to the camera.

Regenerate the firmware header after any change:
    python -m marvin_host.scene > ../firmware/src/scene_data.h

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

DESK_H = 750.0                 # workbench top above the floor
FLOOR = -DESK_H
CEILING = 2500.0 - DESK_H
LIDAR_Z = 133.0

# where the robot stands on the floor plan, and the direction it faces (room -X)
ROBOT_X, ROBOT_Y = 3550.0, 2250.0


def to_device(x: float, y: float) -> tuple[float, float]:
    """Floor-plan point (mm) -> device X, Y. The robot faces room -X, so its left is room -Y."""
    return ROBOT_Y - y, x - ROBOT_X


@dataclass(frozen=True)
class Box:
    name: str
    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    color: tuple[int, int, int]


def _box(name, x0, y0, z0, x1, y1, z1, color) -> Box:
    """Box from floor-plan coordinates (mm, floor at z = 0)."""
    ax, ay = to_device(x0, y0)
    bx, by = to_device(x1, y1)
    return Box(name, (min(ax, bx), min(ay, by), z0 - DESK_H), (max(ax, bx), max(ay, by), z1 - DESK_H), color)


H = 2500.0                     # ceiling height
WALL = (236, 230, 215)
OAK = (175, 130, 85)
RUSTIC = (105, 72, 48)
PINE = (215, 178, 125)
BLUE_MAT = (70, 150, 215)
PANEL_BLUE = (55, 105, 130)
MUSTARD = (200, 160, 40)
BLACK = (40, 40, 44)
GREEN = (80, 135, 70)
WHITE = (240, 240, 238)

BOXES = [
    # walls (room 3800 x 3600), the entrance door opening in the north wall, a short hallway
    _box("wall south", -100, -100, 0, 3900, 0, H, WALL),
    _box("wall west", -100, 0, 0, 0, 3600, H, WALL),
    _box("wall east (windows)", 3800, 0, 0, 3900, 3600, H, WALL),
    _box("wall north a", -100, 3600, 0, 150, 3700, H, WALL),
    _box("wall north b", 950, 3600, 0, 3900, 3700, H, WALL),
    _box("door lintel", 150, 3600, 2100, 950, 3700, H, WALL),
    _box("hallway west", -100, 3700, 0, 150, 4900, H, WALL),
    _box("hallway east", 950, 3700, 0, 1100, 4900, H, WALL),
    _box("hallway end", -100, 4900, 0, 1100, 5000, H, WALL),
    _box("beam", 0, 1850, 2250, 3800, 2150, H, (110, 70, 40)),
    _box("beam east", 3600, 0, 2300, 3800, 3600, H, (110, 70, 40)),
    # north wall: built-in closet, blue panel, L-shaped desk, monitors
    _box("closet", 1000, 3000, 0, 1700, 3600, H, WALL),
    _box("blue panel", 1800, 3590, 700, 3700, 3600, 2300, PANEL_BLUE),
    _box("pinboard", 2500, 3570, 1450, 3100, 3590, 1850, (200, 165, 120)),
    _box("desk main arm", 1700, 2950, 0, 3100, 3550, 750, RUSTIC),
    _box("desk left arm", 1700, 1950, 0, 2300, 2950, 750, RUSTIC),
    _box("drawer pedestal", 1750, 2000, 0, 2150, 2500, 700, BLACK),
    _box("curved monitor", 2250, 3250, 950, 3000, 3320, 1380, BLACK),
    _box("monitor stand", 2590, 3300, 750, 2660, 3400, 950, BLACK),
    _box("monitor left", 1780, 3150, 900, 2180, 3200, 1260, BLACK),
    _box("monitor side", 1760, 2250, 900, 1810, 2750, 1260, BLACK),
    _box("desk lamp", 3000, 3300, 750, 3040, 3340, 1450, BLACK),
    _box("pendant lamp a", 1950, 3300, 1850, 2100, 3450, 2050, (245, 200, 120)),
    _box("pendant lamp b", 2850, 3300, 1850, 3000, 3450, 2050, (245, 200, 120)),
    # the office chair, in front of the desk corner (its back crosses the lidar plane)
    _box("chair seat", 2450, 2100, 450, 2950, 2600, 500, BLACK),
    _box("chair back", 2460, 1980, 500, 2940, 2040, 1150, BLACK),
    _box("chair base", 2660, 2250, 0, 2740, 2330, 450, BLACK),
    # east wall: French doors, pegboard over the blue workbench, window with the air conditioner
    _box("french doors", 3790, 2350, 0, 3800, 3450, 2200, (180, 200, 215)),
    _box("pegboard", 3780, 1500, 1000, 3800, 2300, 2000, WHITE),
    _box("workbench", 3100, 1500, 0, 3800, 2900, 750, BLUE_MAT),
    _box("3d printer", 3350, 2500, 750, 3750, 2880, 1150, (200, 200, 205)),
    _box("printer enclosure", 3150, 1600, 0, 3650, 2100, 600, BLACK),
    _box("window", 3790, 450, 950, 3800, 1450, 2100, (180, 200, 215)),
    _box("air conditioner", 3550, 450, 250, 3800, 1450, 700, WHITE),
    _box("side table", 3450, 1050, 0, 3800, 1500, 850, RUSTIC),
    _box("drawer box", 3500, 1100, 850, 3750, 1400, 1150, (200, 120, 50)),
    _box("rattan stool", 3000, 1050, 0, 3350, 1400, 450, (190, 150, 90)),
    _box("rug", 2600, 300, 0, 3700, 1700, 8, (215, 205, 185)),
    # south wall: dresser with books, sofa, framed textile, bookshelves
    _box("dresser", 2700, 0, 0, 3450, 450, 750, PINE),
    _box("books on dresser", 2750, 50, 750, 3300, 400, 950, (150, 60, 50)),
    _box("sofa", 1000, 0, 0, 2600, 900, 450, MUSTARD),
    _box("sofa back", 1000, 0, 450, 2600, 250, 850, MUSTARD),
    _box("textile", 1300, 0, 1300, 2400, 20, 2000, (140, 90, 50)),
    _box("bookshelves", 2800, 0, 1600, 3400, 250, 2200, (130, 60, 45)),
    # west wall: wardrobe with bags on top
    _box("wardrobe", 0, 1500, 0, 600, 2600, 1950, OAK),
    _box("bags", 50, 1600, 1950, 550, 2500, 2300, (60, 60, 60)),
    # plants: the tall bamboo in its red pot, a smaller one by the French doors
    _box("bamboo pot", 1300, 2250, 0, 1650, 2600, 450, (180, 50, 50)),
    _box("bamboo", 1200, 2150, 450, 1750, 2700, 2000, GREEN),
    _box("small bamboo", 3200, 3150, 750, 3500, 3500, 1900, GREEN),
]

# ---------------------------------------------------------------- the person

PERSON_RADIUS = 180.0
# (time s, x, y) on the floor plan: comes in through the door, crosses the room, stops by the
# sofa and the window, sits at the desk for a while, then leaves. Linear between waypoints.
_PLAN = [
    (0.0, 550, 4400), (3.0, 550, 3400), (5.0, 820, 2850), (8.0, 850, 1300), (11.0, 1800, 1300),
    (14.0, 1800, 1300), (17.0, 2700, 1100), (20.0, 2700, 1100), (23.0, 2700, 1750), (26.0, 2700, 2380),
    (56.0, 2700, 2380), (59.0, 2700, 1750), (62.0, 850, 1300), (65.0, 820, 2850), (67.0, 550, 3400),
    (70.0, 550, 4400),
]
WAYPOINTS = [(t, *to_device(x, y)) for t, x, y in _PLAN]
LOOP = WAYPOINTS[-1][0]
SIT = (27.0, 55.0)            # seated between these times (s): lower head, vital signs visible
# While seated the person mostly sits still, with a few bursts of typing / shifting in the chair
# (start, end in s, inside SIT). During these the MR60BHA2 loses the vital signs, as the real one
# does as soon as the person moves, and the person sways more so the LD2450 sees the movement.
FIDGETS = [(34.0, 36.5), (42.0, 45.0), (49.0, 51.0)]
FIDGET_SWAY = (60.0, 60.0)    # extra sway amplitude (device X, Y), mm, peaking mid-window
FIDGET_HZ = (1.0, 0.7)        # sway frequency (X, Y)


def fidget_at(t: float) -> tuple[float, float]:
    """(envelope 0..1, its time derivative 1/s) of the fidget sway at time t (s, in the loop)."""
    for t0, t1 in FIDGETS:
        if t0 < t < t1:
            k = math.pi / (t1 - t0)
            return math.sin(k * (t - t0)), k * math.cos(k * (t - t0))
    return 0.0, 0.0


@dataclass
class Person:
    x: float
    y: float
    vx: float
    vy: float
    seated: bool
    top: float                 # head top, Z


def person_at(t: float) -> Person:
    t %= LOOP
    for (t0, x0, y0), (t1, x1, y1) in zip(WAYPOINTS, WAYPOINTS[1:]):
        if t0 <= t <= t1:
            k = (t - t0) / (t1 - t0)
            x, y = x0 + (x1 - x0) * k, y0 + (y1 - y0) * k
            vx, vy = (x1 - x0) / (t1 - t0), (y1 - y0) / (t1 - t0)
            break
    seated = SIT[0] <= t <= SIT[1]
    if seated:                                         # small sway while seated
        w = 2 * math.pi * 0.2
        x += 25 * math.sin(w * t)
        vx += 25 * w * math.cos(w * t)
        env, denv = fidget_at(t)
        if env > 0:                                    # typing / shifting: larger, faster sway
            for axis, (amp, hz) in enumerate(zip(FIDGET_SWAY, FIDGET_HZ)):
                w = 2 * math.pi * hz
                d = amp * env * math.sin(w * t)
                v = amp * (denv * math.sin(w * t) + env * w * math.cos(w * t))
                if axis == 0:
                    x, vx = x + d, vx + v
                else:
                    y, vy = y + d, vy + v
    return Person(x, y, vx, vy, seated, 550.0 if seated else 1000.0)


# ---------------------------------------------------------------- lidar plane

def lidar_segments(z: float = LIDAR_Z) -> np.ndarray:
    """(S, 4) segments x1, y1, x2, y2: the cross-section of every box at the lidar height."""
    segs = []
    for b in BOXES:
        if b.lo[2] <= z <= b.hi[2]:
            (x0, y0, _), (x1, y1, _) = b.lo, b.hi
            segs += [(x0, y0, x1, y0), (x1, y0, x1, y1), (x1, y1, x0, y1), (x0, y1, x0, y0)]
    return np.array(segs, dtype=np.float64)


SEGMENTS = lidar_segments()


def ray_distances(dx: np.ndarray, dy: np.ndarray, person: Person | None, segs: np.ndarray = SEGMENTS) -> np.ndarray:
    """Distances from the origin along unit directions (dx, dy) to the scene in the lidar plane. inf = no hit."""
    dx, dy = dx[:, None], dy[:, None]
    x1, y1, x2, y2 = (segs[:, i][None, :] for i in range(4))
    ex, ey = x2 - x1, y2 - y1
    den = dx * ey - dy * ex
    with np.errstate(divide="ignore", invalid="ignore"):
        t = (x1 * ey - y1 * ex) / den
        u = (x1 * dy - y1 * dx) / den
    t = np.where((np.abs(den) > 1e-9) & (t > 0) & (u >= 0) & (u <= 1), t, np.inf)
    d = t.min(axis=1)
    if person is not None:
        dx, dy = dx[:, 0], dy[:, 0]
        b = dx * person.x + dy * person.y
        c = person.x ** 2 + person.y ** 2 - PERSON_RADIUS ** 2
        disc = b * b - c
        hit = np.where(disc >= 0, b - np.sqrt(np.maximum(disc, 0)), np.inf)
        d = np.minimum(d, np.where(hit > 0, hit, np.inf))
    return d


# ---------------------------------------------------------------- vital signs (MR60BHA2)

MR60_POS = (0.0, -28.4, 41.7)


def vitals_at(t: float) -> tuple[bool, float, float, float, float, float]:
    """(valid, breath rate bpm, heart rate bpm, breath wave, heart wave, distance mm).

    Vital signs are only measurable on a still, seated person in front of the radar: none while
    walking, and none during the fidget windows (the real sensor drops out as soon as the person moves).
    """
    p = person_at(t)
    dist = math.hypot(p.x - MR60_POS[0], p.y - MR60_POS[1])
    if not p.seated or fidget_at(t % LOOP)[0] > 0:
        return False, 0.0, 0.0, 0.0, 0.0, dist
    # slow drifts plus a little beat-to-beat jitter
    br = 14.0 + 1.5 * math.sin(2 * math.pi * t / 23.0) + 0.3 * math.sin(2 * math.pi * t / 3.7)
    hr = 68.0 + 4.0 * math.sin(2 * math.pi * t / 17.0) + 1.2 * math.sin(2 * math.pi * t / 2.3)
    # phases integrated from the mean rates keep the waves smooth
    bw = math.sin(2 * math.pi * 14.0 / 60.0 * t)
    hw = math.sin(2 * math.pi * 68.0 / 60.0 * t)
    return True, br, hr, bw, hw, dist


# ---------------------------------------------------------------- firmware header

def c_header() -> str:
    out = [
        "// Generated by `python -m marvin_host.scene` from host/marvin_host/scene.py. Do not edit.",
        "// SPDX-License-Identifier: MIT",
        "#pragma once",
        "",
        "namespace scene {",
        "",
        "struct Seg { float x1, y1, x2, y2; };",
        f"constexpr int SEG_COUNT = {len(SEGMENTS)};",
        "const Seg SEGMENTS[SEG_COUNT] = {",
    ]
    out += [f"    {{{a:.0f}, {b:.0f}, {c:.0f}, {d:.0f}}}," for a, b, c, d in SEGMENTS]
    out += [
        "};",
        "",
        "struct Waypoint { float t, x, y; };",
        f"constexpr int WAYPOINT_COUNT = {len(WAYPOINTS)};",
        "const Waypoint WAYPOINTS[WAYPOINT_COUNT] = {",
    ]
    out += [f"    {{{t:.1f}f, {x:.0f}, {y:.0f}}}," for t, x, y in WAYPOINTS]
    out += [
        "};",
        f"constexpr float LOOP = {LOOP:.1f}f;",
        f"constexpr float SIT_START = {SIT[0]:.1f}f;",
        f"constexpr float SIT_END = {SIT[1]:.1f}f;",
        "",
        "struct Window { float start, end; };",
        f"constexpr int FIDGET_COUNT = {len(FIDGETS)};",
        "const Window FIDGETS[FIDGET_COUNT] = {",
    ]
    out += [f"    {{{a:.1f}f, {b:.1f}f}}," for a, b in FIDGETS]
    out += [
        "};",
        f"constexpr float FIDGET_SWAY_X = {FIDGET_SWAY[0]:.1f}f;",
        f"constexpr float FIDGET_SWAY_Y = {FIDGET_SWAY[1]:.1f}f;",
        f"constexpr float FIDGET_HZ_X = {FIDGET_HZ[0]:.2f}f;",
        f"constexpr float FIDGET_HZ_Y = {FIDGET_HZ[1]:.2f}f;",
        "",
        f"constexpr float PERSON_RADIUS = {PERSON_RADIUS:.1f}f;",
        f"constexpr float MR60_X = {MR60_POS[0]:.1f}f;",
        f"constexpr float MR60_Y = {MR60_POS[1]:.1f}f;",
        "",
        "}  // namespace scene",
        "",
    ]
    return "\n".join(out)


if __name__ == "__main__":
    print(c_header(), end="")
