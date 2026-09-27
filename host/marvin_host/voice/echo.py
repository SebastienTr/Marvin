"""Marvin must never answer its own voice.

Without acoustic echo cancellation, the microphone hears what the speaker plays. Two layers keep
that sound out of the conversation:

1. Half duplex (`EchoGate`): while Marvin speaks, and for a short tail after the speaker really
   stops (output latency plus room echo), microphone frames are replaced by silence and any
   utterance in progress is dropped. On by default with the computer's speakers; `--duplex` turns
   it off for a headset (or, later, a robot with echo cancellation).
2. Transcript filter (`EchoFilter`), in every state and in both modes: a transcript that repeats
   what Marvin said recently (its last replies, including the one being spoken) is ignored.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import threading
import time
from collections import deque
from difflib import SequenceMatcher
from typing import Callable

from .wake import is_wake_word, normalize


def _tokens(text: str) -> list[str]:
    return normalize(text).split()


class EchoFilter:
    """Remembers what Marvin said; recognises it when the microphone brings it back."""

    def __init__(self, keep: int = 2, ratio: float = 0.6):
        self.ratio = ratio
        self._said: deque[list[str]] = deque(maxlen=keep)     # finished replies
        self._current: list[str] = []                        # the reply being spoken
        self._lock = threading.Lock()

    def speaking(self, text: str) -> None:
        """What Marvin is saying right now (grows sentence by sentence)."""
        with self._lock:
            self._current = _tokens(text)

    def said(self, text: str) -> None:
        """A reply is over (finished or interrupted)."""
        with self._lock:
            toks = _tokens(text) or self._current
            if toks:
                self._said.append(toks)
            self._current = []

    def current_has_wake_word(self) -> bool:
        with self._lock:
            return any(is_wake_word(w) for w in self._current)

    def is_own_voice(self, heard: str) -> bool:
        h = _tokens(heard)
        if not h:
            return False
        with self._lock:
            replies = [r for r in (self._current, *self._said) if r]
        return any(_echoes(h, r, self.ratio) for r in replies)


def _echoes(heard: list[str], reply: list[str], ratio: float) -> bool:
    """True if `heard` is (a garbled copy of) `reply` or of a contiguous part of it."""
    content = [w for w in heard if not is_wake_word(w)]
    if not content:
        return False                        # only the name: the user calling, not an echo
    if len(heard) <= 3:                     # "with you", "merci beaucoup": must appear as is
        n = len(heard)
        return n >= 2 and any(reply[i:i + n] == heard for i in range(len(reply) - n + 1))
    sm = SequenceMatcher(None, heard, reply, autojunk=False)
    if sm.ratio() >= ratio:
        return True                         # the whole reply, slightly garbled
    longest = sm.find_longest_match(0, len(heard), 0, len(reply)).size
    covered = sum(b.size for b in sm.get_matching_blocks())
    return longest >= max(3, ratio * len(heard)) or (covered >= 0.8 * len(heard) and longest >= 3)


class EchoGate:
    """Half duplex: `closed()` from the moment Marvin starts speaking until `tail_s` after the sink
    has played everything (the assistant calls `speaking(True)` before the first sentence and
    `speaking(False)` after `sink.wait()`). The short listening chime is not gated. The speaker's
    output latency is added to the tail when the sink reports it
    (`output_latency` attribute, seconds)."""

    def __init__(self, sink, tail_s: float = 0.8, enabled: bool = True, clock: Callable[[], float] = time.monotonic):
        self.sink = sink
        self.enabled = enabled
        self.tail_s = tail_s + float(getattr(sink, "output_latency", 0.0) or 0.0)
        self.clock = clock
        self._speaking = False
        self._until = 0.0

    def speaking(self, on: bool) -> None:
        """Marvin starts (True) or has finished (False) speaking."""
        if on:
            self._speaking = True
        elif self._speaking:
            self._speaking = False
            self._until = self.clock() + self.tail_s

    def closed(self) -> bool:
        if not self.enabled:
            return False
        return self._speaking or self.clock() < self._until
