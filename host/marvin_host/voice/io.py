"""Audio in and out on the computer: microphone, speakers, WAV files, and silent sinks for tests.

All classes implement the contract in audio.py (mono int16, 16 kHz, 20 ms frames). The robot's own
microphone and speaker implement the same interfaces in robot_audio.py, so the voice pipeline does
not care where the sound comes from.

`sounddevice` (PortAudio) is imported lazily: only `MicSource` and `SpeakerSink` need it.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import queue
import threading
import time
import wave
from pathlib import Path
from typing import Iterator

import numpy as np

from ..audio import FRAME_MS, FRAME_SAMPLES, SAMPLE_RATE

log = logging.getLogger("marvin.voice.io")


# ---------------------------------------------------------------- resampling

def _lowpass_taps(cutoff: float, n: int = 63) -> np.ndarray:
    """Windowed-sinc low-pass FIR. `cutoff` is a fraction of the sample rate (0 < cutoff < 0.5)."""
    k = np.arange(n) - (n - 1) / 2
    h = 2 * cutoff * np.sinc(2 * cutoff * k) * np.hamming(n)
    return h / h.sum()


def resample(pcm: np.ndarray, src: int, dst: int = SAMPLE_RATE) -> np.ndarray:
    """Resamples a whole int16 (or float) buffer from `src` to `dst` Hz. Good enough for speech:
    an anti-aliasing FIR when downsampling, then linear interpolation."""
    if src == dst or len(pcm) == 0:
        return pcm
    x = pcm.astype(np.float32)
    if dst < src:
        x = np.convolve(x, _lowpass_taps(0.45 * dst / src), mode="same")
    n_out = int(round(len(x) * dst / src))
    y = np.interp(np.arange(n_out) * (src / dst), np.arange(len(x)), x)
    if pcm.dtype == np.int16:
        return np.clip(np.round(y), -32768, 32767).astype(np.int16)
    return y.astype(pcm.dtype)


class StreamResampler:
    """Resamples a stream chunk by chunk, without clicks at chunk boundaries."""

    def __init__(self, src: int, dst: int = SAMPLE_RATE):
        self.src, self.dst = src, dst
        self.step = src / dst                   # input samples per output sample
        self.taps = _lowpass_taps(0.45 * dst / src) if dst < src else None
        self._hist = np.zeros(0 if self.taps is None else len(self.taps) - 1, np.float32)
        self._buf = np.zeros(0, np.float32)     # filtered input not consumed yet
        self._pos = 0.0                          # next output position, in `_buf` samples

    def process(self, pcm: np.ndarray) -> np.ndarray:
        if self.src == self.dst:
            return pcm.astype(np.int16)
        x = pcm.astype(np.float32)
        if self.taps is not None:
            full = np.concatenate([self._hist, x])
            x = np.convolve(full, self.taps, mode="valid")
            self._hist = full[len(full) - (len(self.taps) - 1):]
        self._buf = np.concatenate([self._buf, x])
        n = int(np.floor((len(self._buf) - 1 - self._pos) / self.step)) + 1
        if n <= 0:
            return np.zeros(0, np.int16)
        pos = self._pos + np.arange(n) * self.step
        y = np.interp(pos, np.arange(len(self._buf)), self._buf)
        nxt = self._pos + n * self.step
        drop = int(np.floor(nxt))
        self._buf = self._buf[drop:]
        self._pos = nxt - drop
        return np.clip(np.round(y), -32768, 32767).astype(np.int16)


class _Framer:
    """Cuts a stream of arbitrary-size chunks into FRAME_SAMPLES frames."""

    def __init__(self):
        self._rest = np.zeros(0, np.int16)

    def push(self, pcm: np.ndarray) -> list[np.ndarray]:
        buf = np.concatenate([self._rest, pcm]) if len(self._rest) else pcm
        n = len(buf) // FRAME_SAMPLES
        frames = [buf[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES].copy() for i in range(n)]
        self._rest = buf[n * FRAME_SAMPLES:].copy()
        return frames


def to_int16(pcm: np.ndarray) -> np.ndarray:
    """Float [-1, 1] or int16, any shape with channels last -> mono int16."""
    a = np.asarray(pcm)
    if a.ndim == 2:
        a = a.mean(axis=1) if a.dtype.kind == "f" else a.astype(np.int32).mean(axis=1)
    if a.dtype.kind == "f":
        return np.clip(np.round(a * 32767), -32768, 32767).astype(np.int16)
    return a.astype(np.int16)


def _sounddevice():
    try:
        import sounddevice as sd
    except (ImportError, OSError) as e:          # OSError: PortAudio library missing (Linux)
        raise RuntimeError("audio needs the 'voice' extra: pip install -e \".[voice]\" "
                           "(on Linux also: apt install libportaudio2)") from e
    return sd


def list_devices() -> str:
    """The sound devices PortAudio sees, as text (`marvin-host talk --list-devices`)."""
    return str(_sounddevice().query_devices())


# ---------------------------------------------------------------- sources

class MicSource:
    """The computer's microphone. Asks PortAudio for 16 kHz mono; if the device refuses, opens it
    at its native rate and resamples. Frames are queued (up to `max_queue_s`), so a slow consumer
    (Whisper running) delays audio instead of losing it."""

    def __init__(self, device: int | str | None = None, max_queue_s: float = 30.0):
        self.device = device
        self._q: queue.Queue[np.ndarray | None] = queue.Queue(maxsize=int(max_queue_s * 1000 / FRAME_MS))
        self._framer = _Framer()
        self._closed = threading.Event()
        self._stream = None
        self.rate = SAMPLE_RATE
        self.overflows = 0

    def _open(self):
        sd = _sounddevice()
        try:
            sd.check_input_settings(device=self.device, channels=1, dtype="int16", samplerate=SAMPLE_RATE)
            rate = SAMPLE_RATE
        except Exception:
            rate = int(sd.query_devices(self.device, "input")["default_samplerate"])
            log.info("microphone does not do %d Hz, recording at %d Hz and resampling", SAMPLE_RATE, rate)
        self.rate = rate
        rs = StreamResampler(rate, SAMPLE_RATE)

        def callback(indata, frames, t, status):
            if status.input_overflow:
                self.overflows += 1
            for f in self._framer.push(rs.process(indata[:, 0])):
                try:
                    self._q.put_nowait(f)
                except queue.Full:
                    self.overflows += 1

        self._stream = sd.InputStream(device=self.device, channels=1, dtype="int16", samplerate=rate,
                                      blocksize=int(rate * FRAME_MS / 1000), callback=callback)
        self._stream.start()

    def frames(self) -> Iterator[np.ndarray]:
        if self._stream is None:
            self._open()
        while not self._closed.is_set():
            try:
                f = self._q.get(timeout=0.2)
            except queue.Empty:
                continue
            if f is None:
                break
            yield f

    def close(self) -> None:
        self._closed.set()
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None
        try:
            self._q.put_nowait(None)
        except queue.Full:
            pass


def read_wav(path: str | Path) -> tuple[np.ndarray, int]:
    """Reads a 16-bit PCM WAV file -> (mono int16, sample rate)."""
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError(f"{path}: only 16-bit PCM WAV is supported")
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
        ch, rate = w.getnchannels(), w.getframerate()
    if ch > 1:
        pcm = pcm.reshape(-1, ch).astype(np.int32).mean(axis=1).astype(np.int16)
    return pcm.astype(np.int16), rate


def write_wav(path: str | Path, pcm: np.ndarray, rate: int = SAMPLE_RATE) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(np.asarray(pcm, dtype="<i2").tobytes())


class ArraySource:
    """Frames from a PCM buffer, at real-time pace (`realtime=True`) or as fast as possible.
    `tail_s` of silence is appended, so the last utterance ends properly."""

    def __init__(self, pcm: np.ndarray, rate: int = SAMPLE_RATE, realtime: bool = False, tail_s: float = 1.0):
        pcm = resample(to_int16(pcm), rate, SAMPLE_RATE)
        self.pcm = np.concatenate([pcm, np.zeros(int(tail_s * SAMPLE_RATE), np.int16)])
        self.realtime = realtime
        self._closed = threading.Event()

    def frames(self) -> Iterator[np.ndarray]:
        t0 = time.monotonic()
        n = len(self.pcm) // FRAME_SAMPLES
        for i in range(n):
            if self._closed.is_set():
                return
            if self.realtime:
                delay = t0 + i * FRAME_MS / 1000 - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
            yield self.pcm[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES].copy()

    def close(self) -> None:
        self._closed.set()


class WavSource(ArraySource):
    """Frames from a WAV file (any rate, mono or stereo, 16-bit)."""

    def __init__(self, path: str | Path, realtime: bool = False, tail_s: float = 1.0):
        pcm, rate = read_wav(path)
        super().__init__(pcm, rate, realtime=realtime, tail_s=tail_s)


# ---------------------------------------------------------------- sinks

class NullSink:
    """Plays nothing. Keeps what it was given in `played` (16 kHz int16 arrays) for tests.
    With `realtime=True` it stays busy for the duration of the audio, like a real speaker."""

    def __init__(self, realtime: bool = False):
        self.realtime = realtime
        self.played: list[np.ndarray] = []
        self.stops = 0
        self._until = 0.0
        self._lock = threading.Lock()
        self._stopped = threading.Event()

    def play(self, pcm: np.ndarray, rate: int = SAMPLE_RATE) -> None:
        pcm = resample(to_int16(pcm), rate, SAMPLE_RATE)
        with self._lock:
            self.played.append(pcm)
            if self.realtime:
                self._stopped.clear()
                self._until = max(self._until, time.monotonic()) + len(pcm) / SAMPLE_RATE

    def wait(self) -> None:
        while True:
            with self._lock:
                left = self._until - time.monotonic()
            if left <= 0 or self._stopped.wait(min(left, 0.05)):
                return

    def stop(self) -> None:
        with self._lock:
            self.stops += 1
            self._until = 0.0
            self._stopped.set()

    @property
    def busy(self) -> bool:
        return time.monotonic() < self._until

    @property
    def audio(self) -> np.ndarray:
        """Everything played, concatenated."""
        return np.concatenate(self.played) if self.played else np.zeros(0, np.int16)


class WavSink(NullSink):
    """Writes everything played to a WAV file (16 kHz) when closed."""

    def __init__(self, path: str | Path, realtime: bool = False):
        super().__init__(realtime=realtime)
        self.path = Path(path)

    def close(self) -> None:
        write_wav(self.path, self.audio)


class SpeakerSink:
    """The computer's speakers: a PortAudio output stream fed from a buffer. `play()` returns at
    once, `wait()` blocks until the buffer has been played, `stop()` drops it (barge-in)."""

    def __init__(self, device: int | str | None = None):
        sd = _sounddevice()
        try:
            sd.check_output_settings(device=device, channels=1, dtype="int16", samplerate=SAMPLE_RATE)
            self.rate = SAMPLE_RATE
        except Exception:
            self.rate = int(sd.query_devices(device, "output")["default_samplerate"])
        self._buf = np.zeros(0, np.int16)
        self._lock = threading.Lock()
        self._drained = threading.Event()
        self._drained.set()
        self._tail = 0                  # callbacks still to run before the last samples are heard
        self._stream = sd.OutputStream(device=device, channels=1, dtype="int16", samplerate=self.rate,
                                       callback=self._callback, latency="low")
        self._stream.start()
        # from the samples leaving our buffer to the sound leaving the speaker (the echo gate adds it)
        self.output_latency = float(self._stream.latency or 0.0)

    def _callback(self, outdata, frames, t, status):
        with self._lock:
            n = min(frames, len(self._buf))
            outdata[:n, 0] = self._buf[:n]
            outdata[n:, 0] = 0
            self._buf = self._buf[n:]
            if len(self._buf) == 0 and not self._drained.is_set():
                if n == 0:
                    self._tail -= 1
                    if self._tail <= 0:
                        self._drained.set()
                else:
                    self._tail = 2      # let the device play out what it already has

    def play(self, pcm: np.ndarray, rate: int = SAMPLE_RATE) -> None:
        pcm = resample(to_int16(pcm), rate, self.rate)
        with self._lock:
            self._buf = np.concatenate([self._buf, pcm])
            self._drained.clear()

    def wait(self) -> None:
        self._drained.wait()

    def stop(self) -> None:
        with self._lock:
            self._buf = np.zeros(0, np.int16)
            self._drained.set()

    @property
    def busy(self) -> bool:
        return not self._drained.is_set()

    def close(self) -> None:
        self.stop()
        self._stream.stop()
        self._stream.close()
