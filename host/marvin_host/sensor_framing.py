"""Stream framers for the sensor serial lines: they cut a raw byte stream into complete, valid frames.

The Python mirror of firmware/src/sensor_framing.{h,cpp}, with the same counters, so the USB sensor bridge
(usb_sensors.py) frames the lidar and the radar exactly as the robot does:

- LidarFramer: LDROBOT 47-byte packets (D500 / STL-19P, D800 / STL-27L), header 54 2C, CRC-8 polynomial 0x4D
  (ldrobot.crc8).
- Ld2450Framer: HLK-LD2450 30-byte target frames (AA FF 03 00 ... 55 CC) and the command ACK frames
  (FD FC FB FA, length, data, 04 03 02 01).
- ld2450 command builders (enable configuration, multi-target tracking, end configuration).

Both framers resynchronise after garbage, partial or corrupted frames without losing the next valid one: when a
candidate frame turns out to be wrong, only its first byte is dropped and the bytes after it are scanned again.
The firmware examines one byte at a time; here a chunk is scanned with `bytes.find`, which gives the same frames
and the same counters (host/tests/test_sensor_framing.py repeats the firmware's native tests).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import struct
from . import ld2450, ldrobot

LIDAR_PACKET = ldrobot.SIZE                 # 47
LD2450_FRAME = ld2450.SIZE                  # 30
LIDAR_HEAD = bytes([ldrobot.HEADER, ldrobot.VER_LEN])
TARGETS_HEAD = ld2450.HEAD
TARGETS_TAIL = ld2450.TAIL
CMD_HEAD = b"\xFD\xFC\xFB\xFA"
CMD_TAIL = b"\x04\x03\x02\x01"
MAX_ACK_DATA = 64                           # in-frame data bytes of an ACK; the protocol's largest is 30

# verdicts on the bytes at one position
_INVALID, _INCOMPLETE, _VALID, _BAD = range(4)


class _StreamFramer:
    """Shared resynchronising framer. Subclasses give the bytes a frame can start with and `_classify`."""

    STARTS: bytes = b""

    def __init__(self) -> None:
        self._buf = bytearray()
        self.dropped_bytes = 0              # discarded while looking for frames (garbage, rejected frames)

    @property
    def pending(self) -> int:
        """Bytes of an incomplete frame currently held."""
        return len(self._buf)

    def reset(self) -> None:
        self._buf.clear()

    def _classify(self, buf: bytearray, i: int) -> tuple[int, int]:
        """(verdict, frame length) for the candidate starting at buf[i]."""
        raise NotImplementedError

    def _accept(self, frame: bytes) -> object:
        raise NotImplementedError

    def _reject(self) -> None:
        raise NotImplementedError

    def _next_start(self, buf: bytearray, i: int) -> int:
        found = [j for j in (buf.find(s, i) for s in self.STARTS) if j >= 0]
        return min(found) if found else len(buf)

    def feed(self, data: bytes) -> list:
        """Feeds a chunk of bytes; returns the complete valid frames it finished, in order."""
        buf = self._buf
        buf += data
        out = []
        i, n = 0, len(buf)
        while i < n:
            j = self._next_start(buf, i)
            self.dropped_bytes += j - i
            i = j
            if i >= n:
                break
            verdict, size = self._classify(buf, i)
            if verdict == _INCOMPLETE:
                break
            if verdict == _VALID:
                out.append(self._accept(bytes(buf[i:i + size])))
                i += size
                continue
            if verdict == _BAD:
                self._reject()
            self.dropped_bytes += 1          # resynchronise: drop the first byte, look again after it
            i += 1
        del buf[:i]
        return out


class LidarFramer(_StreamFramer):
    """LDROBOT lidar packets: `feed` returns 47-byte packets."""

    STARTS = LIDAR_HEAD[:1]

    def __init__(self) -> None:
        super().__init__()
        self.packets = 0
        self.crc_errors = 0

    def _next_start(self, buf: bytearray, i: int) -> int:
        j = buf.find(LIDAR_HEAD[0], i)
        return len(buf) if j < 0 else j

    def _classify(self, buf: bytearray, i: int) -> tuple[int, int]:
        avail = len(buf) - i
        if avail >= 2 and buf[i + 1] != LIDAR_HEAD[1]:
            return _INVALID, 0
        if avail < LIDAR_PACKET:
            return _INCOMPLETE, 0
        ok = ldrobot.crc8(buf[i:i + LIDAR_PACKET - 1]) == buf[i + LIDAR_PACKET - 1]
        return (_VALID if ok else _BAD), LIDAR_PACKET

    def _accept(self, frame: bytes) -> bytes:
        self.packets += 1
        return frame

    def _reject(self) -> None:
        self.crc_errors += 1


TARGETS = 0
ACK = 1


class Ld2450Framer(_StreamFramer):
    """HLK-LD2450 frames: `feed` returns (kind, frame) pairs, kind TARGETS (30 bytes) or ACK (header to footer)."""

    STARTS = TARGETS_HEAD[:1] + CMD_HEAD[:1]

    def __init__(self) -> None:
        super().__init__()
        self.frames = 0
        self.acks = 0
        self.bad_frames = 0                 # the right header but a wrong footer or length

    def _next_start(self, buf: bytearray, i: int) -> int:
        a, b = buf.find(TARGETS_HEAD[0], i), buf.find(CMD_HEAD[0], i)
        found = [j for j in (a, b) if j >= 0]
        return min(found) if found else len(buf)

    def _classify(self, buf: bytearray, i: int) -> tuple[int, int]:
        avail = len(buf) - i
        for head in (TARGETS_HEAD, CMD_HEAD):
            k = min(avail, len(head))
            if buf[i:i + k] == head[:k]:
                break
        else:
            return _INVALID, 0
        if head == TARGETS_HEAD:
            if avail < LD2450_FRAME:
                return _INCOMPLETE, 0
            ok = buf[i + LD2450_FRAME - 2:i + LD2450_FRAME] == TARGETS_TAIL
            return (_VALID if ok else _BAD), LD2450_FRAME
        if avail < 6:
            return _INCOMPLETE, 0
        data = buf[i + 4] | buf[i + 5] << 8
        if data < 4 or data > MAX_ACK_DATA:
            return _BAD, 0
        total = 4 + 2 + data + 4
        if avail < total:
            return _INCOMPLETE, 0
        ok = buf[i + total - 4:i + total] == CMD_TAIL
        return (_VALID if ok else _BAD), total

    def _accept(self, frame: bytes) -> tuple[int, bytes]:
        kind = TARGETS if frame[0] == TARGETS_HEAD[0] else ACK
        if kind == TARGETS:
            self.frames += 1
        else:
            self.acks += 1
        return kind, frame

    def _reject(self) -> None:
        self.bad_frames += 1


# ---- HLK-LD2450 configuration commands (firmware: namespace framing::ld2450_cmd) ----------------

ENABLE_CONFIG = 0x00FF      # value 0x0001
END_CONFIG = 0x00FE
SINGLE_TARGET = 0x0080
MULTI_TARGET = 0x0090
QUERY_MODE = 0x0091


def ld2450_command(command: int, value: bytes = b"") -> bytes:
    """A command frame: FD FC FB FA, in-frame length (u16 LE), command word (u16 LE), value, 04 03 02 01."""
    value = value[:16]
    return CMD_HEAD + struct.pack("<HH", 2 + len(value), command) + value + CMD_TAIL


def ld2450_multi_target_sequence() -> bytes:
    """What the firmware sends the radar at boot: enable configuration, multi-target tracking, end configuration."""
    return (ld2450_command(ENABLE_CONFIG, b"\x01\x00") + ld2450_command(MULTI_TARGET)
            + ld2450_command(END_CONFIG))


def parse_ack(frame: bytes) -> tuple[int, int] | None:
    """(command it answers, status: 0 = success) of an ACK frame, or None if it is not a well-formed ACK."""
    if len(frame) < 14 or frame[:4] != CMD_HEAD:
        return None
    data = frame[4] | frame[5] << 8
    if data < 4 or len(frame) != 4 + 2 + data + 4 or frame[-4:] != CMD_TAIL:
        return None
    word, status = struct.unpack_from("<HH", frame, 6)
    if not word & 0x0100:
        return None
    return word & ~0x0100 & 0xFFFF, status

