"""Audio contract: how sound gets in and out of Marvin's host software.

The voice pipeline (voice/) listens to an `AudioSource` and speaks through an `AudioSink`. Today
both can be the computer's own microphone and speakers; once the robot is built, the robot's
PDM microphone and I2S speaker implement the same interfaces over the network (robot_audio.py).

Format everywhere: mono, signed 16-bit little-endian PCM (numpy int16), SAMPLE_RATE Hz,
delivered in frames of FRAME_SAMPLES samples (20 ms). This module holds the contract only.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

from typing import Iterator, Protocol, runtime_checkable

import numpy as np

SAMPLE_RATE = 16_000
FRAME_MS = 20
FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS // 1000     # 320 samples, 640 bytes


@runtime_checkable
class AudioSource(Protocol):
    """A live microphone. `frames()` yields int16 arrays of FRAME_SAMPLES samples until `close()`."""

    def frames(self) -> Iterator[np.ndarray]: ...
    def close(self) -> None: ...


@runtime_checkable
class AudioSink(Protocol):
    """A speaker. `play()` queues int16 PCM at `rate` Hz (resampled if needed) and returns at once;
    `wait()` blocks until everything queued has been played; `stop()` drops what is queued."""

    def play(self, pcm: np.ndarray, rate: int = SAMPLE_RATE) -> None: ...
    def wait(self) -> None: ...
    def stop(self) -> None: ...
    @property
    def busy(self) -> bool: ...
