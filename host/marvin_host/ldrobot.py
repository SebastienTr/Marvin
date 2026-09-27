"""LDROBOT lidar packets (LD19 family: D500 / STL-19P and D800 / STL-27L).

A packet is 47 bytes, little-endian:

    0x54, 0x2C (header, 12 points), speed u16 (deg/s), start angle u16 (0.01 deg),
    12 x (distance u16 mm, intensity u8), end angle u16 (0.01 deg), timestamp u16 (ms), CRC8

Angles are clockwise seen from above; see frames.py for the conversion to the robot frame.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import struct
from dataclasses import dataclass

import numpy as np

HEADER = 0x54
VER_LEN = 0x2C
POINTS = 12
SIZE = 47
_BODY = struct.Struct("<BBHH" + "HB" * POINTS + "HH")


def _crc_table() -> list[int]:
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = ((c << 1) ^ 0x4D) & 0xFF if c & 0x80 else (c << 1) & 0xFF
        table.append(c)
    return table


CRC_TABLE = _crc_table()


def crc8(data: bytes) -> int:
    c = 0
    for b in data:
        c = CRC_TABLE[(c ^ b) & 0xFF]
    return c


@dataclass
class Packet:
    speed_dps: int
    angles_deg: np.ndarray      # 12 angles, degrees, clockwise
    distances_mm: np.ndarray    # 12 distances, 0 = no return
    intensities: np.ndarray     # 12 values 0-255
    timestamp_ms: int


def parse(buf: bytes) -> Packet:
    if len(buf) != SIZE or buf[0] != HEADER or buf[1] != VER_LEN:
        raise ValueError("not an LDROBOT packet")
    if crc8(buf[:-1]) != buf[-1]:
        raise ValueError("CRC mismatch")
    f = _BODY.unpack(buf[:-1])
    speed, start = f[2], f[3] / 100.0
    end, ts = f[4 + 2 * POINTS] / 100.0, f[5 + 2 * POINTS]
    span = (end - start) % 360.0
    angles = (start + span * np.arange(POINTS) / (POINTS - 1)) % 360.0
    dist = np.array(f[4:4 + 2 * POINTS:2], dtype=np.float32)
    inten = np.array(f[5:5 + 2 * POINTS:2], dtype=np.uint8)
    return Packet(speed, angles, dist, inten, ts)


def build(speed_dps: int, start_deg: float, end_deg: float, distances_mm, intensities, timestamp_ms: int) -> bytes:
    vals = [HEADER, VER_LEN, speed_dps & 0xFFFF, int(round(start_deg * 100)) % 36000]
    for d, i in zip(distances_mm, intensities):
        vals += [int(d) & 0xFFFF, int(i) & 0xFF]
    vals += [int(round(end_deg * 100)) % 36000, int(timestamp_ms) % 30000]
    body = _BODY.pack(*vals)
    return body + bytes([crc8(body)])


def split(payload: bytes) -> list[bytes]:
    """Split a LIDAR message payload (after its model byte) into 47-byte packets."""
    return [payload[i:i + SIZE] for i in range(0, len(payload) - SIZE + 1, SIZE)]
