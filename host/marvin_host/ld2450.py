"""HLK-LD2450 24 GHz radar target frames.

A frame is 30 bytes: AA FF 03 00, 3 targets x 8 bytes, 55 CC.
Each target: x i16 (mm), y i16 (mm), speed i16 (cm/s), distance resolution u16 (mm).
x, y and speed use a sign bit: bit 15 set = positive, clear = negative (magnitude in bits 0-14).
An all-zero target slot means "no target". The radar frame: x to the right, y straight ahead.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import struct
from dataclasses import dataclass

HEAD = b"\xAA\xFF\x03\x00"
TAIL = b"\x55\xCC"
SIZE = 30
_T = struct.Struct("<HHHH")


@dataclass
class Target:
    x_mm: int
    y_mm: int
    speed_cms: int
    resolution_mm: int


def _dec(v: int) -> int:
    return (v & 0x7FFF) if v & 0x8000 else -(v & 0x7FFF)


def _enc(v: int) -> int:
    v = int(v)
    return (0x8000 | min(v, 0x7FFF)) if v >= 0 else min(-v, 0x7FFF)


def parse(buf: bytes) -> list[Target]:
    if len(buf) != SIZE or not buf.startswith(HEAD) or not buf.endswith(TAIL):
        raise ValueError("not an LD2450 frame")
    out = []
    for i in range(3):
        x, y, s, r = _T.unpack_from(buf, 4 + 8 * i)
        if x == y == s == r == 0:
            continue
        out.append(Target(_dec(x), _dec(y), _dec(s), r))
    return out


def build(targets: list[Target]) -> bytes:
    body = b""
    for i in range(3):
        if i < len(targets):
            t = targets[i]
            body += _T.pack(_enc(t.x_mm), _enc(t.y_mm), _enc(t.speed_cms), t.resolution_mm)
        else:
            body += bytes(8)
    return HEAD + body + TAIL
