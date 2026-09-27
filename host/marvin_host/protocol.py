"""Marvin UDP protocol, version 1. The firmware mirrors this file in firmware/src/protocol.h.

Every datagram starts with a 16-byte little-endian header:

    offset  size  field
    0       2     magic, b"MV"
    2       1     protocol version (1)
    3       1     message type
    4       4     sequence number (per sender, wraps)
    8       8     sender clock, microseconds since boot

followed by the payload. See docs/protocol.md.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import struct
from dataclasses import dataclass

MAGIC = b"MV"
VERSION = 1
HOST_PORT = 47100      # the host listens here
DEVICE_PORT = 47101    # the robot listens here

HEADER = struct.Struct("<2sBBIQ")

# message types
HELLO = 0x01        # device -> host (broadcast until acknowledged, then heartbeat)
LIDAR = 0x02        # device -> host: u8 lidar model + N raw 47-byte LDROBOT packets
LD2450 = 0x03       # device -> host: one raw 30-byte HLK-LD2450 frame
LOG = 0x04          # device -> host: UTF-8 text
HOST_ACK = 0x81     # host -> device: u64 host clock (us); the device then unicasts to the sender

# HELLO board ids
BOARDS = {1: "Wemos D1 mini (ESP8266)", 2: "ESP32-S3 DevKitC", 3: "XIAO ESP32S3 Sense", 255: "simulator"}
# LIDAR model ids
LIDAR_MODELS = {1: "D500 (STL-19P)", 2: "D800 (STL-27L)"}
FLAG_SIMULATED = 0x01


class ProtocolError(ValueError):
    pass


@dataclass
class Header:
    type: int
    seq: int
    t_us: int


def pack(msg_type: int, seq: int, t_us: int, payload: bytes = b"") -> bytes:
    return HEADER.pack(MAGIC, VERSION, msg_type, seq & 0xFFFFFFFF, t_us) + payload


def unpack(datagram: bytes) -> tuple[Header, bytes]:
    if len(datagram) < HEADER.size:
        raise ProtocolError("datagram shorter than the header")
    magic, version, msg_type, seq, t_us = HEADER.unpack_from(datagram)
    if magic != MAGIC:
        raise ProtocolError("bad magic")
    if version != VERSION:
        raise ProtocolError(f"unsupported protocol version {version}")
    return Header(msg_type, seq, t_us), datagram[HEADER.size:]


@dataclass
class Hello:
    device_id: bytes          # 6 bytes, the Wi-Fi MAC address
    board: int
    flags: int
    rssi: int
    uptime_ms: int
    firmware: str

    _S = struct.Struct("<6sBBbI")

    def encode(self) -> bytes:
        fw = self.firmware.encode()[:255]
        return self._S.pack(self.device_id, self.board, self.flags, self.rssi, self.uptime_ms) + bytes([len(fw)]) + fw

    @classmethod
    def decode(cls, payload: bytes) -> "Hello":
        dev, board, flags, rssi, uptime = cls._S.unpack_from(payload)
        n = payload[cls._S.size]
        fw = payload[cls._S.size + 1: cls._S.size + 1 + n].decode(errors="replace")
        return cls(dev, board, flags, rssi, uptime, fw)

    @property
    def simulated(self) -> bool:
        return bool(self.flags & FLAG_SIMULATED)

    @property
    def device_name(self) -> str:
        return "marvin-" + self.device_id[-3:].hex()
