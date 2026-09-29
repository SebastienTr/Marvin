"""What Whisper hears that nobody said.

Whisper was trained on subtitled video: on silence, breath, a chair creaking or background music it
readily "hears" the end of a video: "Thank you.", "Thanks for watching!", "I'm going to go.",
"Sous-titres réalisés par la communauté d'Amara.org". The assistant keeps these out in layers:

1. Speech evidence (`speech_evidence`), before Whisper runs: enough voiced frames, a large
   enough share of the utterance voiced, loud enough.
2. Decoder confidence (`decoder_reason`), per Whisper segment: the probability that there was no
   speech, the average token log-probability, and the compression ratio (repetition loops).
3. Known hallucinations (`hallucination_reason`): a curated list of phrases, matched on the whole
   transcript only (exact or near-exact), plus text with no words and runaway repetitions.
4. In a follow-up window or with --no-wake (no name to confirm the person talks to Marvin):
   `follow_up_reason` also requires the conversation's language (unless the detection is sure)
   and at least two words, with a few exceptions (see `follow_up_reason`).

Extend `HALLUCINATIONS` when a new one shows up in the log as "heard: ..." followed by a turn nobody
asked for: write it as said, the matching normalises case, accents and punctuation.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

from difflib import SequenceMatcher

import numpy as np

from ..audio import FRAME_MS
from .vad import Segment, rms_dbfs
from .wake import is_wake_word, normalize

# ---------------------------------------------------------------- 1. speech evidence

MIN_VOICED_S = 0.3          # at least this much voiced audio
MIN_VOICED_RATIO = 0.25     # of the utterance (pre-roll and trailing silence included)
MIN_DBFS = -50.0            # level of the loudest half of the utterance


def speech_evidence(seg: Segment) -> str | None:
    """None if the utterance looks like speech, else why not."""
    voiced_s = seg.voiced * FRAME_MS / 1000
    if seg.voiced and voiced_s < MIN_VOICED_S:
        return f"only {voiced_s:.2f} s of speech"
    if seg.voiced and seg.duration > 0 and voiced_s / seg.duration < MIN_VOICED_RATIO:
        return f"mostly silence ({voiced_s:.2f} of {seg.duration:.2f} s voiced)"
    if loud_dbfs(seg.pcm) < MIN_DBFS:
        return f"too quiet ({loud_dbfs(seg.pcm):.0f} dBFS)"
    return None


def loud_dbfs(pcm: np.ndarray, frame: int = 320) -> float:
    """Level of the loudest half of the 20 ms frames: speech, not the pauses around it."""
    n = len(pcm) // frame
    if n == 0:
        return rms_dbfs(pcm)
    levels = sorted((rms_dbfs(pcm[i * frame:(i + 1) * frame]) for i in range(n)), reverse=True)
    top = levels[:max(1, n // 2)]
    return float(np.mean(top))


# ---------------------------------------------------------------- 2. decoder confidence

NO_SPEECH_PROB = 0.6        # with a low log-probability: Whisper thinks there was no speech
LOW_LOGPROB = -1.0
MIN_LOGPROB = -1.2          # below this, whatever no_speech says, the text is a guess
MAX_COMPRESSION = 2.4       # gzip ratio above this: a repetition loop


def decoder_reason(no_speech_prob: float | None, avg_logprob: float | None,
                   compression_ratio: float | None) -> str | None:
    """None if a Whisper segment looks like real speech, else why not (thresholds of OpenAI's
    Whisper, plus a floor on the log-probability for short clips)."""
    if no_speech_prob is not None and avg_logprob is not None \
            and no_speech_prob > NO_SPEECH_PROB and avg_logprob < LOW_LOGPROB:
        return f"no speech (p={no_speech_prob:.2f}, logprob {avg_logprob:.2f})"
    if compression_ratio is not None and compression_ratio > MAX_COMPRESSION:
        return f"repetitive (compression {compression_ratio:.1f})"
    if avg_logprob is not None and avg_logprob < MIN_LOGPROB:
        return f"low confidence (logprob {avg_logprob:.2f})"
    return None


# ---------------------------------------------------------------- 3. known hallucinations

HALLUCINATIONS = [
    # English
    "Thank you.", "Thank you very much.", "Thank you so much.", "Thanks.", "Thanks for watching!",
    "Thank you for watching.", "Thanks for watching, see you next time.", "Thank you for listening.",
    "Please subscribe.", "Please like and subscribe.", "Don't forget to subscribe.",
    "Subscribe to my channel.", "See you next time.", "See you in the next video.",
    "I'll see you next time.", "I'm going to go.", "I'm going to go now.", "Bye.", "Bye-bye.",
    "Goodbye.", "You", "The end.", "So", "Okay.", "Oh.", "Hmm.", "Uh.", "Um.", "I'm sorry.",
    "Music", "Applause", "Silence", "Laughter", "(upbeat music)", "[BLANK_AUDIO]",
    # French
    "Merci.", "Merci beaucoup.", "Merci d'avoir regardé.", "Merci d'avoir regardé cette vidéo.",
    "Merci de votre attention.", "Merci à tous.", "Merci et à bientôt.", "À bientôt.",
    "À la prochaine.", "Au revoir.", "Abonnez-vous.", "N'oubliez pas de vous abonner.",
    "Sous-titres réalisés par la communauté d'Amara.org",
    "Sous-titrage ST' 501", "Sous-titrage Société Radio-Canada", "Sous-titres par Jérémy Diaz",
    "Musique", "Applaudissements", "Rires", "Silence", "Euh.", "Bon.", "Voilà.",
]
# anywhere in the transcript
HALLUCINATION_MARKERS = ["amara org", "sous titres realises", "sous titrage", "subtitles by",
                         "thanks for watching", "merci d avoir regarde", "abonnez vous"]
_BLOCKLIST = {normalize(h) for h in HALLUCINATIONS}


def hallucination_reason(text: str) -> str | None:
    """None if `text` may be something someone said, else why it is probably Whisper's invention."""
    n = normalize(text)
    if not n or not any(ch.isalpha() for ch in n):
        return "no words"
    if n in _BLOCKLIST:
        return "known Whisper hallucination"
    for h in _BLOCKLIST:
        if len(h) >= 8 and SequenceMatcher(None, n, h).ratio() >= 0.9:
            return "known Whisper hallucination"
    for m in HALLUCINATION_MARKERS:
        if m in n:
            return "known Whisper hallucination"
    words = n.split()
    if len(words) >= 6 and len(set(words)) / len(words) < 0.35:
        return "repeated words"
    return None


# ---------------------------------------------------------------- 4. follow-ups (no wake word)

FOLLOW_UP_LANGUAGE_PROB = 0.8
# One word is not enough to be sure someone talks to Marvin, except:
QUESTION_WORDS = {"pourquoi", "comment", "quand", "ou", "combien", "qui", "quoi", "lequel", "laquelle",
                  "vraiment", "why", "how", "when", "where", "who", "what", "which", "really"}
YES_NO = {"oui", "non", "ouais", "si", "yes", "no", "yeah", "nope", "yep"}
# ...and these end the conversation politely (no answer, no more listening)
CLOSERS = {"merci", "merci beaucoup", "super", "parfait", "genial", "cool",
           "thanks", "thank you", "great", "perfect", "au revoir", "bye", "a plus", "bonne nuit",
           "c est bon", "ca marche", "that s all", "good night"}
# ...but "ok" or "d'accord" alone often starts a sentence the speaker has not finished ("OK, j'ai pas encore
# mangé..."): they close only next to a real closer ("ok, merci"), never on their own
SOFT_CLOSERS = {"ok", "okay", "d", "accord", "bon"}

ACCEPT, IGNORE, CLOSE = "accept", "ignore", "close"


def follow_up_decision(text: str, language: str | None, language_prob: float | None,
                       conversation_language: str, last_reply: str = "") -> tuple[str, str]:
    """(ACCEPT | IGNORE | CLOSE, reason) for something heard without the wake word.

    - another language than the conversation's, unless detected with probability >= 0.8: IGNORE;
    - a short thank-you or goodbye ("merci", "ok, super", "thanks"): CLOSE the follow-up window;
    - "oui" / "non" / "yes" / "no": ACCEPT only if Marvin's last reply was a question, else CLOSE;
    - one word: ACCEPT only a question word ("Pourquoi ?", "How?"), else IGNORE;
    - two words or more: ACCEPT.
    """
    n = normalize(text)
    words = [w for w in n.split() if not is_wake_word(w)]
    if language and conversation_language and language != conversation_language \
            and (language_prob is None or language_prob < FOLLOW_UP_LANGUAGE_PROB):
        return IGNORE, f"{language} in a {conversation_language} conversation (p={language_prob})"
    if n in CLOSERS or (words and len(words) <= 3 and all(w in CLOSERS or w in SOFT_CLOSERS for w in words)
                        and any(w in CLOSERS for w in words)):
        return CLOSE, "thanks or goodbye"
    if words and all(w in SOFT_CLOSERS for w in words):
        return IGNORE, "just an ok (the sentence may go on)"
    if len(words) == 1:
        w = words[0]
        if w in YES_NO:
            if last_reply.rstrip().endswith("?"):
                return ACCEPT, "answer to its question"
            return CLOSE, "yes/no without a question"
        if w in QUESTION_WORDS:
            return ACCEPT, "question word"
        return IGNORE, "a single word"
    if not words:
        return IGNORE, "no words"
    if len(words) == 2 and all(w in YES_NO for w in words):
        return (ACCEPT, "answer to its question") if last_reply.rstrip().endswith("?") \
            else (CLOSE, "yes/no without a question")
    return ACCEPT, ""
