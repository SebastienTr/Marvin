"""The wake word, "Marvin".

`WakeWordDetector` is the interface: given one utterance, say whether it addresses the robot and,
if the detector transcribed it, what was asked. Today's implementation, `TranscriptWakeWord`, is
pragmatic: a cheap level/length pre-filter, then Whisper on the utterance, then a fuzzy match on
the transcript. It costs one Whisper run per candidate utterance, which is fine on a desk (people
do not talk to themselves all day) and gives the question for free ("Marvin, what time is it?").

A dedicated keyword-spotting model (a small CNN trained on Google Speech Commands, whose 35 words
include "marvin") would be cheaper and more robust to accents, and is a good future ML project; it
only needs to implement `WakeWordDetector` and return `WakeMatch(query=None)`, in which case the
assistant transcribes the utterance itself.

Where the name may be: at the start ("Marvin, ...", "Dis Marvin, ...", "Hey Marvin ...") or at the
end ("..., Marvin?"). In the middle of a sentence it is ignored: someone talking *about* Marvin is
not talking *to* it.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Protocol

import numpy as np

from ..audio import SAMPLE_RATE
from .stt import STT, Transcript
from .vad import rms_dbfs

WAKE_WORD = "marvin"
# how Whisper and people spell it; anything else must start with "marv"/"merv" and be close to "marvin"
VARIANTS = {"marvin", "marvine", "marven", "marvain", "marvyn", "mervin", "mervyn", "marvins", "marvinn",
            "marvinne", "marvein", "marvan", "marwin"}
NOT_MARVIN = {"marvel", "marvels", "martin", "marin", "marine", "marina", "mardi", "matin", "marvelous"}
# words that may come before the name: "hey Marvin", "dis Marvin", "ok Marvin", "bonjour Marvin"
LEADING = {"hey", "hi", "hello", "ok", "okay", "oh", "dis", "salut", "bonjour", "bonsoir", "coucou", "eh",
           "he", "alors", "et", "euh", "so", "yo", "allo"}
MAX_LEADING = 2


def normalize(text: str) -> str:
    """Lower case, no accents, letters and digits only."""
    t = unicodedata.normalize("NFKD", text.lower())
    t = "".join(ch for ch in t if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9']+", " ", t).replace("'", " ").strip()


def _edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def is_wake_word(word: str) -> bool:
    """Fuzzy match of one normalized word against "marvin"."""
    w = word.strip()
    if w in NOT_MARVIN:
        return False
    if w in VARIANTS:
        return True
    return (w.startswith(("marv", "merv")) and 5 <= len(w) <= 8
            and _edit_distance(w, WAKE_WORD) <= 2)


@dataclass
class WakeMatch:
    """The utterance addresses the robot. `query`: what was said besides the name ("" = nothing,
    wait for the question), or None if the detector did not transcribe the utterance."""

    query: str | None
    transcript: Transcript | None = None


def match_wake_word(text: str) -> str | None:
    """If `text` addresses Marvin, returns the rest of it (the question, "" if none), else None.

    >>> match_wake_word("Marvin, quelle heure est-il ?")
    'quelle heure est-il ?'
    >>> match_wake_word("Hey Marvain.")
    ''
    >>> match_wake_word("J'ai vu Marvin hier") is None
    True
    """
    # Work on the original words so that the query keeps its punctuation and case. Punctuation
    # standing alone ("Marvin ?") sticks to the previous word.
    words: list[str] = []
    for w in re.findall(r"\S+", text):
        if words and not normalize(w):
            words[-1] += " " + w
        else:
            words.append(w)
    norm = [normalize(w).replace(" ", "") for w in words]
    n = len(words)

    def hit(i: int) -> int:
        """Number of words (1 or 2) forming the name at position i, 0 if none."""
        if i < n and is_wake_word(norm[i]):
            return 1
        if i + 1 < n and norm[i] and norm[i + 1] and is_wake_word(norm[i] + norm[i + 1]):  # "Mar vin"
            return 2
        return 0

    # at the start, after up to MAX_LEADING filler words
    for i in range(min(MAX_LEADING, n) + 1):
        if i > 0 and norm[i - 1] not in LEADING:
            break
        k = hit(i)
        if k:
            rest = " ".join(words[i + k:]).lstrip(" ,;:.!?-\u2014\u2026")
            return rest if re.search(r"\w", rest) else ""
    # at the end: "..., Marvin ?"
    for k in (1, 2):
        if n - k >= 0 and hit(n - k) == k:
            rest = " ".join(words[:n - k]).rstrip(" ,;:-\u2014")
            return rest if re.search(r"\w", rest) else ""
    return None


class WakeWordDetector(Protocol):
    def check(self, pcm: np.ndarray, language: str | None = None) -> WakeMatch | None:
        """One utterance (16 kHz int16) -> a match, or None if it is not for the robot."""
        ...


class TranscriptWakeWord:
    """Pre-filter (length and level), then transcribe and fuzzy-match the name."""

    def __init__(self, stt: STT, min_s: float = 0.3, max_s: float = 12.0, min_dbfs: float = -45.0):
        self.stt = stt
        self.min_s, self.max_s, self.min_dbfs = min_s, max_s, min_dbfs
        self.last: Transcript | None = None     # transcript of the last utterance that passed the pre-filter

    def candidate(self, pcm: np.ndarray) -> bool:
        d = len(pcm) / SAMPLE_RATE
        return self.min_s <= d <= self.max_s and rms_dbfs(pcm) >= self.min_dbfs

    def check(self, pcm: np.ndarray, language: str | None = None,
              transcript: Transcript | None = None) -> WakeMatch | None:
        """`transcript`: already transcribed (speculatively, while the speaker was pausing)."""
        self.last = None
        if not self.candidate(pcm):
            return None
        tr = transcript or self.stt.transcribe(pcm, language)
        self.last = tr
        rest = match_wake_word(tr.text)
        return None if rest is None else WakeMatch(rest, tr)
