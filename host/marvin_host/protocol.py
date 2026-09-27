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

import numpy as np

from .events import EventKind, PresenceState

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
VITALS = 0x05       # device -> host: MR60BHA2 readings (see Vitals)
AUDIO_IN = 0x06     # device -> host: microphone PCM, u32 sample index + 320 x i16 (see AudioIn)
HOST_ACK = 0x81     # host -> device: u64 host clock (us); the device then unicasts to the sender
FACE_STATE = 0x82   # host -> device: presence state for the face, ~10 Hz (see FaceState)
FACE_EVENT = 0x83   # host -> device: one brain event, u8 code (see FACE_EVENT_CODES)
AUDIO_OUT = 0x84    # host -> device: PCM to play, u16 stream id + u32 sample index + N x i16 (see AudioOut)
AUDIO_CTRL = 0x85   # host -> device: u8 command + u8 argument (see audio_ctrl)
SOUND = 0x86        # host -> device: play a built-in sound, u8 id (see SOUNDS)

# HELLO board ids
BOARDS = {1: "Wemos D1 mini (ESP8266)", 2: "ESP32-S3 DevKitC", 3: "XIAO ESP32S3 Sense", 4: "MR60BHA2 kit (XIAO ESP32C6)",
          255: "simulator"}
# LIDAR model ids
LIDAR_MODELS = {1: "D500 (STL-19P)", 2: "D800 (STL-27L)"}
# HELLO flags: bit 0 simulated sensor data; bits 1 and 2 are capabilities (older robots send 0)
FLAG_SIMULATED = 0x01
FLAG_CAMERA = 0x02     # MJPEG camera at camera_url(ip)
FLAG_AUDIO = 0x04      # speaker and microphone: AUDIO_IN / AUDIO_OUT / AUDIO_CTRL / SOUND
CAMERA_PORT = 81

# Audio, both directions: 16 kHz mono signed 16-bit little-endian PCM (same as audio.py)
AUDIO_RATE = 16_000
AUDIO_IN_SAMPLES = 320          # 20 ms per AUDIO_IN datagram
AUDIO_OUT_MAX_SAMPLES = 480     # at most 30 ms (960 bytes) per AUDIO_OUT datagram

# AUDIO_CTRL commands (payload: command u8, argument u8)
AUDIO_MIC_START = 1     # start (or keep) streaming AUDIO_IN; repeat it every second while listening
AUDIO_MIC_STOP = 2
AUDIO_PLAY_STOP = 3     # drop queued AUDIO_OUT and the sound being played
AUDIO_VOLUME = 4        # argument: volume 0..100 (hard-capped in firmware for the 1 W speaker)
AUDIO_MIC_GAIN = 5      # argument: microphone gain, dB 0..36

# SOUND ids: name -> (id, duration in ms). firmware/src/audio/earcons.h, docs/audio.md.
SOUNDS: dict[str, tuple[int, int]] = {
    "chirp": (1, 140),
    "beep": (2, 120),
    "wake": (3, 200),
    "done": (4, 200),
    "error": (5, 360),
    "hello": (6, 430),
}
# HELLO board ids that have the face screen: the host sends them FACE_STATE and FACE_EVENT
SCREEN_BOARDS = frozenset({2, 3})

# FACE_EVENT codes, one table for both sides (firmware/src/face/face.h, enum face::Event). 0 is unused;
# the robot ignores codes it does not know.
FACE_EVENT_CODES: dict[EventKind, int] = {
    EventKind.ARRIVED: 1,
    EventKind.LEFT: 2,
    EventKind.APPROACHED: 3,
    EventKind.SAT_DOWN: 4,
    EventKind.STOOD_UP: 5,
    EventKind.STILL_LONG: 6,
    EventKind.VITALS_ACQUIRED: 7,
    EventKind.VITALS_LOST: 8,
}
FACE_EVENT_KINDS: dict[int, EventKind] = {v: k for k, v in FACE_EVENT_CODES.items()}


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
    def has_camera(self) -> bool:
        return bool(self.flags & FLAG_CAMERA)

    @property
    def has_audio(self) -> bool:
        return bool(self.flags & FLAG_AUDIO)

    @property
    def device_name(self) -> str:
        return "marvin-" + self.device_id[-3:].hex()


def camera_url(ip: str, path: str = "/stream") -> str:
    """The robot's camera: `/stream` (MJPEG, multipart/x-mixed-replace) or `/capture` (one JPEG)."""
    return f"http://{ip}:{CAMERA_PORT}{path}"


@dataclass
class Vitals:
    """MR60BHA2 60 GHz radar readings. Rates in breaths / beats per minute, waves in -1..1."""

    valid: bool               # a still person is measured
    breath_rate: float
    heart_rate: float
    breath_wave: float
    heart_wave: float
    distance_mm: int

    _S = struct.Struct("<BHHhhH")

    def encode(self) -> bytes:
        return self._S.pack(int(self.valid), round(self.breath_rate * 100), round(self.heart_rate * 100),
                            round(max(-1, min(1, self.breath_wave)) * 32767),
                            round(max(-1, min(1, self.heart_wave)) * 32767), min(int(self.distance_mm), 0xFFFF))

    @classmethod
    def decode(cls, payload: bytes) -> "Vitals":
        v, br, hr, bw, hw, d = cls._S.unpack_from(payload)
        return cls(bool(v & 1), br / 100, hr / 100, bw / 32767, hw / 32767, d)


@dataclass
class FaceState:
    """FACE_STATE payload: what the face needs from `PresenceState`, 17 bytes.

    flags (u8): bit 0 present, 1 seated, 2 head valid, 3 position valid, 4 distance valid,
    5 heart rate valid; head x, y, z (3 x i16, mm, device frame); position x, y, z (3 x i16, mm);
    distance (u16, mm); heart rate (u16, 0.01/min). Invalid fields are sent as 0.
    """

    present: bool = False
    seated: bool = False
    head: tuple[float, float, float] | None = None
    position: tuple[float, float, float] | None = None
    distance_m: float | None = None
    heart_rate: float | None = None

    _S = struct.Struct("<B3h3hHH")
    SIZE = 17

    @classmethod
    def from_presence(cls, s: PresenceState) -> "FaceState":
        return cls(s.present, s.seated, s.head, s.position, s.distance_m, s.heart_rate)

    def encode(self) -> bytes:
        def mm(p):
            return [max(-32767, min(32767, round(v))) for v in p] if p is not None else [0, 0, 0]

        flags = (self.present | self.seated << 1 | (self.head is not None) << 2 | (self.position is not None) << 3
                 | (self.distance_m is not None) << 4 | (self.heart_rate is not None) << 5)
        dist = min(0xFFFF, max(0, round(self.distance_m * 1000))) if self.distance_m is not None else 0
        hr = min(0xFFFF, max(0, round(self.heart_rate * 100))) if self.heart_rate is not None else 0
        return self._S.pack(flags, *mm(self.head), *mm(self.position), dist, hr)

    @classmethod
    def decode(cls, payload: bytes) -> "FaceState":
        flags, hx, hy, hz, px, py, pz, dist, hr = cls._S.unpack_from(payload)
        return cls(bool(flags & 1), bool(flags & 2),
                   (float(hx), float(hy), float(hz)) if flags & 4 else None,
                   (float(px), float(py), float(pz)) if flags & 8 else None,
                   dist / 1000 if flags & 16 else None,
                   hr / 100 if flags & 32 else None)


def face_event(kind: EventKind) -> bytes | None:
    """FACE_EVENT payload for an event, or None if the robot has no code for it."""
    code = FACE_EVENT_CODES.get(kind)
    return None if code is None else bytes([code])


@dataclass
class AudioIn:
    """AUDIO_IN payload: sample index (u32, samples since the stream started, wraps) + PCM.

    The header's t_us is the robot time of the first sample. The robot restarts the index at 0
    each time the microphone starts.
    """

    index: int
    pcm: np.ndarray             # int16, AUDIO_IN_SAMPLES samples normally

    def encode(self) -> bytes:
        return struct.pack("<I", self.index & 0xFFFFFFFF) + np.asarray(self.pcm, dtype="<i2").tobytes()

    @classmethod
    def decode(cls, payload: bytes) -> "AudioIn":
        if len(payload) < 4 or (len(payload) - 4) % 2:
            raise ProtocolError("bad AUDIO_IN length")
        (index,) = struct.unpack_from("<I", payload)
        return cls(index, np.frombuffer(payload, dtype="<i2", offset=4).astype(np.int16))


@dataclass
class AudioOut:
    """AUDIO_OUT payload: stream id (u16), sample index of the first sample in that stream (u32), PCM.

    A new stream id makes the robot drop what it has queued; the index lets it place each datagram
    (a lost one becomes silence, a late one is dropped). 1 to AUDIO_OUT_MAX_SAMPLES samples.
    """

    stream: int
    index: int
    pcm: np.ndarray

    def encode(self) -> bytes:
        pcm = np.asarray(self.pcm, dtype="<i2")
        if not 1 <= len(pcm) <= AUDIO_OUT_MAX_SAMPLES:
            raise ProtocolError(f"AUDIO_OUT carries 1 to {AUDIO_OUT_MAX_SAMPLES} samples, not {len(pcm)}")
        return struct.pack("<HI", self.stream & 0xFFFF, self.index & 0xFFFFFFFF) + pcm.tobytes()

    @classmethod
    def decode(cls, payload: bytes) -> "AudioOut":
        n2 = len(payload) - 6
        if n2 < 2 or n2 % 2 or n2 // 2 > AUDIO_OUT_MAX_SAMPLES:
            raise ProtocolError("bad AUDIO_OUT length")
        stream, index = struct.unpack_from("<HI", payload)
        return cls(stream, index, np.frombuffer(payload, dtype="<i2", offset=6).astype(np.int16))


def audio_ctrl(command: int, argument: int = 0) -> bytes:
    """AUDIO_CTRL payload."""
    return bytes([command & 0xFF, max(0, min(255, int(argument)))])


def sound(name_or_id: str | int) -> bytes:
    """SOUND payload for a sound name (SOUNDS) or a raw id."""
    sid = SOUNDS[name_or_id][0] if isinstance(name_or_id, str) else int(name_or_id)
    return bytes([sid & 0xFF])
