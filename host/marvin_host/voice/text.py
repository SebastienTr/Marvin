"""A language guess for a short text: which of the expected languages its common words show.

The voice sidecar uses it for a typed question without a language and in its test mode. Cutting
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


def guess_language(text: str, candidates: tuple[str, ...] = ("fr", "en")) -> str | None:
    """The candidate language whose common words dominate `text`, or None when unsure."""
    words = re.findall(r"[a-zA-Zà-ÿÀ-Ÿ']+", text.lower())
    scores = {lang: sum(w in _STOPWORDS.get(lang, ()) for w in words) for lang in candidates}
    best = max(scores, key=scores.get)
    others = max((s for lang, s in scores.items() if lang != best), default=0)
    return best if scores[best] >= 2 and scores[best] >= 2 * others else None
