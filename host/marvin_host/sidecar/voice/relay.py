"""The robot's audio as the core relays it: what robot_audio.py expects from a receiver.

The Java core owns the robot's UDP socket. It relays each AUDIO_IN to the sidecar as an
`AudioFrame` (same samples, sample index and robot clock), and turns the sidecar's
`SpeakerFrame`, `RobotAudioCtrl` and `RobotSound` back into AUDIO_OUT, AUDIO_CTRL and SOUND.
`RobotRelay` stands in for the receiver of robot_audio.py, so the robot's microphone
(`RobotMicSource`: gap filling, restarts, the MIC_START keepalive) and speaker
(`RobotSpeakerSink`: 150 ms lead pacing, stream ids, PLAY_STOP) are the same code as in the
Python host.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import threading
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Callable

import numpy as np

from ... import protocol
from .contract import voice_pb2 as pb

log = logging.getLogger("marvin.sidecar.voice")


@dataclass
class RobotDevice:
    """A robot the core has linked; `addr` and `hello.flags` are what robot_audio.py looks at."""

    name: str
    has_audio: bool = False
    hello: SimpleNamespace = field(init=False)

    def __post_init__(self):
        self.hello = SimpleNamespace(flags=protocol.FLAG_AUDIO if self.has_audio else 0)

    @property
    def addr(self) -> str:
        return self.name


class RobotRelay:
    """See the module docstring. `send(message)` delivers a `VoiceToCore` to the core."""

    def __init__(self, send: Callable[[pb.VoiceToCore], None]):
        self._send = send
        self._lock = threading.Lock()
        self.devices: dict[str, RobotDevice] = {}
        self._audio_listeners: list[Callable] = []
        self.frames_in = 0
        self.frames_out = 0

    # the core's side

    def link(self, name: str, connected: bool, has_audio: bool) -> bool:
        """A robot connected or left. True if what is known changed."""
        with self._lock:
            old = self.devices.get(name)
            if not connected:
                return self.devices.pop(name, None) is not None
            if old is not None and old.has_audio == has_audio:
                return False
            self.devices[name] = RobotDevice(name, has_audio)
            return True

    def audio_device(self) -> RobotDevice | None:
        """The first linked robot with a microphone and a speaker."""
        with self._lock:
            return next((d for d in self.devices.values() if d.has_audio), None)

    def on_frame(self, frame: pb.AudioFrame) -> None:
        """A relayed AUDIO_IN."""
        with self._lock:
            dev = self.devices.get(frame.device)
            listeners = list(self._audio_listeners)
        if dev is None or len(frame.pcm) % 2:
            return
        self.frames_in += 1
        pcm = np.frombuffer(frame.pcm, dtype="<i2").astype(np.int16)
        for fn in listeners:
            try:
                fn(dev, frame.robot_time_us, frame.sample_index, pcm)
            except Exception:                   # noqa: BLE001 - one listener must not stop the others
                log.exception("robot audio listener failed")

    # the receiver robot_audio.py expects

    def add_audio_listener(self, fn: Callable) -> None:
        with self._lock:
            self._audio_listeners.append(fn)

    def remove_audio_listener(self, fn: Callable) -> None:
        with self._lock:
            if fn in self._audio_listeners:
                self._audio_listeners.remove(fn)

    def send(self, dev: RobotDevice, msg_type: int, payload: bytes) -> None:
        """What the Python host would send to the robot, as a message for the core to relay."""
        if msg_type == protocol.AUDIO_OUT:
            out = protocol.AudioOut.decode(payload)
            self.frames_out += 1
            msg = pb.VoiceToCore(robot_speaker=pb.SpeakerFrame(
                device=dev.name, stream=out.stream, sample_index=out.index,
                pcm=np.asarray(out.pcm, dtype="<i2").tobytes()))
        elif msg_type == protocol.AUDIO_CTRL:
            msg = pb.VoiceToCore(ctrl=pb.RobotAudioCtrl(device=dev.name, command=payload[0], argument=payload[1]))
        elif msg_type == protocol.SOUND:
            msg = pb.VoiceToCore(sound=pb.RobotSound(device=dev.name, id=payload[0]))
        else:
            raise ValueError(f"the voice does not send message type 0x{msg_type:02x} to the robot")
        self._send(msg)
