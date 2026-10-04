"""Voice calibration and enrollment: Marvin learns the room, the microphone and a person's voice.

The app asks the person to stay quiet for a moment, then to read two or three short sentences
(French and English). Through the device the voice is using, the sidecar:

1. **measures** the room's noise floor (the quiet part) and the person's speech level (the
   sentences), and derives the device's thresholds (`calibrate`): the VAD's floor
   (`vad_min_dbfs`), the "too quiet" level of an utterance (`speech_min_dbfs`) and, when Silero
   runs, its speech probability threshold (`vad_threshold`, raised when the room's noise looks
   like speech to it). The Java host stores them in that device's profile in voice.json;
2. **recognises** each sentence and shows what it heard ("I heard: ...", with how close it is to
   the text), so the person sees speech recognition work before relying on it;
3. **computes a voiceprint** (speaker.py): the mean of the sentences' speaker embeddings, kept by
   the Java host under the person's name (voiceprints.json, on this computer only).

`Enrollment` is the state machine; the engine feeds it every frame while it runs (the voice
answers nothing meanwhile). It uses its own permissive segmenter (no floor), so a microphone too
quiet for the current thresholds can still be calibrated.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import difflib
import logging
from dataclasses import dataclass, field
from typing import Callable

import numpy as np

from ..audio import FRAME_MS
from .vad import Segmenter, SegmenterConfig, Vad, rms_dbfs
from .wake import normalize

log = logging.getLogger("marvin.voice.enroll")

# what the app shows when it sends none (the Java host sends its own, in the person's languages)
SENTENCES = ("Bonjour Marvin, je m'appelle {name}.",
             "Hey Marvin, what's the weather like today?",
             "Marvin, rappelle-moi d'arroser les plantes demain matin.")

NOISE_S = 1.5               # the quiet part
SENTENCE_TIMEOUT_S = 12.0   # nobody speaks: the calibration stops
MIN_SENTENCE_S = 0.7        # shorter: "say the whole sentence"
MAX_TRIES = 3

# thresholds' bounds (dBFS)
VAD_MIN_RANGE = (-80.0, -35.0)
SPEECH_MIN_RANGE = (-70.0, -25.0)
SILERO_RANGE = (0.5, 0.85)


@dataclass
class Calibration:
    noise_dbfs: float           # the room's noise (90th percentile of the quiet part's 20 ms frames)
    speech_dbfs: float          # the person's speech (median of the loud frames of the sentences)
    vad_min_dbfs: float
    speech_min_dbfs: float
    vad_threshold: float
    warnings: list[str] = field(default_factory=list)

    @property
    def snr_db(self) -> float:
        return self.speech_dbfs - self.noise_dbfs


def frame_levels(pcm: np.ndarray, frame: int = 320) -> np.ndarray:
    """dBFS of each 20 ms frame of `pcm`."""
    n = len(pcm) // frame
    return np.array([rms_dbfs(pcm[i * frame:(i + 1) * frame]) for i in range(n)], dtype=np.float64)


def speech_level(pcm: np.ndarray, noise_dbfs: float) -> tuple[float, np.ndarray]:
    """(median level of the frames clearly above the noise, those levels): the voice's level."""
    lv = frame_levels(pcm)
    loud = lv[lv > noise_dbfs + 6.0]
    if len(loud) < 5:                           # barely above the noise: take its loudest half
        loud = np.sort(lv)[len(lv) // 2:] if len(lv) else np.array([-100.0])
    return float(np.median(loud)), loud


def calibrate(noise_db, speech_db, noise_probs=None) -> Calibration:
    """Thresholds from the quiet part's frame levels (`noise_db`), the sentences' speech frame
    levels (`speech_db`) and, with Silero, its speech probabilities during the quiet part.

    - `vad_min_dbfs`: 6 dB above the noise, but at least 25 dB below the voice (quiet consonants);
    - `speech_min_dbfs`: an utterance whose loudest half is 18 dB under the person's voice (from
      across the room) still counts, but never less than 8 dB above the noise;
    - `vad_threshold`: 0.5 (Silero's own), or 0.1 above what the room's noise reached (95th
      percentile), up to 0.85.
    """
    noise = np.asarray(noise_db, dtype=np.float64)
    speech = np.asarray(speech_db, dtype=np.float64)
    noise = noise[noise > -100] if np.any(noise > -100) else np.array([-90.0])
    floor = float(np.percentile(noise, 90))
    voice = float(np.median(speech)) if len(speech) else floor + 20
    warnings = []
    vad_min = min(floor + 6.0, voice - 25.0)
    speech_min = max(floor + 8.0, voice - 18.0)
    probs = np.asarray(noise_probs if noise_probs is not None else [], dtype=np.float64)
    threshold = 0.5 if not len(probs) else max(0.5, float(np.percentile(probs, 95)) + 0.1)
    snr = voice - floor
    if voice > -6.0:
        warnings.append("Your voice clips: lower the microphone's input level.")
    if voice < -45.0:
        warnings.append("Your voice is very quiet: move closer or raise the microphone's input level.")
    if snr < 15.0:
        warnings.append(f"The room is noisy for this microphone (voice {snr:.0f} dB above the noise): "
                        "move closer or make it quieter.")
    if threshold > 0.6:
        warnings.append("The room's noise sounds like speech to Marvin (a TV, music, people): it will be stricter.")
    clamp = lambda v, r: float(min(max(v, r[0]), r[1]))      # noqa: E731
    return Calibration(round(floor, 1), round(voice, 1), round(clamp(vad_min, VAD_MIN_RANGE), 1),
                       round(clamp(min(speech_min, voice - 6.0), SPEECH_MIN_RANGE), 1),
                       round(clamp(threshold, SILERO_RANGE), 2), warnings)


def text_match(expected: str, heard: str) -> float:
    """How close what was heard is to the sentence, 0..1 (word sequence similarity, accents,
    case and punctuation ignored)."""
    a, b = normalize(expected).split(), normalize(heard).split()
    if not a or not b:
        return 0.0
    return round(difflib.SequenceMatcher(None, a, b).ratio(), 2)


class Enrollment:
    """One calibration run. The engine calls `feed(frame, heard)` for every captured frame
    (`heard`: False while the microphone is deaf); progress goes to `emit(state, **data)`:

    - "noise": the quiet part starts (`seconds`)
    - "sentence": read sentence `index` (`text`, `tries`)
    - "heard": sentence `index` was heard: `text` (the transcript), `match` 0..1, `dbfs`
    - "retry": sentence `index` again, `message` says why
    - "done": `name`, `calibration` (Calibration), `embeddings` (list, may be empty), `transcripts`
    - "failed": `message`
    - "cancelled"
    """

    def __init__(self, name: str, sentences: list[str], *, vad: Vad,
                 transcribe: Callable[[np.ndarray], object], embed: Callable[[np.ndarray], np.ndarray] | None,
                 emit: Callable[..., None], noise_s: float = NOISE_S, timeout_s: float = SENTENCE_TIMEOUT_S):
        self.name = name.strip()
        self.sentences = [s.replace("{name}", self.name) for s in (sentences or SENTENCES)]
        self.vad = vad
        self.segmenter = Segmenter(vad, SegmenterConfig(end_silence_s=0.8, end_silence_long_s=0.0, max_segment_s=15.0,
                                                        min_speech_s=0.3))
        self.transcribe, self.embed, self.emit = transcribe, embed, emit
        self.noise_s, self.timeout_s = noise_s, timeout_s
        self.state = "noise"
        self.index = 0
        self.tries = 0
        self._t = 0.0                       # audio time of this run
        self._phase_t = 0.0                 # when the current phase started
        self._noise: list[float] = []
        self._noise_voiced = 0
        self._noise_probs: list[float] = []
        self._speech: list[np.ndarray] = []
        self.embeddings: list[np.ndarray] = []
        self.transcripts: list[str] = []
        self.matches: list[float] = []
        self.emit("noise", seconds=noise_s)

    @property
    def active(self) -> bool:
        return self.state in ("noise", "sentence")

    def cancel(self) -> None:
        if self.active:
            self.state = "cancelled"
            self.emit("cancelled")

    def feed(self, frame: np.ndarray, heard: bool = True) -> None:
        if not self.active:
            return
        self._t += FRAME_MS / 1000
        if not heard:
            self.segmenter.advance()
            return
        if self.state == "noise":
            voiced = self.vad.is_speech(frame)
            self._noise.append(rms_dbfs(frame))
            self._noise_voiced += voiced
            prob = getattr(self.vad, "prob", None)
            if prob is not None:
                self._noise_probs.append(float(prob))
            if self._t - self._phase_t >= self.noise_s - 1e-9:
                self._start_sentence()
            return
        seg = self.segmenter.feed(frame)
        if seg is not None:
            self._on_sentence(seg.pcm)
        elif not self.segmenter.in_speech and self._t - self._phase_t > self.timeout_s:
            self.state = "failed"
            self.emit("failed", message="Marvin heard nothing: check that the right microphone is chosen and not muted.")

    def _noise_floor(self) -> float:
        lv = np.asarray(self._noise or [-90.0])
        return float(np.percentile(lv, 90))

    def _start_sentence(self) -> None:
        if self.state == "noise" and self._noise and self._noise_voiced > 0.3 * len(self._noise):
            log.info("enrollment: speech during the quiet part (%d/%d frames)", self._noise_voiced, len(self._noise))
        self.state = "sentence"
        self._phase_t = self._t
        self.segmenter.reset()
        self.emit("sentence", index=self.index, text=self.sentences[self.index], tries=self.tries)

    def _retry(self, message: str) -> None:
        self.tries += 1
        if self.tries >= MAX_TRIES:
            self.state = "failed"
            self.emit("failed", message=message)
            return
        self.emit("retry", index=self.index, message=message)
        self._start_sentence()

    def _on_sentence(self, pcm: np.ndarray) -> None:
        seconds = len(pcm) / 16000
        if seconds < MIN_SENTENCE_S:
            self._retry("That was short: read the whole sentence.")
            return
        level, loud = speech_level(pcm, self._noise_floor())
        tr = self.transcribe(pcm)
        text = (getattr(tr, "text", tr) or "").strip()
        if not text:
            self._retry("Marvin could not make out the words: read the sentence again, a little closer.")
            return
        match = text_match(self.sentences[self.index], text)
        if self.embed is not None:
            try:
                self.embeddings.append(np.asarray(self.embed(pcm), np.float32))
            except Exception:                           # noqa: BLE001 - a voiceprint is a bonus
                log.exception("speaker embedding failed")
        self._speech.append(loud)
        self.transcripts.append(text)
        self.matches.append(match)
        self.emit("heard", index=self.index, text=text, match=match, dbfs=round(level, 1))
        self.index += 1
        self.tries = 0
        if self.index < len(self.sentences):
            self._start_sentence()
            return
        speech = np.concatenate(self._speech) if self._speech else np.zeros(0)
        cal = calibrate(self._noise, speech, self._noise_probs or None)
        if self._noise and self._noise_voiced > 0.3 * len(self._noise):
            cal.warnings.append("Someone spoke during the quiet part: the noise floor may be too high; "
                                "calibrate again in a quiet moment.")
        if self.matches and min(self.matches) < 0.5:
            cal.warnings.append("Speech recognition misheard a sentence: check the microphone, or choose another "
                                "speech recognition model.")
        self.state = "done"
        self.emit("done", name=self.name, calibration=cal, embeddings=list(self.embeddings),
                  transcripts=list(self.transcripts), matches=list(self.matches))
