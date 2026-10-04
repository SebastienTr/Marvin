"""The owner's words: names and terms speech recognition should get right.

Speech recognition knows "Paris" but not "Aashma", "Laurine" or "Bosco": it writes what it heard
the way it would spell an ordinary word ("Ashma", "Lorine", "Bosko"), and Marvin then remembers
facts about a person who does not exist. The `vocabulary` setting (Marvin > Voice & models; in
voice.json `"vocabulary": ["Marvin", "Aashma", "Laurine", "Bosco"]`) is used twice:

1. **Biasing**, where the engine supports it: Whisper gets the words as hot words
   (faster-whisper) or in its initial prompt (MLX). Parakeet's MLX port has no context biasing
   (NVIDIA's NeMo has GPU-PB word boosting, not ported), so for Parakeet only step 2 applies.
2. **Correction** (`Lexicon.correct`), after any engine: a word (or two words run together,
   "Ash ma") that sounds like one of the terms is replaced by it. "Sounds like" is a small
   French/English phonetic key (doubled letters, ph/f, c/k/qu, y/i, silent h and final e,
   "-ine"/"-in"), compared with an edit distance: equal keys always count; a near key counts
   only for a capitalised word or a very close one, and never for a common word ("marin",
   "martin", "matin" are not "Marvin").

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

MAX_TERMS = 40
MAX_TERM_LEN = 40

# words that are never corrected into a term, whatever they sound like
COMMON = {
    "marin", "marine", "marina", "martin", "matin", "mardi", "marvel", "marvels", "marvelous", "mari", "marie",
    "lorraine", "laurent", "lorie", "loire", "bosch", "boss", "bisou", "basque", "basket", "basco", "boscage",
    "asthma", "asma", "ashes", "eau", "est", "les", "des", "mais", "mars", "marche", "merci", "mercy", "martine",
    "maman", "machine", "marvellous", "morning", "moving", "mourir", "murmure", "laurier", "lorsque", "lorry",
    "aussi", "assez", "assise", "parce", "puisque", "quelque", "comme", "comment", "bonjour", "bonsoir",
}


def _fold(text: str) -> str:
    t = unicodedata.normalize("NFKD", text.lower())
    return "".join(ch for ch in t if not unicodedata.combining(ch) and ch.isalpha())


def phonetic_key(word: str) -> str:
    """A rough French/English sound key: "Marvine" and "Marvin" share "marvin", "Ashma" and
    "Aashma" share "asma", "Lorine" and "Laurine" share "lorin"."""
    w = _fold(word)
    if not w:
        return ""
    for a, b in (("ph", "f"), ("sch", "s"), ("ch", "s"), ("sh", "s"), ("ck", "k"), ("qu", "k"), ("gu", "g"),
                 ("au", "o"), ("eau", "o"), ("ou", "u"), ("oo", "u"), ("ai", "e"), ("ei", "e"), ("ay", "e"),
                 ("ee", "i"), ("ea", "i"), ("y", "i"), ("w", "v"), ("x", "ks"), ("z", "s"), ("c", "k")):
        w = w.replace(a, b)
    w = re.sub(r"(?<=[^aeiou])h|^h", "", w)               # silent h
    w = re.sub(r"(.)\1+", r"\1", w)                       # doubled letters
    w = re.sub(r"(in|en|an|on|un)e$", r"\1", w)          # Marvine -> marvin
    if len(w) > 3:
        w = re.sub(r"e$", "", w)                          # final silent e
    w = re.sub(r"^a(?=a)", "", w)
    return w


def _distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def clean_terms(terms) -> list[str]:
    """The vocabulary as the settings give it: stripped, de-duplicated, bounded."""
    out, seen = [], set()
    for t in terms or ():
        t = " ".join(str(t).split())[:MAX_TERM_LEN]
        if t and t.lower() not in seen:
            seen.add(t.lower())
            out.append(t)
    return out[:MAX_TERMS]


_WORD = re.compile(r"[\w'’-]+|[^\w\s]+|\s+", re.UNICODE)


@dataclass
class Lexicon:
    terms: list[str] = field(default_factory=list)

    def __post_init__(self):
        self.terms = clean_terms(self.terms)
        self._keys = [(t, phonetic_key(t), _fold(t)) for t in self.terms if " " not in t and phonetic_key(t)]

    def __bool__(self) -> bool:
        return bool(self._keys)

    def _match(self, word: str, capitalised: bool) -> str | None:
        folded = _fold(word)
        if len(folded) < 3 or folded in COMMON:
            return None
        key = phonetic_key(word)
        best = None
        for term, tkey, tfold in self._keys:
            if folded == tfold:
                return term
            if not key or key[0] != tkey[0]:
                continue
            d = _distance(key, tkey)
            n = max(len(key), len(tkey))
            close = d == 0 or (capitalised and d <= (1 if n <= 6 else 2)) or (n >= 6 and d <= 1 and len(folded) >= 5)
            if close and (best is None or d < best[0]):
                best = (d, term)
        return best[1] if best else None

    def correct(self, text: str) -> str:
        """`text` with the words that sound like a term spelt as the term."""
        if not self._keys or not text:
            return text
        tokens = _WORD.findall(text)
        out: list[str] = []
        i = 0
        while i < len(tokens):
            tok = tokens[i]
            if not re.match(r"\w", tok):
                out.append(tok)
                i += 1
                continue
            # two words run apart ("Ash ma", "Mar vin"): try them together first
            if i + 2 < len(tokens) and tokens[i + 1] == " " and re.match(r"\w", tokens[i + 2]):
                joined = self._match(tok + tokens[i + 2], tok[:1].isupper())
                if joined and _fold(tok) not in COMMON and _fold(tokens[i + 2]) not in COMMON \
                        and len(_fold(tok)) <= 4 and len(_fold(tokens[i + 2])) <= 4:
                    out.append(joined)
                    i += 3
                    continue
            hit = self._match(tok, tok[:1].isupper())
            out.append(hit if hit else tok)
            i += 1
        return "".join(out)
