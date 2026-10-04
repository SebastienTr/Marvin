"""Whose voice is it: speaker embeddings and the voiceprints of the people Marvin knows.

A speaker embedding is a short vector (192 numbers) that a network trained to tell voices apart
computes from a few seconds of speech: two recordings of the same person give vectors pointing
the same way, two people give different directions. The model is WeSpeaker's ECAPA-TDNN (512
channels) trained on VoxCeleb2 (Apache-2.0, 25 MB ONNX, models.py): about 20-60 ms for a
question on a laptop CPU, run while the words are being recognised, so the answer waits for
nothing.

- **Enrollment** (enroll.py): the person reads two or three sentences; their embeddings are
  averaged into a `Voiceprint` (name, vector, the model it belongs to). Voiceprints are the Java
  host's to keep (`voiceprints.json` next to `voice.json`, on this computer only) and are sent to
  the voice with its settings; deleting one in the app removes it.
- **At run time** (`SpeakerId.identify`): each question's embedding is compared (cosine
  similarity) with every voiceprint; the best one above `threshold` names the speaker
  ("Sébastien", 0.71), else the voice is unknown. The core puts it in Marvin's context.

The score is the cosine similarity, -1..1. With this model, two recordings of one person through
the same microphone score about 0.5-0.85; two people about -0.1-0.3. The default threshold 0.45
favours not naming someone wrongly; utterances shorter than `MIN_SPEECH_S` are not judged.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Callable, Iterable, Protocol

import numpy as np

from .features import kaldi_fbank

log = logging.getLogger("marvin.voice.speaker")

MODEL_ID = "wespeaker-ecapa512"       # the voiceprints' model: another model's prints are not comparable
DEFAULT_THRESHOLD = 0.45
MIN_SPEECH_S = 0.8                    # shorter: no judgement ("Oui." says little about a voice)


class SpeakerEncoder(Protocol):
    model_id: str

    def embed(self, pcm: np.ndarray) -> np.ndarray:
        """16 kHz int16 speech -> a unit-length embedding."""
        ...


def unit(v) -> np.ndarray:
    v = np.asarray(v, dtype=np.float32).reshape(-1)
    n = float(np.linalg.norm(v))
    return v / n if n > 0 else v


def cosine(a, b) -> float:
    return float(np.dot(unit(a), unit(b)))


class WeSpeakerEncoder:
    """A WeSpeaker ONNX model (fbank 80 with mean normalisation in, embedding out)."""

    model_id = MODEL_ID

    def __init__(self, path=None):
        from . import models
        path = path or models.path_of("speaker")
        if path is None:
            raise FileNotFoundError("the speaker model is not downloaded (python -m marvin_host.voice.models --prefetch)")
        self._session = models.onnx_session(path, threads=2)
        self._input = self._session.get_inputs()[0].name
        t = time.monotonic()
        self.embed(np.zeros(16000, np.int16))           # load and optimise now
        log.info("speaker model ready in %.2f s", time.monotonic() - t)

    def embed(self, pcm: np.ndarray) -> np.ndarray:
        feats = kaldi_fbank(pcm)
        if len(feats) < 10:
            feats = np.zeros((10, feats.shape[1]), np.float32)
        out = self._session.run(None, {self._input: feats[None, :, :]})[0]
        return unit(out)


class FakeEncoder:
    """For tests: `fn(pcm) -> vector`; by default, the vector is the utterance's pitch class (a
    buzzy test tone of 110 + 20 k Hz gives axis k), so scripted phrases have scripted voices."""

    model_id = MODEL_ID

    def __init__(self, fn: Callable[[np.ndarray], Iterable[float]] | None = None, dims: int = 16):
        self._fn = fn
        self.dims = dims
        self.calls = 0

    def embed(self, pcm: np.ndarray) -> np.ndarray:
        self.calls += 1
        if self._fn is not None:
            return unit(self._fn(pcm))
        x = np.asarray(pcm, np.float32)
        spec = np.abs(np.fft.rfft(x[: 16000] if len(x) >= 16000 else np.pad(x, (0, 16000 - len(x)))))
        f0 = float(np.argmax(spec[60:400]) + 60)            # 1 Hz bins over one second
        k = int(round((f0 - 110) / 20)) % self.dims
        v = np.full(self.dims, 0.05, np.float32)
        v[k] = 1.0
        return unit(v)


@dataclass
class Voiceprint:
    """A person's voice: `embedding` is the mean of their enrollment sentences' embeddings."""

    name: str
    embedding: np.ndarray
    model: str = MODEL_ID
    samples: int = 1

    @classmethod
    def of(cls, name: str, embeddings: list[np.ndarray], model: str = MODEL_ID) -> "Voiceprint":
        if not embeddings:
            raise ValueError("no speech to make a voiceprint from")
        return cls(name, unit(np.mean([unit(e) for e in embeddings], axis=0)), model, len(embeddings))


@dataclass
class SpeakerMatch:
    """`name`: the person recognised, None when the voice is unknown (or was not judged);
    `score`: the best similarity (with `best`, whoever it was), None when not judged."""

    name: str | None
    score: float | None
    best: str | None = None
    seconds: float = 0.0

    @property
    def known(self) -> bool:
        return self.name is not None

    def label(self) -> str:
        """For the core: the name, "unknown", or "" when nobody is enrolled or it was not judged."""
        if self.name:
            return self.name
        return "unknown" if self.score is not None else ""


@dataclass
class SpeakerId:
    """Compares utterances with the voiceprints (only those of the encoder's model)."""

    encoder: SpeakerEncoder
    voiceprints: list[Voiceprint] = field(default_factory=list)
    threshold: float = DEFAULT_THRESHOLD

    def __post_init__(self):
        mine = [v for v in self.voiceprints if v.model == self.encoder.model_id]
        if len(mine) != len(self.voiceprints):
            log.warning("%d voiceprint(s) made with another model are ignored: enrol again",
                        len(self.voiceprints) - len(mine))
        self.voiceprints = mine

    @property
    def enrolled(self) -> bool:
        return bool(self.voiceprints)

    def identify(self, pcm: np.ndarray, embedding: np.ndarray | None = None) -> SpeakerMatch:
        t = time.monotonic()
        if not self.voiceprints or (embedding is None and len(pcm) / 16000 < MIN_SPEECH_S):
            return SpeakerMatch(None, None)
        e = self.encoder.embed(pcm) if embedding is None else embedding
        return self.match(e, time.monotonic() - t)

    def match(self, embedding: np.ndarray, seconds: float = 0.0) -> SpeakerMatch:
        if not self.voiceprints:
            return SpeakerMatch(None, None, seconds=seconds)
        scores = [(cosine(embedding, v.embedding), v.name) for v in self.voiceprints]
        score, best = max(scores)
        return SpeakerMatch(best if score >= self.threshold else None, round(score, 3), best, seconds)


def make_encoder() -> SpeakerEncoder | None:
    """The speaker model when it is downloaded, else None (no speaker recognition)."""
    try:
        return WeSpeakerEncoder()
    except (FileNotFoundError, ImportError) as e:
        log.info("speaker recognition unavailable: %s", e)
    except Exception as e:                                  # noqa: BLE001 - a broken file or runtime
        log.warning("the speaker model failed to load: %s", e)
    return None
