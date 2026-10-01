"""One audio device: a microphone and a speaker that go together, and how to listen on them without
hearing Marvin's own voice.

The voice hears and speaks through one device at a time: the computer's (`MicSource`,
`SpeakerSink`), or a robot's (its PDM microphone and I2S speaker, relayed by the Java host). The
Java host decides which (the `audio_route` setting, and whether a robot with audio is linked) and
sends the device's `DeviceProfile` with the voice's configuration; the sidecar never decides the
route itself. When a robot links or leaves, the running voice swaps its device
(`VoiceEngine.swap_device`): models stay loaded.

The profile holds what differs from one device to the other:

- half or full duplex, the echo tail and the output latency (echo.py, `EchoGate`): the computer's
  speakers are far from its microphone, the robot's speaker sits in the same body as its
  microphone;
- the VAD's floor (`vad_min_dbfs`) and the "too quiet" threshold of an utterance
  (`speech_min_dbfs`, filters.py): the robot's microphone has 12 dB of gain (docs/audio.md) and the
  lidar's motor turns a few centimetres away, so its noise floor is higher;
- capabilities: whether it can play the voice's earcons, whether it cancels its own echo.

The values themselves are the Java host's (host-java `VoiceSettings`, the single source of the
voice's defaults); the ones below are its defaults, for tests and tools only, and a test checks
they stay equal (host-java/marvin-contracts/golden/voice/configure_defaults.json).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, replace

import numpy as np

from ..audio import SAMPLE_RATE, AudioSink, AudioSource

log = logging.getLogger("marvin.voice.device")

COMPUTER, ROBOT = "computer", "robot"


@dataclass(frozen=True)
class DeviceProfile:
    kind: str = COMPUTER                # computer | robot
    robot: str = ""                     # with robot: its device name
    duplex: bool = False                # keep listening while speaking (only with AEC or a headset)
    echo_tail_s: float = 0.8            # half duplex: still deaf this long after the speaker really stopped
    output_latency_s: float = 0.0       # queued to heard, added to the tail; 0: what the sink reports
    vad_min_dbfs: float = -60.0         # a frame quieter than this is never speech
    speech_min_dbfs: float = -50.0      # an utterance whose loudest half is quieter is "too quiet"
    vad_aggressiveness: int = 2         # WebRTC VAD, 0..3
    has_earcons: bool = True            # it can play the voice's short sounds
    has_aec: bool = False               # it cancels its own echo
    input_device: str = ""              # computer: PortAudio input ("" = the system default)
    output_device: str = ""

    @property
    def name(self) -> str:
        """"computer", or the robot's device name."""
        return self.robot if self.kind == ROBOT and self.robot else self.kind


# the Java host's defaults (VoiceSettings.COMPUTER, VoiceSettings.ROBOT)
COMPUTER_PROFILE = DeviceProfile()
ROBOT_PROFILE = DeviceProfile(kind=ROBOT, echo_tail_s=0.5, output_latency_s=0.05, vad_min_dbfs=-55.0,
                              speech_min_dbfs=-45.0, vad_aggressiveness=3)


def robot_profile(name: str, **kw) -> DeviceProfile:
    return replace(ROBOT_PROFILE, robot=name, **kw)


# ---------------------------------------------------------------- earcons on the computer's speaker

def _tone(notes: list[tuple[float, float]], rate: int, level: float) -> np.ndarray:
    """Soft notes (frequency, seconds) with a 10 ms attack and an exponential decay."""
    out = []
    for f, d in notes:
        t = np.arange(int(d * rate)) / rate
        env = np.minimum(1, t / 0.01) * np.exp(-t / (d / 2))
        out.append(np.sin(2 * np.pi * f * t) * env * level)
    return np.concatenate(out).astype(np.int16)


EARCONS = {
    "wake": [(587.3, 0.09), (880.0, 0.09)],         # D5, A5 rising: "I'm listening"
    "done": [(880.0, 0.09), (587.3, 0.11)],         # falling: the window closed
    "error": [(330.0, 0.14), (262.0, 0.2)],         # low and falling: something went wrong
}


def earcon_pcm(sound: str, rate: int = SAMPLE_RATE) -> np.ndarray:
    """The computer's version of an earcon: the robot plays the same moments itself (SOUND)."""
    return _tone(EARCONS[sound], rate, 2500.0)


class AudioDevice:
    """A source, a sink and their profile. `earcon()` plays a short sound on it; `stop_playback()`
    stops the speaker and waits until it is really silent (half duplex then reopens the microphone)."""

    def __init__(self, source: AudioSource, sink: AudioSink, profile: DeviceProfile = COMPUTER_PROFILE):
        self.source, self.sink, self.profile = source, sink, profile

    @property
    def name(self) -> str:
        return self.profile.name

    @property
    def output_latency_s(self) -> float:
        """From a sample queued to it being heard: the profile's, else what the sink reports."""
        return self.profile.output_latency_s or float(getattr(self.sink, "output_latency", 0.0) or 0.0)

    def earcon(self, sound: str) -> float:
        """Plays `sound` (wake, done, error); returns how long the microphone should not take it for
        speech (seconds). 0 when the device has none."""
        if not self.profile.has_earcons or sound not in EARCONS:
            return 0.0
        if hasattr(self.sink, "play_sound"):                # a robot: its own built-in sound
            self.sink.play_sound(sound)
            from .. import protocol
            ms = next((d for i, d in protocol.SOUNDS.values() if i == protocol.sound(sound)[0]), 300)
            return ms / 1000 + self.output_latency_s
        pcm = earcon_pcm(sound)
        self.sink.play(pcm, SAMPLE_RATE)
        return len(pcm) / SAMPLE_RATE + self.output_latency_s

    def stop_playback(self, timeout: float = 0.6) -> bool:
        """Stops the speaker and waits until it has really stopped (what the device already had is
        played out). False if it still sounded busy after `timeout`."""
        self.sink.stop()
        end = time.monotonic() + timeout
        while getattr(self.sink, "busy", False):
            if time.monotonic() > end:
                return False
            time.sleep(0.005)
        return True

    def close(self) -> None:
        try:
            self.source.close()
        finally:
            if hasattr(self.sink, "close"):
                self.sink.close()
