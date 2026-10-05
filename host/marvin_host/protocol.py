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
BUTTON = 0x07       # device -> host: a button was pressed, u8 button + u8 action (see Button)
ENVIRONMENT = 0x08  # device -> host: the room sensors, PIR / temperature and humidity / light (see Environment)
HEAD = 0x09         # device -> host: the head's pose, 5 Hz and on a change (see Head)
HOST_ACK = 0x81     # host -> device: u64 host clock (us); the device then unicasts to the sender
FACE_STATE = 0x82   # host -> device: presence state for the face, ~10 Hz (see FaceState)
FACE_EVENT = 0x83   # host -> device: one brain event, u8 code (see FACE_EVENT_CODES)
AUDIO_OUT = 0x84    # host -> device: PCM to play, u16 stream id + u32 sample index + N x i16 (see AudioOut)
AUDIO_CTRL = 0x85   # host -> device: u8 command + u8 argument (see audio_ctrl)
SOUND = 0x86        # host -> device: play a built-in sound, u8 id (see SOUNDS)
HEAD_CTRL = 0x87    # host -> device: u8 head mode + i16 yaw (0.1 deg) (see head_ctrl)

# HELLO board ids
BOARDS = {1: "Wemos D1 mini (ESP8266)", 2: "ESP32-S3 DevKitC", 3: "XIAO ESP32S3 Sense", 4: "MR60BHA2 kit (XIAO ESP32C6)",
          5: "ESP32 DevKit (ESP32-WROOM-32)", 6: "USB sensor bridge (computer)", 255: "simulator"}
# a lidar or a radar on a USB-serial adapter, bridged by this computer (usb_sensors.py, docs/usb-sensors.md)
BOARD_USB_BRIDGE = 6
# LIDAR model ids
LIDAR_MODELS = {1: "D500 (STL-19P)", 2: "D800 (STL-27L)"}
# HELLO flags: bit 0 simulated sensor data; bits 1 to 6 are capabilities (older robots send 0)
FLAG_SIMULATED = 0x01
FLAG_CAMERA = 0x02     # MJPEG camera at camera_url(ip)
FLAG_AUDIO = 0x04      # speaker and microphone: AUDIO_IN / AUDIO_OUT / AUDIO_CTRL / SOUND
FLAG_BUTTONS = 0x08    # buttons: BUTTON
FLAG_BUZZER = 0x10     # a buzzer that plays SOUND (no AUDIO_OUT, no microphone)
FLAG_FACE = 0x20       # the face on a small screen of its own: FACE_STATE / FACE_EVENT, whatever the board
FLAG_ENVIRONMENT = 0x40  # room sensors: ENVIRONMENT (which ones: its sensors byte)
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
# HELLO board ids that have the face screen: the host sends them FACE_STATE and FACE_EVENT (and to any
# device whose HELLO has FLAG_FACE)
SCREEN_BOARDS = frozenset({2, 3})

# BUTTON: button ids and actions. The host ignores the ones it does not know.
BUTTONS = {1: "talk", 2: "mute"}
BUTTON_TALK = 1         # the app's Talk now
BUTTON_MUTE = 2         # mute or unmute the microphone
BUTTON_PRESS = 1

# ENVIRONMENT: the sensors a device has (sensors byte) and what its readings say (flags byte)
ENV_PIR = 0x01          # a PIR motion sensor
ENV_CLIMATE = 0x02      # a temperature and humidity sensor (DHT11)
ENV_LIGHT = 0x04        # a light sensor (LDR)
ENV_SENSORS = {ENV_PIR: "pir", ENV_CLIMATE: "climate", ENV_LIGHT: "light"}
ENV_MOTION = 0x01       # the PIR sees motion now
ENV_WARMING_UP = 0x02   # the PIR is still warming up (about a minute after power-on): no motion reported
ENV_TEMPERATURE_VALID = 0x04
ENV_HUMIDITY_VALID = 0x08
ENV_LIGHT_VALID = 0x10
ENV_SIMULATED = 0x20    # the readings are simulated
ENV_NO_MOTION = 0xFFFF  # motion age: none since the PIR warmed up

# HEAD: the head's modes (HEAD and HEAD_CTRL) and flags. No HELLO flag: a device with a head sends HEAD.
HEAD_AUTO = 1           # follows the nearest LD2450 target, glances around now and then when nobody is there
HEAD_MANUAL = 2         # holds the yaw HEAD_CTRL gave, until another HEAD_CTRL or the host is lost
HEAD_CENTER = 3         # straight ahead
HEAD_MODES = {HEAD_AUTO: "auto", HEAD_MANUAL: "manual", HEAD_CENTER: "center"}
HEAD_TARGET = 0x01      # the last LD2450 frame had a target: target yaw and distance valid
HEAD_TRACKING = 0x02    # following a target (seen in the last 3 s)
HEAD_GLANCING = 0x04    # an idle glance around
HEAD_SERVO_ON = 0x08    # the servo is driven (off at boot and while the head rests)

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
    def has_buttons(self) -> bool:
        return bool(self.flags & FLAG_BUTTONS)

    @property
    def has_buzzer(self) -> bool:
        return bool(self.flags & FLAG_BUZZER)

    @property
    def has_face(self) -> bool:
        """Shows the face: a board with the screen, or a device announcing one (FLAG_FACE)."""
        return self.board in SCREEN_BOARDS or bool(self.flags & FLAG_FACE)

    @property
    def has_environment(self) -> bool:
        return bool(self.flags & FLAG_ENVIRONMENT)

    @property
    def device_name(self) -> str:
        return "marvin-" + self.device_id[-3:].hex()


@dataclass
class Button:
    """BUTTON payload: button (u8, BUTTONS) and action (u8, BUTTON_PRESS). Longer payloads keep this prefix."""

    button: int
    action: int = BUTTON_PRESS

    SIZE = 2

    def encode(self) -> bytes:
        return bytes([self.button & 0xFF, self.action & 0xFF])

    @classmethod
    def decode(cls, payload: bytes) -> "Button":
        if len(payload) < cls.SIZE:
            raise ProtocolError("BUTTON too short")
        return cls(payload[0], payload[1])

    @property
    def name(self) -> str | None:
        """talk, mute, or None for a button this host does not know."""
        return BUTTONS.get(self.button)


@dataclass
class Environment:
    """ENVIRONMENT payload, 12 bytes: the room sensors of a device (docs/protocol.md).

    sensors (u8: ENV_PIR, ENV_CLIMATE, ENV_LIGHT: the sensors it has), flags (u8: ENV_MOTION, ENV_WARMING_UP,
    ENV_*_VALID, ENV_SIMULATED), temperature (i16, 0.1 degC), humidity (u16, 0.1 %RH), light level (u16,
    0..1000, higher is brighter), light raw (u16, the ADC's 0..4095), seconds since the last motion (u16, 0
    while there is motion, ENV_NO_MOTION: none since the PIR warmed up). Invalid readings are None here and 0
    on the wire. A longer payload keeps this prefix.
    """

    pir: bool = False
    climate: bool = False
    light_sensor: bool = False
    motion: bool = False
    warming_up: bool = False
    temperature_c: float | None = None
    humidity: float | None = None          # %RH
    light: float | None = None             # 0 (dark) .. 1 (full scale)
    light_raw: int | None = None
    motion_age_s: int | None = None        # None: no motion since the PIR warmed up (or no PIR)
    simulated: bool = False

    _S = struct.Struct("<BBhHHHH")
    SIZE = 12

    def encode(self) -> bytes:
        sensors = ENV_PIR * self.pir | ENV_CLIMATE * self.climate | ENV_LIGHT * self.light_sensor
        flags = (ENV_MOTION * self.motion | ENV_WARMING_UP * self.warming_up
                 | ENV_TEMPERATURE_VALID * (self.temperature_c is not None)
                 | ENV_HUMIDITY_VALID * (self.humidity is not None)
                 | ENV_LIGHT_VALID * (self.light is not None) | ENV_SIMULATED * self.simulated)
        t = max(-32768, min(32767, round(self.temperature_c * 10))) if self.temperature_c is not None else 0
        h = max(0, min(0xFFFF, round(self.humidity * 10))) if self.humidity is not None else 0
        lv = max(0, min(1000, round(self.light * 1000))) if self.light is not None else 0
        raw = max(0, min(0xFFFF, int(self.light_raw))) if self.light is not None and self.light_raw is not None else 0
        age = ENV_NO_MOTION if self.motion_age_s is None else max(0, min(0xFFFE, int(self.motion_age_s)))
        return self._S.pack(sensors, flags, t, h, lv, raw, age)

    @classmethod
    def decode(cls, payload: bytes) -> "Environment":
        if len(payload) < cls.SIZE:
            raise ProtocolError("ENVIRONMENT too short")
        sensors, flags, t, h, lv, raw, age = cls._S.unpack_from(payload)
        light_ok = bool(flags & ENV_LIGHT_VALID)
        return cls(bool(sensors & ENV_PIR), bool(sensors & ENV_CLIMATE), bool(sensors & ENV_LIGHT),
                   bool(flags & ENV_MOTION), bool(flags & ENV_WARMING_UP),
                   t / 10 if flags & ENV_TEMPERATURE_VALID else None,
                   h / 10 if flags & ENV_HUMIDITY_VALID else None,
                   lv / 1000 if light_ok else None, raw if light_ok else None,
                   None if age == ENV_NO_MOTION else age, bool(flags & ENV_SIMULATED))


@dataclass
class Head:
    """HEAD payload, 10 bytes: where the robot's head looks (docs/protocol.md).

    mode (u8, HEAD_MODES), flags (u8: HEAD_TARGET, HEAD_TRACKING, HEAD_GLANCING, HEAD_SERVO_ON), yaw (i16, 0.1
    deg: 0 straight ahead, positive towards the device frame's +x), goal (i16, 0.1 deg: where it is going),
    target yaw (i16, 0.1 deg) and distance (u16, mm), 0 on the wire and None here without HEAD_TARGET. A longer
    payload keeps this prefix.
    """

    mode: int = HEAD_AUTO
    yaw_deg: float = 0.0
    goal_deg: float = 0.0
    target_yaw_deg: float | None = None
    target_distance_mm: int | None = None
    tracking: bool = False
    glancing: bool = False
    servo_on: bool = False

    _S = struct.Struct("<BBhhhH")
    SIZE = 10

    def encode(self) -> bytes:
        def dd(v):
            return max(-32767, min(32767, round(v * 10)))
        target = self.target_yaw_deg is not None
        flags = (HEAD_TARGET * target | HEAD_TRACKING * self.tracking | HEAD_GLANCING * self.glancing
                 | HEAD_SERVO_ON * self.servo_on)
        return self._S.pack(self.mode & 0xFF, flags, dd(self.yaw_deg), dd(self.goal_deg),
                            dd(self.target_yaw_deg) if target else 0,
                            max(0, min(0xFFFF, int(self.target_distance_mm or 0))) if target else 0)

    @classmethod
    def decode(cls, payload: bytes) -> "Head":
        if len(payload) < cls.SIZE:
            raise ProtocolError("HEAD too short")
        mode, flags, yaw, goal, tyaw, tdist = cls._S.unpack_from(payload)
        target = bool(flags & HEAD_TARGET)
        return cls(mode, yaw / 10, goal / 10, tyaw / 10 if target else None, tdist if target else None,
                   bool(flags & HEAD_TRACKING), bool(flags & HEAD_GLANCING), bool(flags & HEAD_SERVO_ON))

    @property
    def mode_name(self) -> str | None:
        """auto, manual, center, or None for a mode this host does not know."""
        return HEAD_MODES.get(self.mode)


def head_ctrl(mode: int, yaw_deg: float = 0.0) -> bytes:
    """HEAD_CTRL payload: a head mode (HEAD_AUTO, HEAD_MANUAL, HEAD_CENTER) and, for HEAD_MANUAL, the yaw."""
    return struct.pack("<Bh", mode & 0xFF, max(-32767, min(32767, round(yaw_deg * 10))))


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
