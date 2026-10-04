"""End of turn: has the speaker finished, or only paused?

A fixed silence is a poor judge: 0.55 s cuts off "Je voudrais... [thinks] un café", and waiting
longer for everyone slows every answer. Smart Turn v3 (pipecat-ai/smart-turn, BSD-2-Clause, an
8 M-parameter Whisper-Tiny encoder with a linear head, 8 MB int8 ONNX, 23 languages including
French and English) listens to the end of the utterance itself, its words *and* its intonation,
and says whether the turn is complete.

The policy (engine.py, `VoiceEngine._endpoint`), when the model is here:

1. The VAD (Silero) hears `turn_check_s` of silence (0.2 s): Smart Turn reads the last 8 s of
   the utterance (about 10-30 ms on a laptop CPU).
2. Complete (probability >= 0.5): the question ends now.
3. Incomplete: the voice keeps listening; the question ends after `turn_max_s` of silence (2 s)
   unless the person goes on (then the next pause is judged again, on the longer utterance).

Without the model (or with `turn: "silence"`), the older rule applies: `end_silence_s`, longer
when the words so far announce more (filters.announces_more).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Callable, Protocol

import numpy as np

from .features import whisper_log_mel

log = logging.getLogger("marvin.voice.turn")

TURN_CHOICES = ("auto", "smart-turn", "silence")
COMPLETE = 0.5          # Smart Turn's own threshold


@dataclass
class TurnDecision:
    complete: bool
    probability: float
    seconds: float              # time the decision took


class TurnDetector(Protocol):
    def decide(self, pcm: np.ndarray) -> TurnDecision:
        """`pcm`: the utterance so far (16 kHz int16), ending with the pause being judged."""
        ...


class SmartTurn:
    """Smart Turn v3.x on onnxruntime (CPU, one thread)."""

    name = "smart-turn"

    def __init__(self, path=None, threshold: float = COMPLETE):
        from . import models
        path = path or models.path_of("smart-turn")
        if path is None:
            raise FileNotFoundError("Smart Turn is not downloaded (python -m marvin_host.voice.models --prefetch)")
        self._session = models.onnx_session(path)
        self.threshold = threshold
        t = time.monotonic()
        self.decide(np.zeros(16000, np.int16))           # load and optimise now, not at the first pause
        log.info("Smart Turn ready in %.2f s", time.monotonic() - t)

    def probability(self, pcm: np.ndarray) -> float:
        feats = whisper_log_mel(pcm)[None, :, :]
        return float(np.asarray(self._session.run(None, {"input_features": feats})[0]).reshape(-1)[0])

    def decide(self, pcm: np.ndarray) -> TurnDecision:
        t = time.monotonic()
        p = self.probability(pcm)
        return TurnDecision(p >= self.threshold, p, time.monotonic() - t)


class FakeTurn:
    """For tests: `fn(pcm) -> probability`, or a fixed probability; records the calls."""

    name = "fake"

    def __init__(self, fn: Callable[[np.ndarray], float] | float = 1.0, seconds: float = 0.0):
        self._fn = fn
        self.seconds = seconds
        self.calls: list[float] = []         # duration of each judged utterance, seconds

    def decide(self, pcm: np.ndarray) -> TurnDecision:
        self.calls.append(len(pcm) / 16000)
        p = float(self._fn(pcm) if callable(self._fn) else self._fn)
        return TurnDecision(p >= COMPLETE, p, self.seconds)


def make_turn(kind: str = "auto") -> TurnDetector | None:
    """'smart-turn', 'silence' (None: silence timers), or 'auto' (Smart Turn when its model is
    here). 'smart-turn' without the model falls back to silence with a warning."""
    if kind == "silence":
        return None
    try:
        return SmartTurn()
    except (FileNotFoundError, ImportError) as e:
        (log.warning if kind == "smart-turn" else log.info)("Smart Turn unavailable (%s): the end of a question is a "
                                                            "silence", e)
    except Exception as e:                                  # noqa: BLE001 - a broken file or runtime
        log.warning("Smart Turn failed to load (%s): the end of a question is a silence", e)
    return None
