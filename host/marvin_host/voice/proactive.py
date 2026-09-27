"""Marvin speaking first: reminders driven by the brain's events.

`ProactiveSpeaker` is a brain listener. It speaks:
- on STILL_LONG: "You've been sitting for 50 minutes. Time to stretch?" (on by default);
- on ARRIVED after a long absence: "Welcome back." (off by default: some people find a greeting
  every morning charming, others find it creepy).

It never interrupts a conversation, and says at most one thing every `min_interval_s`. Sentences
are in the assistant's language (the language of the last conversation, else the default).

    brain.add_listener(ProactiveSpeaker(assistant))

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Callable

from ..events import Event, EventKind
from . import persona

log = logging.getLogger("marvin.voice.proactive")


@dataclass
class ProactiveConfig:
    still_long: bool = True
    welcome_back: bool = False
    absence_s: float = 30 * 60              # ARRIVED counts as "back" after this long away
    min_interval_s: float = 10 * 60         # at most one proactive sentence per interval


class ProactiveSpeaker:
    def __init__(self, assistant, config: ProactiveConfig | None = None,
                 clock: Callable[[], float] = time.monotonic):
        self.assistant = assistant
        self.config = config or ProactiveConfig()
        self.clock = clock
        self._last_spoken: float | None = None
        self._left_t_us: int | None = None
        self.spoken: list[str] = []

    def __call__(self, ev: Event) -> None:
        c = self.config
        text = None
        lang = self.assistant.language
        if ev.kind == EventKind.LEFT:
            self._left_t_us = ev.t_us
        elif ev.kind == EventKind.STILL_LONG and c.still_long:
            minutes = round(ev.data.get("seated_s", 0) / 60)
            text = (persona.phrase("still_long_hour", lang) if 55 <= minutes <= 65
                    else persona.phrase("still_long", lang, minutes=minutes))
        elif ev.kind == EventKind.ARRIVED and c.welcome_back:
            away = None if self._left_t_us is None else (ev.t_us - self._left_t_us) / 1e6
            if away is not None and away >= c.absence_s:
                text = persona.phrase("welcome_back", lang)
        if text is None:
            return
        now = self.clock()
        if self._last_spoken is not None and now - self._last_spoken < c.min_interval_s:
            log.debug("proactive speech rate-limited: %s", text)
            return
        if self.assistant.say(text, lang):
            self._last_spoken = now
            self.spoken.append(text)
