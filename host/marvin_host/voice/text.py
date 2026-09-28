"""Text helpers for speech: cut a streamed reply into sentences, and remove what cannot be spoken.

The assistant speaks the first sentence while the model is still writing the second one, so the
reply is cut as it arrives. A sentence ends at . ! ? or … followed by a space (not "3.5", not
"M. Dupont"), or at a line break.

A model given tools sometimes writes the tool call as text (a JSON object, `<tool_call>` tags)
instead of calling it: `payload_tool_calls` reads such calls back, and `clean_for_speech` never lets
JSON or tool-call markup reach the speaker.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import re

# "M. Dupont", "Dr. Who", "etc. " would otherwise end a sentence
ABBREVIATIONS = {"m", "mm", "mme", "mlle", "dr", "pr", "st", "ste", "mr", "mrs", "ms", "prof", "etc", "vs",
                 "cf", "ex", "p", "env", "approx", "no", "n°", "e.g", "i.e"}
_END = re.compile(r"([.!?…]+[\"»”')\]]*)(\s+)|(\n+)")
_CLAUSE = re.compile(r"(?<=[^\d\s])([,;:—])(\s+|$)")          # not "3,5" or "14:30"
_FIRST_END = re.compile(r"(?<=[^\d\s])([.!?…]+[\"»”')\]]*)$")   # a sentence end at the end of the buffer
_EMOJI = re.compile("[\U0001F000-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF‍️]")
_TOOL_TAG = re.compile(r"<\|?/?(tool_call|tool_calls|function_call|functions?)\|?>", re.I)
_BRACES = re.compile(r"\{[^{}]*\}")
_JSON_KEY = re.compile(r'"[A-Za-z_][\w-]*"\s*:\s*["{\[\d-]')    # "key": value
_EMPTY_LIST = re.compile(r"\[[\s,]*\]")
# how tool calls written as text begin: a reply starting with a prefix of one of these waits
_OPENINGS = ("<tool_call>", "<|tool_call|>", "<function_call>", "<functions>", "```")


def looks_like_payload(text: str) -> bool | None:
    """Whether a reply starts like a tool call written as text (JSON, a code block, a tag) rather
    than like a sentence, decided on its first characters so the reply can be held back. None:
    not sure yet ("<tool_c" may become a tag), wait for more."""
    t = text.lstrip()
    if not t:
        return None
    if t[:1] in ("{", "[") or t.startswith("```") or _TOOL_TAG.match(t):
        return True
    if any(o.startswith(t) and len(t) < len(o) for o in _OPENINGS):
        return None
    return False


def strip_payload(text: str) -> str:
    """`text` without JSON objects and tool-call tags; "" if what is left still looks like a piece
    of JSON (a streamed chunk can hold half an object)."""
    t = _TOOL_TAG.sub(" ", text)
    while True:
        n = _BRACES.sub(" ", t)
        if n == t:
            break
        t = n
    if "{" in t or "}" in t or _JSON_KEY.search(t):
        return ""
    return _EMPTY_LIST.sub(" ", t)


def payload_tool_calls(text: str) -> list[dict]:
    """Tool calls written as text: every JSON object in `text` (bare, in a code block or between
    `<tool_call>` tags, or a list of them) that has a "name" (or a "function"). [] if none."""
    t = _TOOL_TAG.sub("\n", re.sub(r"```(?:json)?", "\n", text))
    dec = json.JSONDecoder()
    found: list[dict] = []
    i = 0
    while i < len(t):
        if t[i] not in "{[":
            i += 1
            continue
        try:
            obj, end = dec.raw_decode(t, i)
        except ValueError:
            i += 1
            continue
        for o in (obj if isinstance(obj, list) else [obj]):
            if isinstance(o, dict) and (isinstance(o.get("name"), str) or isinstance(o.get("function"), dict)):
                found.append(o)
        i = end
    return found


_THINK_BLOCK = re.compile(r"<think>.*?</think\s*>?", re.S)
_THINK_END = re.compile(r"</think\b\s*>?")


def strip_thinking(text: str) -> str:
    """Drops reasoning a model wrote into its answer: `<think>…</think>` blocks, and everything
    before a lone `</think>` (after a tool result, Qwen 3.5 models sometimes think in the answer
    itself even with thinking off, then close the tag and answer again)."""
    text = _THINK_BLOCK.sub("", text)
    parts = _THINK_END.split(text)
    return parts[-1].lstrip() if len(parts) > 1 else text


def clean_for_speech(text: str) -> str:
    """Removes markdown, URLs, emoji and anything that looks like JSON or a tool call; the model is
    asked not to produce them, but may."""
    t = _THINK_END.sub(" ", _THINK_BLOCK.sub(" ", text))
    t = re.sub(r"```.*?```", " ", t, flags=re.S)
    t = strip_payload(t)
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
