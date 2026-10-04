"""A language guess for a short text: which of the expected languages its common words show.

The voice sidecar uses it for a typed question without a language, in its test mode, and for
what Parakeet heard (Parakeet writes the words of any of its 25 languages but does not say which
one it heard: `detect_language`). Cutting
and cleaning what Marvin says is the Java host's (`SentenceSplitter`, `SpeechText`,
`SpeechLanguage`): the sidecar speaks the clauses it is sent as they are.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import re

# A few very common words per language: enough to tell French from English in a spoken reply.
_STOPWORDS = {
    "fr": {"je", "tu", "il", "elle", "nous", "vous", "le", "la", "les", "un", "une", "des", "est", "et",
           "pas", "ne", "de", "du", "que", "qui", "pour", "avec", "mais", "sur", "dans", "mes", "ton",
           "c'est", "j'ai", "oui", "non", "suis", "moi", "toi", "ça", "en", "au", "aux"},
    "en": {"i", "you", "he", "she", "we", "they", "the", "a", "an", "is", "are", "and", "not", "do",
           "of", "to", "that", "for", "with", "but", "on", "in", "my", "your", "it's", "i'm", "yes",
           "no", "am", "me", "it", "this", "have", "get"},
}


_ELISION = re.compile(r"\b(?:[ldjmnstc]|qu|jusqu|lorsqu|puisqu)['\u2019]\w", re.IGNORECASE)
_EN_CONTRACTION = re.compile(r"\w['\u2019](?:s|t|re|ve|ll|m|d)\b", re.IGNORECASE)
_FR_LETTERS = re.compile(r"[éèêëàâçùûüôîïœæ]", re.IGNORECASE)
_EXTRA = {
    "fr": {"bonjour", "salut", "merci", "quoi", "comment", "pourquoi", "quel", "quelle", "fait", "faire", "aussi",
           "très", "tout", "rien", "peux", "veux", "est-ce", "il", "ils", "sont", "ai", "as", "a", "va", "vais",
           "ce", "cette", "ces", "mon", "ma", "ta", "sa", "son", "notre", "votre", "leur", "y", "où", "quand",
           "demain", "aujourd'hui", "temps", "heure", "coucou", "dis", "peut", "être", "bien", "sais", "était"},
    "en": {"hey", "hi", "hello", "thanks", "what", "how", "why", "when", "where", "who", "which", "can", "could",
           "would", "should", "will", "please", "the", "there", "here", "today", "tomorrow", "time", "weather",
           "like", "know", "think", "tell", "about", "just", "was", "were", "be", "been", "some", "fun", "let's",
           "little", "your", "our", "their", "from", "at", "by", "if", "so", "or", "up", "out", "what's"},
}


def language_scores(text: str, candidates: tuple[str, ...] = ("fr", "en")) -> dict[str, float]:
    """How much `text` looks like each candidate language (French and English only have evidence
    beyond the word lists: elisions and accents, contractions)."""
    words = re.findall(r"[a-zA-Zà-ÿÀ-Ÿœ'\u2019-]+", text.lower().replace("\u2019", "'"))
    scores = {lang: float(sum(w in _STOPWORDS.get(lang, ()) or w in _EXTRA.get(lang, ()) for w in words))
              for lang in candidates}
    if "fr" in scores:
        scores["fr"] += len(_ELISION.findall(text)) + 0.5 * len(_FR_LETTERS.findall(text))
    if "en" in scores:
        scores["en"] += len(_EN_CONTRACTION.findall(text))
    return scores


def detect_language(text: str, candidates: tuple[str, ...] = ("fr", "en")) -> tuple[str | None, float]:
    """(the language `text` is mostly in, its share of the evidence 0..1), or (None, 0.0) when
    nothing tells ("Marvin ?", "OK."). Two languages in one sentence give the dominant one with a
    lower share."""
    scores = language_scores(text, candidates)
    total = sum(scores.values())
    if not scores or total < 1.0:
        return None, 0.0
    best = max(scores, key=scores.get)
    return best, scores[best] / total


def guess_language(text: str, candidates: tuple[str, ...] = ("fr", "en")) -> str | None:
    """The candidate language whose common words dominate `text`, or None when unsure."""
    words = re.findall(r"[a-zA-Zà-ÿÀ-Ÿ']+", text.lower())
    scores = {lang: sum(w in _STOPWORDS.get(lang, ()) for w in words) for lang in candidates}
    best = max(scores, key=scores.get)
    others = max((s for lang, s in scores.items() if lang != best), default=0)
    return best if scores[best] >= 2 and scores[best] >= 2 * others else None
