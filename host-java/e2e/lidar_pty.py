#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""A pseudo-terminal that plays an STL-27L lidar at its real rate (1800 packets of 12 points a second, 10
revolutions), for the USB sensor bridge (`MARVIN_USB_PORTS=<LINK>`), with no hardware.

The scan is a lidar lying on a cluttered desk, as the owner's first one did: everything between 10 cm and 1 m
(a screen in front, a lamp and a mug on the sides, a wall behind), a few bearings with no return, a little
noise. `--far` plays a room instead (1 to 4 m).

    python3 host-java/e2e/lidar_pty.py /tmp/marvin-lidar [SECONDS] [--far]

Run it with host/ on the Python path (it uses marvin_host.ldrobot to build the packets)."""
from __future__ import annotations

import math
import os
import pty
import random
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "host"))
from marvin_host import ldrobot  # noqa: E402

POINTS = 12
PACKETS_PER_S = 1800
SPEED_DPS = 3600                       # 10 revolutions a second


def desk(deg: float) -> float:
    """The distance (mm) at bearing `deg` (clockwise from the front) on a cluttered desk: 0 is no return."""
    d = deg % 360
    if d < 35 or d > 325:
        return 380 + 40 * math.cos(math.radians(d) * 3)          # the screen
    if 60 < d < 75:
        return 140                                               # the mug
    if 280 < d < 300:
        return 210                                               # the lamp's foot
    if 150 < d < 210:
        return 950                                               # the wall behind
    if 120 < d < 125:
        return 0                                                 # a black cable: nothing comes back
    return 520 + 300 * math.sin(math.radians(d) * 2) ** 2        # books, a keyboard, the desk's edge


def room(deg: float) -> float:
    d = math.radians(deg % 360)
    return min(4000, 1600 / max(0.25, abs(math.cos(d))), 2400 / max(0.25, abs(math.sin(d))))


def packets(shape, rng: random.Random):
    """Endless STL-27L packets, 2 degrees each at 3600 deg/s (180 a revolution)."""
    start, ts = 0.0, 0
    step = 360.0 / (PACKETS_PER_S / 10)
    while True:
        end = (start + step * (POINTS - 1) / POINTS) % 360
        dist, inten = [], []
        for k in range(POINTS):
            a = start + step * k / POINTS
            r = shape(a)
            dist.append(0 if r <= 0 else max(0, int(r + rng.gauss(0, 4))))
            inten.append(0 if r <= 0 else 200)
        yield ldrobot.build(SPEED_DPS, start, end, dist, inten, ts)
        start = (start + step) % 360
        ts = (ts + 1) % 30000


def main(argv: list[str]) -> int:
    args = [a for a in argv if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    link = args[0]
    seconds = float(args[1]) if len(args) > 1 else 3600.0
    shape = room if "--far" in argv else desk
    master, slave = pty.openpty()
    os.set_blocking(master, False)
    if os.path.lexists(link):
        os.remove(link)
    os.symlink(os.ttyname(slave), link)
    print(f"STL-27L on {link} -> {os.ttyname(slave)} ({'room' if shape is room else 'desk'})", flush=True)
    gen = packets(shape, random.Random(0))
    t0 = time.monotonic()
    sent = 0
    try:
        while time.monotonic() - t0 < seconds:
            due = int((time.monotonic() - t0) * PACKETS_PER_S)
            if due > sent:
                chunk = b"".join(next(gen) for _ in range(due - sent))
                try:
                    os.write(master, chunk)
                except OSError:
                    time.sleep(0.05)                 # nobody reads yet: the bytes are dropped
                sent = due
            time.sleep(0.01)
    finally:
        os.remove(link)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
