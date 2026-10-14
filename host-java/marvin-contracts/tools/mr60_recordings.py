# SPDX-License-Identifier: MIT
"""The MR60BHA2 recordings (golden/recordings/mr60_*.mvrec.gz): what the MR60BHA2 bridge
(firmware/mr60_bridge, 0.2.0) sends, for the Java host's vitals pipeline (VitalsRecordingsTest).

The bridge's datagrams are synthesized the way its firmware builds them: HELLO (board 4, real data) every 2 s,
VITALS at 10 Hz whose rates change about once a second (the radar's own refresh), the flags byte with the
signal-quality bits (1: quality reported, 2: steady), the distance in mm. Their scenario is described in
golden/recordings/mr60.json, with the expectations the Java test checks; the files are deterministic.

    python3 host-java/marvin-contracts/tools/mr60_recordings.py
"""
from __future__ import annotations

import math
import struct
from pathlib import Path

from _common import GOLDEN, rel, write_json

from marvin_host import protocol, record, sim  # noqa: E402
from recordings import fixed_clock, simulated  # noqa: E402

BRIDGE_ADDR = ("192.0.2.4", protocol.DEVICE_PORT)       # TEST-NET-1, as the simulated robot's
BRIDGE_MAC = bytes.fromhex("58e6c5a1b2c3")
BRIDGE_FW = "mr60-bridge-0.2.0"
BRIDGE_BOOT_S = 37.25                                    # the bridge's clock (esp_timer) when the recording starts
VALID, QUALITY, STEADY = 0x01, 0x02, 0x04


def vitals(flags: int, br: float, hr: float, bw: float, hw: float, mm: int) -> bytes:
    """The bridge's VITALS payload (mr60::encode_vitals)."""
    def rate(r):
        return 0 if not r > 0 else min(65535, round(r * 100))

    def wave(w):
        return int(round(max(-1.0, min(1.0, w)) * 32767))
    return struct.pack("<BHHhhH", flags, rate(br), rate(hr), wave(bw), wave(hw), mm)


def bridge(segments, seconds: float, start: float = 0.0):
    """(host time s, datagram) of the bridge. `segments`: (until s, what) with what(t) -> (flags, br, hr, mm)."""
    out = []
    seq = 0

    def pack(t, typ, payload):
        nonlocal seq
        out.append((t, protocol.pack(typ, seq, int((BRIDGE_BOOT_S + t) * 1e6), payload)))
        seq += 1

    for i in range(int(round(seconds * 10))):
        t = start + i * 0.1
        if i % 20 == 0:
            h = protocol.Hello(BRIDGE_MAC, 4, 0, -58, int((BRIDGE_BOOT_S + t) * 1000), BRIDGE_FW)
            pack(t, protocol.HELLO, h.encode())
        what = next(w for until, w in segments if t < until)
        flags, br, hr, mm = what(math.floor(t))           # the radar refreshes its rates once a second
        on = flags & VALID
        bw = math.sin(2 * math.pi * br / 60 * t) if on else 0.0
        hw = math.sin(2 * math.pi * hr / 60 * t) if on else 0.0
        pack(t, protocol.VITALS, vitals(flags, br if on else 0, hr if on else 0, bw, hw, mm))
    return out


def nobody(_t):
    return QUALITY, 0, 0, 0


def sitting_down(_t):
    return QUALITY, 0, 0, 850                              # someone there, no rates yet


def seated(t):
    hr = 63.0 + 2.0 * math.sin(2 * math.pi * t / 23.0) + (9.0 if int(t) % 13 == 0 else 0.0)   # an outlier now and then
    br = 13.0 + 1.0 * math.sin(2 * math.pi * t / 31.0)
    return VALID | QUALITY | STEADY, round(br, 2), round(hr, 2), 840


def fidgeting(t):
    return VALID | QUALITY, 14.0, 88.0 + (t % 3) * 4, 790     # valid by the radar, not steady (heart rate jumps)


DESK = [(5.0, nobody), (8.0, sitting_down), (50.0, seated), (56.0, fidgeting), (80.0, seated), (86.0, nobody)]


def write(path: Path, items, meta: dict) -> None:
    """(host time s, address, datagram) -> a .mvrec(.gz), in time order."""
    with fixed_clock(), record.RecordingWriter(path, meta=meta) as w:
        t0 = w._t0
        for t, addr, data in sorted(items, key=lambda x: x[0]):
            w.write_datagram(data, addr, t0 + t)


def mr60_desk(path: Path) -> dict:
    """The MR60BHA2 bridge on its own on a desk (no presence radar): nobody (0-5 s), someone sits at 85 cm (rates
    from 8 s, valid and steady), fidgets (50-56 s: valid, not steady, the heart rate jumps), still again, leaves
    (80 s)."""
    items = [(t, BRIDGE_ADDR, d) for t, d in bridge(DESK, 86.0)]
    write(path, items, {"simulated": False, "scenario": "mr60_desk", "synthesized": "mr60_recordings.py"})
    return {"description": " ".join(mr60_desk.__doc__.split()), "duration_s": 86.0,
            "expect": [
                {"at_s": 4.9, "quality": "none"},
                {"at_s": 12.0, "quality": "unstable"},
                {"at_s": 20.0, "quality": "good", "hr": [60.0, 66.0], "br": [12.0, 14.0], "distance_m": 0.84},
                {"at_s": 49.9, "quality": "good"},
                {"at_s": 54.0, "quality": "unstable"},
                {"at_s": 75.0, "quality": "good"},
                {"at_s": 85.9, "quality": "none"},
            ]}


def mr60_with_robot(path: Path) -> dict:
    """The simulated robot (LD2450 and simulated VITALS, seated in its scene from 26 s) and, from 10 s, the MR60BHA2
    bridge on the desk with other rates: one seat, one radar. The bridge, real, takes the seat from the simulated
    radar at once; the robot's LD2450 still says whether the person is still."""
    robot = [(t - 26.0, protocol.unpack(d)[0].type, d) for t, d in simulated(30.0, start=26.0)]
    items = [(t, record.SIM_ADDR, d) for t, typ, d in robot if typ != protocol.LIDAR]
    steady = [(30.0, lambda t: (VALID | QUALITY | STEADY, 11.5, 57.0 + (int(t) % 2), 700))]
    items += [(t, BRIDGE_ADDR, d) for t, d in bridge(steady, 20.0, start=10.0)]
    write(path, items, {"simulated": False, "scenario": "mr60_with_robot", "synthesized": "mr60_recordings.py",
                        "robot": "marvin_host.sim from scene time 26 s, lidar left out"})
    return {"description": " ".join(mr60_with_robot.__doc__.split()), "duration_s": 30.0,
            "expect": [
                {"at_s": 9.9, "source": "robot"},
                {"at_s": 29.9, "source": "bridge", "quality": "good", "hr": [57.0, 58.0], "br": [11.5, 11.5],
                 "distance_m": 0.7},
            ]}


def main() -> None:
    out = GOLDEN / "recordings"
    scenarios = []
    for fn in (mr60_desk, mr60_with_robot):
        path = out / f"{fn.__name__}.mvrec.gz"
        info = fn(path)
        scenarios.append({"name": fn.__name__, "file": path.name, **info})
        print(rel(path))
    write_json(out / "mr60.json", {
        "about": "MR60BHA2 bridge recordings for the vitals pipeline (host-java/marvin-contracts/tools/mr60_recordings.py)."
                 " Host times in seconds from the start of the recording; expectations checked by VitalsRecordingsTest.",
        "format": "mvrec v1, gzip (host/marvin_host/record.py)",
        "bridge": {"address": "%s:%d" % BRIDGE_ADDR, "board": 4, "firmware": BRIDGE_FW,
                   "flags": {"valid": VALID, "quality_reported": QUALITY, "steady": STEADY}},
        "scenarios": scenarios})


if __name__ == "__main__":
    main()
