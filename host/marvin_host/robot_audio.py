"""The robot's microphone and speaker over the network, behind the host audio contract (audio.py).

    rx = Receiver(sink)                      # serving in its own thread
    dev = robot_audio.audio_device(rx)       # a robot whose HELLO has the audio flag
    mic = RobotMicSource(rx, dev)            # AudioSource: 20 ms int16 frames at 16 kHz
    spk = RobotSpeakerSink(rx, dev)          # AudioSink: play() / wait() / stop() / busy
    spk.play_sound("wake")                   # built-in earcon, no streaming

`RobotMicSource` asks the robot to stream its microphone (AUDIO_CTRL MIC_START, repeated every
second so a lost datagram or a robot reboot does not end the stream), receives AUDIO_IN through
the receiver and yields exact FRAME_SAMPLES frames. The sample index in every AUDIO_IN tells it
what was lost: short gaps (up to `max_gap_s`) are filled with silence so the audio keeps its
timing, late or duplicated datagrams are dropped, and a jump back (the robot restarted its
microphone) starts over. Nothing is invented while no datagram arrives at all.

`RobotSpeakerSink` resamples to 16 kHz, cuts the PCM into AUDIO_OUT datagrams of 20 ms and sends
them at real time plus a small lead (150 ms), so the robot's jitter buffer (100 ms prebuffer)
never overflows and starts playing on the first burst. Each utterance after a pause is a new
stream id; `stop()` sends AUDIO_CTRL PLAY_STOP and starts a new stream for the next play().
`busy` and `wait()` follow the estimated playback end on the robot.

Format and protocol: docs/protocol.md (audio messages), docs/audio.md.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import queue
import random
import threading
import time
from dataclasses import dataclass
from typing import Iterator

import numpy as np

from . import protocol
from .audio import FRAME_SAMPLES, SAMPLE_RATE

log = logging.getLogger("marvin.robot_audio")

_U32 = 1 << 32
RESTART_S = 0.2       # AUDIO_IN clock vs sample index disagreement that means a new microphone stream


def _index_diff(to: int, frm: int) -> int:
    """Signed distance between two u32 sample indexes, wrap-aware (positive: `to` is later)."""
    return ((to - frm + (1 << 31)) % _U32) - (1 << 31)


def audio_device(receiver, timeout: float = 0.0):
    """The first device known to `receiver` whose HELLO announces audio, waiting up to `timeout` s."""
    deadline = time.monotonic() + timeout
    while True:
        for dev in list(receiver.devices.values()):
            if dev.hello.flags & protocol.FLAG_AUDIO:
                return dev
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.05)


# ---- Microphone -------------------------------------------------------------------------------

@dataclass
class MicStats:
    datagrams: int = 0
    frames: int = 0            # frames yielded
    lost_samples: int = 0      # silence inserted for lost datagrams
    late_samples: int = 0      # dropped: late or duplicated
    resyncs: int = 0           # gaps too large to fill, or the robot restarted its microphone
    overflows: int = 0         # datagrams dropped because frames() was not being read


class RobotMicSource:
    """AudioSource over the robot's PDM microphone (AUDIO_IN)."""

    def __init__(self, receiver, dev, *, keepalive_s: float = 1.0, max_gap_s: float = 0.5,
                 gain_db: int | None = None, queue_s: float = 2.0):
        self.rx = receiver
        self.dev = dev
        self.max_gap = int(max_gap_s * SAMPLE_RATE)
        self.stats = MicStats()
        self.last_t_us: int | None = None          # robot time of the latest datagram's first sample
        self._q: queue.Queue = queue.Queue(maxsize=max(1, int(queue_s * SAMPLE_RATE / protocol.AUDIO_IN_SAMPLES)))
        self._closed = threading.Event()
        receiver.add_audio_listener(self._on_audio)
        if gain_db is not None:
            self._ctrl(protocol.AUDIO_MIC_GAIN, gain_db)
        self._ctrl(protocol.AUDIO_MIC_START)
        self._keepalive = threading.Thread(target=self._keep, args=(keepalive_s,), name="mic-keepalive", daemon=True)
        self._keepalive.start()

    def _ctrl(self, command: int, argument: int = 0) -> None:
        self.rx.send(self.dev, protocol.AUDIO_CTRL, protocol.audio_ctrl(command, argument))

    def _keep(self, period: float) -> None:
        while not self._closed.wait(period):
            self._ctrl(protocol.AUDIO_MIC_START)

    def _on_audio(self, dev, t_us: int, seq: int, pcm: np.ndarray) -> None:
        if dev.addr != self.dev.addr or self._closed.is_set():
            return
        self.stats.datagrams += 1
        self.last_t_us = t_us
        try:
            self._q.put_nowait((seq, t_us, pcm))
        except queue.Full:                      # nobody reads frames(): keep the newest
            self.stats.overflows += 1
            try:
                self._q.get_nowait()
            except queue.Empty:
                pass
            self._q.put_nowait((seq, t_us, pcm))

    def frames(self) -> Iterator[np.ndarray]:
        pending = np.empty(0, dtype=np.int16)
        expected: int | None = None
        last_seq = last_t_us = 0
        while not self._closed.is_set():
            try:
                item = self._q.get(timeout=0.1)
            except queue.Empty:
                continue
            if item is None:
                break
            seq, t_us, pcm = item
            if expected is not None:
                d = _index_diff(seq, expected)
                if d < 0:
                    # Behind: late or duplicated, unless the robot clock disagrees with the index
                    # by more than RESTART_S (the robot restarted its microphone, or rebooted).
                    predicted = last_t_us + _index_diff(seq, last_seq) * 1e6 / SAMPLE_RATE
                    if abs(t_us - predicted) > RESTART_S * 1e6:
                        self.stats.resyncs += 1
                    elif -d >= len(pcm):            # duplicate or late: already covered
                        self.stats.late_samples += len(pcm)
                        continue
                    else:                           # overlaps what we have: keep the new part
                        self.stats.late_samples += -d
                        pcm = pcm[-d:]
                        seq = expected
                elif d > 0:
                    if d <= self.max_gap:
                        self.stats.lost_samples += d
                        pending = np.concatenate([pending, np.zeros(d, dtype=np.int16)])
                    else:
                        self.stats.resyncs += 1
            expected = (seq + len(pcm)) % _U32
            last_seq, last_t_us = seq, t_us
            pending = np.concatenate([pending, pcm.astype(np.int16, copy=False)])
            while len(pending) >= FRAME_SAMPLES:
                frame, pending = pending[:FRAME_SAMPLES].copy(), pending[FRAME_SAMPLES:]
                self.stats.frames += 1
                yield frame

    def close(self) -> None:
        if self._closed.is_set():
            return
        self._closed.set()
        self.rx.remove_audio_listener(self._on_audio)
        self._ctrl(protocol.AUDIO_MIC_STOP)
        try:
            self._q.put_nowait(None)
        except queue.Full:
            pass

    def __enter__(self) -> "RobotMicSource":
        return self

    def __exit__(self, *exc) -> None:
        self.close()


# ---- Speaker ----------------------------------------------------------------------------------

def to_pcm16(pcm, rate: int = SAMPLE_RATE) -> np.ndarray:
    """Mono int16 at SAMPLE_RATE from int16 or float (-1..1) PCM at `rate`, mono or (n, channels)."""
    x = np.asarray(pcm)
    is_float = x.dtype.kind == "f"
    x = x.astype(np.float64)
    if x.ndim == 2:
        x = x.mean(axis=1)
    if is_float:
        x = np.clip(x * 32767.0, -32768, 32767)
    if rate != SAMPLE_RATE and len(x):
        if rate > SAMPLE_RATE:                   # anti-alias: windowed-sinc low-pass at 7.2 kHz
            fc = 0.45 * SAMPLE_RATE / rate
            n = np.arange(63) - 31
            h = 2 * fc * np.sinc(2 * fc * n) * np.hamming(63)
            x = np.convolve(x, h / h.sum(), mode="same")
        n_out = max(1, int(round(len(x) * SAMPLE_RATE / rate)))
        x = np.interp(np.arange(n_out) * (rate / SAMPLE_RATE), np.arange(len(x)), x)
    return np.clip(np.round(x), -32768, 32767).astype(np.int16)


class RobotSpeakerSink:
    """AudioSink over the robot's I2S speaker (AUDIO_OUT, AUDIO_CTRL, SOUND)."""

    def __init__(self, receiver, dev, *, lead_s: float = 0.15, chunk: int = FRAME_SAMPLES,
                 latency_s: float = 0.1, volume: int | None = None):
        if not 1 <= chunk <= protocol.AUDIO_OUT_MAX_SAMPLES:
            raise ValueError(f"chunk must be 1..{protocol.AUDIO_OUT_MAX_SAMPLES} samples")
        self.rx = receiver
        self.dev = dev
        self.lead_s = lead_s
        self.chunk = chunk
        self.latency_s = latency_s                # robot prebuffer / drain + I2S DMA, for busy/wait
        self._cond = threading.Condition()
        self._pending = np.empty(0, dtype=np.int16)
        self._stream = random.randrange(1 << 16)
        self._sent = 0                            # samples sent in the current stream (index = _sent mod 2^32)
        self._t0: float | None = None             # host time at which stream sample 0 is due
        self._play_end = 0.0                      # estimated end of playback on the robot
        self._sound_end = 0.0
        self._closed = False
        if volume is not None:
            self.set_volume(volume)
        self._thread = threading.Thread(target=self._run, name="robot-speaker", daemon=True)
        self._thread.start()

    # AudioSink

    def play(self, pcm: np.ndarray, rate: int = SAMPLE_RATE) -> None:
        x = to_pcm16(pcm, rate)
        if not len(x):
            return
        with self._cond:
            now = time.monotonic()
            if not len(self._pending) and now >= self._play_end:
                self._new_stream()                # after a pause: a fresh stream
            elif self._t0 is not None and not len(self._pending):
                # still playing the tail: continue the stream, but never schedule in the past
                self._t0 = max(self._t0, now - self._sent / SAMPLE_RATE)
            self._pending = np.concatenate([self._pending, x])
            self._cond.notify_all()

    def wait(self) -> None:
        while self.busy:
            time.sleep(0.01)

    def stop(self) -> None:
        with self._cond:
            self._pending = np.empty(0, dtype=np.int16)
            self._play_end = self._sound_end = 0.0
            self._new_stream()
        ctrl = protocol.audio_ctrl(protocol.AUDIO_PLAY_STOP)
        self.rx.send(self.dev, protocol.AUDIO_CTRL, ctrl)
        self.rx.send(self.dev, protocol.AUDIO_CTRL, ctrl)   # twice: cheap insurance against a loss

    @property
    def busy(self) -> bool:
        with self._cond:
            now = time.monotonic()
            return bool(len(self._pending)) or now < self._play_end or now < self._sound_end

    # extras

    def play_sound(self, sound: str | int) -> None:
        """Plays a built-in earcon on the robot (protocol.SOUNDS: chirp, beep, wake, done, error, hello)."""
        payload = protocol.sound(sound)
        ms = next((d for i, d in protocol.SOUNDS.values() if i == payload[0]), 0)
        with self._cond:
            self._sound_end = max(self._sound_end, time.monotonic() + ms / 1000 + 0.05)
        self.rx.send(self.dev, protocol.SOUND, payload)

    def set_volume(self, volume: int) -> None:
        """Speaker volume 0..100; the firmware caps the loudest setting for the 1 W speaker."""
        self.rx.send(self.dev, protocol.AUDIO_CTRL, protocol.audio_ctrl(protocol.AUDIO_VOLUME, max(0, min(100, volume))))

    def close(self) -> None:
        with self._cond:
            self._closed = True
            self._cond.notify_all()
        self._thread.join(timeout=1)

    def __enter__(self) -> "RobotSpeakerSink":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    # internals

    def _new_stream(self) -> None:
        self._stream = (self._stream + 1) & 0xFFFF
        self._sent = 0
        self._t0 = None

    def _run(self) -> None:
        while True:
            with self._cond:
                while not self._closed and not len(self._pending):
                    self._cond.wait()
                if self._closed:
                    return
                now = time.monotonic()
                if self._t0 is None:
                    self._t0 = now
                due = self._t0 + self._sent / SAMPLE_RATE - self.lead_s      # when the next chunk may go
                if now < due:
                    self._cond.wait(timeout=due - now)
                    continue
                n = min(self.chunk, len(self._pending))
                chunk, self._pending = self._pending[:n], self._pending[n:]
                payload = protocol.AudioOut(self._stream, self._sent % _U32, chunk).encode()
                self._sent += n
                self._play_end = max(self._play_end, self._t0 + self._sent / SAMPLE_RATE + self.latency_s)
            self.rx.send(self.dev, protocol.AUDIO_OUT, payload)
