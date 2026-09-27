"""Golden frames for the C++ face port (firmware/src/face), from the Python reference face.

Plays one scripted scenario through ``marvin_host.face.Face`` (fixed seed; a person arrives, comes
close, sits down, gets a vitals cue and a break reminder, stands up, walks away, and the robot
falls asleep and wakes up again) and writes what the firmware test needs to replay it:

- ``golden_face.h``: the scenario (presence states, and per step the time, state and events) and
  the CRC-32 of every RGB565 frame, so the C++ face is checked on the whole trajectory;
- ``golden_frames.bin``: a few complete frames, run-length encoded, for a per-pixel comparison.

    python scripts/face_golden.py         # from host/, writes into firmware/test/test_face/

The frames are converted to RGB565 exactly as the screen receives them. Regenerate after any
change to face.py or raster.py, then run ``pio test -e native`` in firmware/.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import pathlib
import struct
import zlib

import numpy as np

from marvin_host.events import Event, EventKind, PresenceState
from marvin_host.face import Face
from marvin_host.protocol import FACE_EVENT_CODES

OUT = pathlib.Path(__file__).resolve().parents[2] / "firmware" / "test" / "test_face"
SEED = 7

# Presence states used by the scenario (device frame, mm). Values are exact in binary or printed
# with repr(), so C++ reads back the same doubles.
STATES: dict[str, PresenceState] = {
    "nobody": PresenceState(),
    "standing_right": PresenceState(present=True, head=(600.0, -1500.0, 1000.0), position=(600.0, -1500.0, 16.0),
                                    distance_m=1.62),
    "close": PresenceState(present=True, head=(100.0, -500.0, 1000.0), position=(100.0, -500.0, 16.0),
                           distance_m=0.51),
    "seated_left": PresenceState(present=True, seated=True, head=(-150.0, -800.0, 550.0),
                                 position=(-150.0, -800.0, 16.0), distance_m=0.81),
    "seated_vitals": PresenceState(present=True, seated=True, head=(-150.0, -800.0, 550.0),
                                   position=(-150.0, -800.0, 16.0), distance_m=0.81, heart_rate=72.0),
    "far_position_only": PresenceState(present=True, position=(-400.0, -2600.0, 16.0), distance_m=2.63),
    "present_nowhere": PresenceState(present=True),
}

# (start ms, end ms, step ms, state): steps at start, start + step, ... < end.
SEGMENTS = [
    (0, 1500, 33, "nobody"),
    (1500, 4000, 33, "standing_right"),
    (4000, 5500, 33, "close"),
    (5500, 9000, 33, "seated_left"),
    (9000, 16000, 33, "seated_vitals"),
    (16000, 16500, 33, "seated_left"),
    (16500, 20000, 33, "far_position_only"),
    (20000, 21500, 33, "present_nowhere"),
    (21500, 47000, 100, "nobody"),
    (47000, 49000, 33, "seated_left"),    # present again without ARRIVED: wakes up anyway
]

# events, applied at the first step at or after this time (ms)
EVENTS = {
    1500: [EventKind.ARRIVED],
    4000: [EventKind.APPROACHED],
    5500: [EventKind.SAT_DOWN],
    9000: [EventKind.VITALS_ACQUIRED],
    12000: [EventKind.STILL_LONG],
    16000: [EventKind.VITALS_LOST],
    16500: [EventKind.STOOD_UP],
    21500: [EventKind.LEFT],
}

# steps kept as complete frames: the first step at or after these times (ms)
FULL_AT = [4300, 6500, 9800, 13000, 27000, 44000, 48000]


def steps():
    """[(t_ms, state name, [EventKind])] in order."""
    pending = sorted(EVENTS.items())
    out = []
    for start, end, step, name in SEGMENTS:
        for t in range(start, end, step):
            evs = []
            while pending and pending[0][0] <= t:
                evs += pending.pop(0)[1]
            out.append((t, name, evs))
    assert not pending
    return out


def to_rgb565(frame: np.ndarray) -> np.ndarray:
    f = frame.astype(np.uint16)
    return ((f[..., 0] >> 3) << 11) | ((f[..., 1] >> 2) << 5) | (f[..., 2] >> 3)


def rle(img: np.ndarray) -> bytes:
    """Run-length encoding: u32 run count, then (u16 length, u16 RGB565) per run, row-major."""
    flat = img.reshape(-1)
    change = np.flatnonzero(np.diff(flat)) + 1
    starts = np.concatenate(([0], change))
    lengths = np.diff(np.concatenate((starts, [flat.size])))
    runs = []
    for s, n in zip(starts, lengths):
        while n > 0:
            k = min(int(n), 0xFFFF)
            runs.append(struct.pack("<HH", k, int(flat[s])))
            n -= k
    return struct.pack("<I", len(runs)) + b"".join(runs)


def c_state(s: PresenceState) -> str:
    def vec(p):
        return "{" + ", ".join(repr(float(v)) for v in p) + "}" if p is not None else "{0, 0, 0}"

    return ("{%s, %s, %s, %s, %s, %s, %s, %s, %s}" % (
        str(s.present).lower(), str(s.seated).lower(), str(s.head is not None).lower(), vec(s.head),
        str(s.position is not None).lower(), vec(s.position), str(s.distance_m is not None).lower(),
        repr(float(s.distance_m or 0.0)), repr(float(s.heart_rate or 0.0))))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    args = ap.parse_args()

    names = list(STATES)
    face = Face(SEED)
    rows, full, blobs = [], [], []
    todo = list(FULL_AT)
    for i, (t_ms, name, evs) in enumerate(steps()):
        for ev in evs:
            face.on_event(Event(ev, t_ms * 1000))
        img = to_rgb565(face.update(STATES[name], t_ms / 1000.0))
        crc = zlib.crc32(img.astype("<u2").tobytes())
        codes = [FACE_EVENT_CODES[e] for e in evs] + [0, 0]
        rows.append(f"    {{{t_ms}, {names.index(name)}, {{{codes[0]}, {codes[1]}}}, 0x{crc:08X}u}},"
                    f"  // {face.expression}")
        if todo and t_ms >= todo[0]:
            todo.pop(0)
            full.append(i)
            blobs.append(rle(img))
    assert not todo

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "golden_frames.bin").write_bytes(b"".join(blobs))
    header = [
        "// SPDX-License-Identifier: MIT",
        "// Generated by host/scripts/face_golden.py from the Python reference face. Do not edit.",
        "#pragma once",
        "",
        "#include <stdint.h>",
        "",
        '#include "face/face.h"',
        "",
        "namespace golden {",
        "",
        f"constexpr uint32_t SEED = {SEED};",
        "",
        "// present, seated, has_head, head, has_position, position, has_distance, distance_m, heart_rate",
        "static const face::Presence STATES[] = {",
        *(f"    {c_state(STATES[n])},  // {n}" for n in names),
        "};",
        "",
        "struct Step {",
        "  uint32_t t_ms;",
        "  uint8_t state;       // index in STATES",
        "  uint8_t events[2];   // FACE_EVENT codes applied before this step, 0 = none",
        "  uint32_t crc;        // CRC-32 of the RGB565 frame (little-endian bytes)",
        "};",
        "",
        "static const Step STEPS[] = {",
        *rows,
        "};",
        "",
        "// steps stored as complete frames in golden_frames.bin, in this order",
        "static const uint16_t FULL_STEPS[] = {" + ", ".join(map(str, full)) + "};",
        "",
        "}  // namespace golden",
        "",
    ]
    (args.out / "golden_face.h").write_text("\n".join(header))
    print(f"{len(rows)} steps, {len(full)} full frames ({sum(map(len, blobs))} bytes) -> {args.out}")


if __name__ == "__main__":
    main()
