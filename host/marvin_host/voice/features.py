"""Audio features for the small ONNX models of the ears, in numpy (no PyTorch, no transformers).

- `whisper_log_mel`: Whisper's 80-band log-mel spectrogram of an 8 s window, what Smart Turn v3
  (turn.py) reads. Same steps as `transformers.WhisperFeatureExtractor(chunk_length=8)` with
  `do_normalize=True` (zero-mean unit-variance waveform, centred 400-point STFT with a periodic
  Hann window, hop 160, Slaney mel filters, log10, floor at max - 8, (x + 4) / 4), the way
  pipecat's `LocalSmartTurnAnalyzerV3` computes it without transformers.
- `kaldi_fbank`: Kaldi's 80-band log filter bank (25 ms Hamming frames every 10 ms, DC removed,
  pre-emphasis 0.97, power spectrum, Kaldi's mel scale from 20 Hz), what the WeSpeaker speaker
  models (speaker.py) were trained on, as `torchaudio.compliance.kaldi.fbank(..., dither=0,
  window_type="hamming")` computes it on int16-scaled samples.

Both take 16 kHz mono float or int16 samples. They are written from the published algorithms; the
tests check them against reference values.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import functools

import numpy as np

SAMPLE_RATE = 16000


def as_float(pcm: np.ndarray) -> np.ndarray:
    """int16 samples (or floats already in -1..1) as float32 in -1..1."""
    a = np.asarray(pcm)
    if a.dtype == np.int16:
        return a.astype(np.float32) / 32768.0
    return a.astype(np.float32)


# ---------------------------------------------------------------- Whisper (Smart Turn)

WHISPER_N_FFT = 400
WHISPER_HOP = 160
WHISPER_MELS = 80


def _hz_to_mel_slaney(f: np.ndarray) -> np.ndarray:
    f = np.atleast_1d(np.asarray(f, dtype=np.float64))
    mel = 3.0 * f / 200.0
    log = f >= 1000.0
    mel[log] = 15.0 + np.log(f[log] / 1000.0) * (27.0 / np.log(6.4))
    return mel


def _mel_to_hz_slaney(m: np.ndarray) -> np.ndarray:
    m = np.atleast_1d(np.asarray(m, dtype=np.float64))
    f = 200.0 * m / 3.0
    log = m >= 15.0
    f[log] = 1000.0 * np.exp((np.log(6.4) / 27.0) * (m[log] - 15.0))
    return f


@functools.lru_cache(maxsize=None)
def _slaney_filters(n_fft: int = WHISPER_N_FFT, n_mels: int = WHISPER_MELS, rate: int = SAMPLE_RATE) -> np.ndarray:
    """(n_fft // 2 + 1, n_mels) triangular filters, area-normalised (librosa's default, Whisper's)."""
    bins = n_fft // 2 + 1
    mels = np.linspace(_hz_to_mel_slaney(0.0)[0], _hz_to_mel_slaney(rate / 2)[0], n_mels + 2)
    edges = _mel_to_hz_slaney(mels)
    fft_freqs = np.linspace(0, rate // 2, bins)
    diff = np.diff(edges)
    slopes = edges[None, :] - fft_freqs[:, None]
    down = -slopes[:, :-2] / diff[:-1]
    up = slopes[:, 2:] / diff[1:]
    filters = np.maximum(0.0, np.minimum(down, up))
    return filters * (2.0 / (edges[2:n_mels + 2] - edges[:n_mels]))[None, :]


def whisper_log_mel(audio: np.ndarray, seconds: int = 8) -> np.ndarray:
    """(80, 100 * seconds) float32 log-mel features of the LAST `seconds` of `audio`, zeros
    padded in front when it is shorter (Smart Turn wants the end of the turn at the end)."""
    x = as_float(audio)
    n = SAMPLE_RATE * seconds
    x = x[-n:] if len(x) >= n else np.concatenate([np.zeros(n - len(x), np.float32), x])
    x = (x - x.mean()) / np.sqrt(x.var() + 1e-7)
    pad = WHISPER_N_FFT // 2
    padded = np.pad(x.astype(np.float64), (pad, pad), mode="reflect")
    window = np.hanning(WHISPER_N_FFT + 1)[:-1]                # periodic Hann
    frames = np.lib.stride_tricks.sliding_window_view(padded, WHISPER_N_FFT)[::WHISPER_HOP]
    power = np.abs(np.fft.rfft(frames * window, axis=-1)) ** 2   # (frames, bins)
    mel = np.maximum(1e-10, power @ _slaney_filters())          # (frames, mels)
    log = np.log10(mel).T[:, :-1]                               # Whisper drops the last frame
    log = np.maximum(log, log.max() - 8.0)
    return ((log + 4.0) / 4.0).astype(np.float32)


# ---------------------------------------------------------------- Kaldi fbank (speaker embeddings)

FBANK_FRAME = 400       # 25 ms
FBANK_SHIFT = 160       # 10 ms
FBANK_FFT = 512
FBANK_MELS = 80
_EPS = float(np.finfo(np.float32).eps)


def _kaldi_mel(f):
    return 1127.0 * np.log(1.0 + np.asarray(f, dtype=np.float64) / 700.0)


@functools.lru_cache(maxsize=None)
def _kaldi_banks(n_mels: int = FBANK_MELS, n_fft: int = FBANK_FFT, rate: int = SAMPLE_RATE,
                 low: float = 20.0) -> np.ndarray:
    """(n_fft // 2 + 1, n_mels) Kaldi triangular mel filters (no area normalisation; the Nyquist
    bin gets no weight)."""
    half = n_fft // 2
    lo, hi = _kaldi_mel(low), _kaldi_mel(rate / 2)
    delta = (hi - lo) / (n_mels + 1)
    mel = _kaldi_mel(np.arange(half) * rate / n_fft)
    banks = np.zeros((half + 1, n_mels))
    for m in range(n_mels):
        left, centre, right = lo + m * delta, lo + (m + 1) * delta, lo + (m + 2) * delta
        up = (mel - left) / (centre - left)
        down = (right - mel) / (right - centre)
        w = np.where(mel <= centre, up, down)
        banks[:half, m] = np.where((mel > left) & (mel < right), w, 0.0)
    return banks


def kaldi_fbank(audio: np.ndarray, cmn: bool = True) -> np.ndarray:
    """(frames, 80) float32 log mel filter bank energies, on int16-scaled samples; `cmn`
    subtracts the mean of each band over the utterance. Fewer than 25 ms: no frame."""
    x = as_float(audio).astype(np.float64) * 32768.0
    if len(x) < FBANK_FRAME:
        return np.zeros((0, FBANK_MELS), np.float32)
    frames = np.lib.stride_tricks.sliding_window_view(x, FBANK_FRAME)[::FBANK_SHIFT].copy()
    frames -= frames.mean(axis=1, keepdims=True)                         # DC offset
    frames[:, 1:] -= 0.97 * frames[:, :-1].copy()                          # pre-emphasis...
    frames[:, 0] -= 0.97 * frames[:, 0]                                    # ...Kaldi's first sample
    frames *= np.hamming(FBANK_FRAME)
    power = np.abs(np.fft.rfft(frames, n=FBANK_FFT, axis=-1)) ** 2
    feats = np.log(np.maximum(power @ _kaldi_banks(), _EPS))
    if cmn:
        feats -= feats.mean(axis=0, keepdims=True)
    return feats.astype(np.float32)
