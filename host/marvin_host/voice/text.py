"""Text helpers for speech: cut a streamed reply into sentences, and remove what cannot be spoken.

The assistant speaks the first sentence while the model is still writing the second one, so the
reply is cut as it arrives. A sentence ends at . ! ? or … followed by a space (not "3.5", not
"M. Dupont"), or at a line break.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import re

# "M. Dupont", "Dr. Who", "etc. " would otherwise end a sentence
ABBREVIATIONS = {"m", "mm", "mme", "mlle", "dr", "pr", "st", "ste", "mr", "mrs", "ms", "prof", "etc", "vs",
                 "cf", "ex", "p", "env", "approx", "no", "n°", "e.g", "i.e"}
_END = re.compile(r"([.!?…]+[\"»”')\]]*)(\s+)|(\n+)")
_CLAUSE = re.compile(r"(?<=[^\d\s])([,;:—])(\s+|$)")          # not "3,5" or "14:30"
_FIRST_END = re.compile(r"(?<=[^\d\s])([.!?…]+[\"»”')\]]*)$")   # a sentence end at the end of the buffer
_EMOJI = re.compile("[\U0001F000-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF‍️]")


def clean_for_speech(text: str) -> str:
    """Removes markdown, URLs and emoji; the model is asked not to produce them, but may."""
    t = re.sub(r"```.*?```", " ", text, flags=re.S)
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)          # [label](link) -> label
    t = re.sub(r"https?://\S+", "", t)
    t = re.sub(r"^\s*(#+|[-*•]|\d+[.)])\s+", "", t, flags=re.M)   # headings, bullets, numbered lists
    t = re.sub(r"[*_`#~|>]+", "", t)
    t = _EMOJI.sub("", t)
    return re.sub(r"[ \t]+", " ", t).strip()


class SentenceSplitter:
    """Feed text pieces as they stream in; get complete sentences back.

    Sentences shorter than `min_chars` are joined with the next one ("Oui. C'est ça." is spoken
    as one), except the very first, which is released as soon as possible to start speaking: it
    may even be a clause, cut at a comma, semicolon or colon once it has `first_clause_words`
    words ("Paris, bien sûr, | la ville..." waits; "La capitale de la France, | c'est Paris."
    does not), so the voice starts before the model finishes its first sentence. 0 disables it.
    For the first chunk it does not even wait for the space after the punctuation (one token less).
    """

    def __init__(self, min_chars: int = 12, first_clause_words: int = 3):
        self.min_chars = min_chars
        self.first_clause_words = first_clause_words
        self._buf = ""
        self._pending = ""          # short sentence waiting to be joined with the next
        self._emitted = 0

    def feed(self, piece: str) -> list[str]:
        self._buf += piece
        out: list[str] = []
        start = 0
        for m in _END.finditer(self._buf):
            if m.group(3) is None:                  # punctuation + space
                word = re.findall(r"[\w°]+$", self._buf[start:m.start(1)])
                if m.group(1) == "." and word and word[0].lower() in ABBREVIATIONS:
                    continue
                end = m.end(1)
            else:
                end = m.start(3)
            s = self._buf[start:end].strip()
            start = m.end()
            if s:
                out.extend(self._push(s))
        self._buf = self._buf[start:]
        if self._emitted == 0 and not out and self.first_clause_words:
            m = _FIRST_END.search(self._buf)
            word = re.findall(r"[\w°]+(?=[.!?…]+\W*$)", self._buf)
            if m and not (m.group(1).startswith(".") and (not word or word[-1].lower() in ABBREVIATIONS
                                                            or len(word[-1]) < 2)):
                head = self._buf.strip()
                self._buf = ""
                return self._push(head)
            for m in _CLAUSE.finditer(self._buf):
                head = self._buf[:m.end(1)].strip()
                if len(re.findall(r"\w+", head)) >= self.first_clause_words:
                    self._buf = self._buf[m.end():]
                    out.extend(self._push(head))
                    break
        return out

    def flush(self) -> list[str]:
        """The rest, at the end of the stream."""
        s = (self._pending + " " + self._buf).strip()
        self._buf = self._pending = ""
        return [s] if s else []

    def _push(self, s: str) -> list[str]:
        s = (self._pending + " " + s).strip()
        if len(s) < self.min_chars and self._emitted > 0:
            self._pending = s
            return []
        self._pending = ""
        self._emitted += 1
        return [s]


def split_sentences(text: str) -> list[str]:
    sp = SentenceSplitter(min_chars=0, first_clause_words=0)
    return sp.feed(text) + sp.flush()
