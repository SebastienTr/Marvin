"""Marvin must never answer its own voice.

Without acoustic echo cancellation, the microphone hears what the speaker plays. Two layers keep
that sound out of the conversation:

1. Half duplex (`EchoGate`): while Marvin speaks, and for a short tail after the speaker really
   stops (output latency plus room echo), microphone frames are not heard and any utterance in
   progress is dropped. On for every device without echo cancellation (device.py); a device
   profile with `duplex` turns it off (a headset, or a robot with echo cancellation).
2. Transcript filter (`EchoFilter`), in every state and in both modes: a transcript that repeats
   what Marvin said (the reply being spoken, or one that ended just before the utterance started)
   is ignored. An utterance that starts well after Marvin stopped cannot be an echo, so it is
   answered even if it takes up Marvin's words ("Je suis assis et immobile." after "reste assis
   et immobile").

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

    def __init__(self, keep: int = 2, ratio: float = 0.6, window_s: float = 2.5,
                 clock: Callable[[], float] = time.monotonic):
        self.ratio = ratio
        self.window_s = window_s                             # how long after a reply its echo can start
        self.clock = clock
        self._said: deque[tuple[list[str], float]] = deque(maxlen=keep)   # finished replies, end time
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
                self._said.append((toks, self.clock()))
            self._current = []

    def current_has_wake_word(self) -> bool:
        with self._lock:
            return any(is_wake_word(w) for w in self._current)

    def is_own_voice(self, heard: str, started: float | None = None) -> bool:
        """`started`: when the utterance began (`clock` time); None counts every remembered reply."""
        h = _tokens(heard)
        if not h:
            return False
        with self._lock:
            said = [r for r, end in self._said if started is None or started <= end + self.window_s]
            replies = [r for r in (self._current, *said) if r]
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


OPEN, MASK, CLOSED = "open", "mask", "closed"


class EchoGate:
    """What the microphone's frames are worth, judged when they are captured (`state()`):

    - OPEN: heard;
    - MASK: kept for the pre-roll of an utterance, but never taken for speech (the VAD says
      silence): Marvin's own earcon, and the moment between Talk now and the speaker having really
      stopped. An utterance going on is not dropped;
    - CLOSED (half duplex only): deaf, an utterance in progress is dropped. From the moment Marvin
      starts speaking until `tail_s` after the speaker has played everything (`speak()` before the
      first sound returns a token, `done(token)` after `sink.wait()`), plus the device's output
      latency.

    `cut(mask_s)` ends speech at once (it was stopped: barge-in, Talk now, Stop, after the speaker
    was confirmed silent) and makes any older token stale, so a late `done()` from the thread that
    was speaking cannot close the microphone again. `soften()` turns CLOSED into MASK until the next
    `cut()` or `speak()` (Talk now pressed: nothing the person says from now on is lost)."""

    def __init__(self, tail_s: float = 0.8, enabled: bool = True, output_latency_s: float = 0.0,
                 clock: Callable[[], float] = time.monotonic):
        self.clock = clock
        self._lock = threading.Lock()
        self.configure(tail_s, enabled, output_latency_s)
        self._speaking = False
        self._soft = False
        self._until = 0.0                   # closed (or masked if soft) until then
        self._mask_until = 0.0
        self._token = 0

    def configure(self, tail_s: float, enabled: bool, output_latency_s: float = 0.0) -> None:
        """A device's echo profile: half duplex (`enabled`) and its tail."""
        with self._lock:
            self.enabled = enabled
            self.tail_s = tail_s + output_latency_s

    def speak(self) -> int:
        """Marvin starts speaking. Returns the token for `done`."""
        with self._lock:
            self._token += 1
            self._speaking = True
            self._soft = False
            return self._token

    def done(self, token: int) -> None:
        """The speaker has played everything of the speech `token` started: deaf for the tail."""
        with self._lock:
            if token == self._token and self._speaking:
                self._speaking = False
                self._until = self.clock() + self.tail_s

    def cut(self, mask_s: float = 0.0) -> None:
        """Speech was stopped and the speaker is silent: open now, masked for `mask_s`."""
        with self._lock:
            self._token += 1
            self._speaking = False
            self._soft = False
            self._until = 0.0
            self._mask_until = max(self._mask_until, self.clock() + mask_s)

    def soften(self) -> None:
        with self._lock:
            self._soft = True

    def mask(self, seconds: float) -> None:
        """An earcon is playing: not speech, whatever the VAD says."""
        with self._lock:
            self._mask_until = max(self._mask_until, self.clock() + seconds)

    @property
    def speaking(self) -> bool:
        return self._speaking

    def state(self) -> str:
        with self._lock:
            now = self.clock()
            if self.enabled and (self._speaking or now < self._until):
                return MASK if self._soft else CLOSED
            return MASK if now < self._mask_until else OPEN

    def closed(self) -> bool:
        return self.state() == CLOSED
