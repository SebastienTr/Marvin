"""A minimal PNG encoder (stdlib zlib + numpy), so the app needs no imaging library.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import struct
import zlib

import numpy as np


def encode(rgb: np.ndarray, level: int = 6) -> bytes:
    """(H, W, 3) or (H, W, 4) uint8 array -> PNG bytes (8-bit RGB / RGBA, no filtering)."""
    a = np.ascontiguousarray(rgb, dtype=np.uint8)
    if a.ndim != 3 or a.shape[2] not in (3, 4):
        raise ValueError(f"expected (H, W, 3|4), got {a.shape}")
    h, w, c = a.shape
    raw = np.zeros((h, w * c + 1), np.uint8)          # each row starts with filter type 0
    raw[:, 1:] = a.reshape(h, w * c)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2 if c == 3 else 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr)
            + _chunk(b"IDAT", zlib.compress(raw.tobytes(), level)) + _chunk(b"IEND", b""))


def _chunk(tag: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
