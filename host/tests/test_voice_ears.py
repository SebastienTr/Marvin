"""The new ears: end of turn (Smart Turn), Silero VAD, Parakeet and its language, the vocabulary,
speaker recognition, the calibration and enrollment, and the models' files. Deterministic tests
with fakes; the real models are tested at the end when they are downloaded (skipped otherwise).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import hashlib
import io
import sys
import wave
from pathlib import Path

import numpy as np
import pytest

from conftest import real_models_dir
from marvin_host.audio import SAMPLE_RATE
from marvin_host.voice import enroll as enroll_mod
from marvin_host.voice import models
from marvin_host.voice.engine import VoiceConfig
from marvin_host.voice.enroll import Enrollment, calibrate, text_match
from marvin_host.voice.features import kaldi_fbank, whisper_log_mel
from marvin_host.voice.io import NullSink
from marvin_host.voice.lexicon import Lexicon, clean_terms, phonetic_key
from marvin_host.voice.speaker import MODEL_ID, FakeEncoder, SpeakerId, Voiceprint, cosine, unit
from marvin_host.voice.stt import FakeSTT, LexiconSTT, Transcript, with_vocabulary
from marvin_host.voice.text import detect_language
from marvin_host.voice.tts import FakeTTS
from marvin_host.voice.turn import FakeTurn, make_turn
from marvin_host.voice.vad import EnergyVad, make_vad
from voicekit import Replies, ScriptedVoice, ScriptSource, device, silence, voice

HOST = Path(__file__).resolve().parents[1]
REFERENCE = HOST / "marvin_host" / "voice" / "voices" / "marvin" / "reference.wav"


def engine(script, stt_script, replies=("D'accord.",), *, turn=None, speaker=None, **cfg):
    """A scripted voice with an end-of-turn model and a speaker encoder of the test's choosing."""
    config = VoiceConfig(**{"follow_up_s": 0.0, "speculative_stt": False, **cfg})
    src = ScriptSource(script)
    stt = FakeSTT(stt_script)
    va = ScriptedVoice(device(src, NullSink()), config, stt=stt, tts=FakeTTS(), vad=EnergyVad(), turn=turn,
                       speaker=speaker, replies=replies if isinstance(replies, Replies) else Replies(replies))
    src.assistant = va
    events = []
    va.add_listener(lambda kind, d: events.append((kind, d)))
    return va, stt, events


def heard(events):
    return [d for k, d in events if k == "heard"]


# ---------------------------------------------------------------- the end of a turn

def test_a_finished_turn_ends_after_a_short_silence():
    turn = FakeTurn(0.93, seconds=0.012)
    va, stt, events = engine([("quiet", 0.5), ("say", 1.2), ("quiet", 1.0), ("idle",)],
                             ["Marvin, quelle heure est-il ?"], turn=turn)
    va.run()
    assert va.asked == ["quelle heure est-il ?"]
    assert len(turn.calls) == 1 and 1.2 < turn.calls[0] < 1.8              # judged once, on the utterance so far
    lat = va.last_latency
    assert lat["endpoint"] == pytest.approx(0.2, abs=0.03)                 # not the 0.55 s silence
    assert lat["turn"] == 0.012 and lat["turn_p"] == 0.93


def test_an_unfinished_turn_waits_for_the_rest():
    # "Marvin, je voudrais... [0.9 s] un café": incomplete at the first pause, complete at the end
    turn = FakeTurn(lambda pcm: 0.1 if len(pcm) < 2.2 * SAMPLE_RATE else 0.9)
    va, stt, events = engine([("quiet", 0.5), ("say", 1.2), ("quiet", 0.9), ("say", 1.0), ("quiet", 1.0), ("idle",)],
                             ["Marvin, je voudrais un café."], turn=turn)
    va.run()
    assert va.asked == ["je voudrais un café."] and len(stt.calls) == 1 and stt.calls[0] > 2.5
    assert len(turn.calls) == 2
    assert va.last_latency["endpoint"] == pytest.approx(0.2, abs=0.03)


def test_an_unfinished_turn_ends_after_the_longest_wait():
    va, stt, events = engine([("quiet", 0.5), ("say", 1.2), ("quiet", 3.0), ("idle",)],
                             ["Marvin, je voudrais"], turn=FakeTurn(0.05), turn_max_s=2.0)
    va.run()
    assert va.asked == ["je voudrais"]
    assert va.last_latency["endpoint"] == pytest.approx(2.0, abs=0.03)


def test_after_a_wrong_finished_the_continuation_still_joins():
    # Smart Turn says "finished" too early; the person goes on before the answer is heard: one question
    va, stt, events = engine([("quiet", 0.5), ("say", 1.5), ("quiet", 0.6), ("say", 1.0), ("quiet", 1.0), ("idle",)],
                             ["Marvin, je suis développeur.", "Et j'aime la voile."],
                             Replies(["C'est noté.", "Développeur et marin."], delay_s=0.4), turn=FakeTurn(0.9))
    va.run()
    h = heard(events)
    assert [x["text"] for x in h] == ["je suis développeur.", "je suis développeur. Et j'aime la voile."]
    assert h[1]["continues"] == [h[0]["uid"]]


def test_without_smart_turn_the_silence_timers_stay():
    assert make_turn("silence") is None
    assert make_turn("auto") is None                                        # not downloaded (conftest)
    va, stt, events = engine([("quiet", 0.5), ("say", 1.2), ("quiet", 1.0), ("idle",)],
                             ["Marvin, quelle heure est-il ?"], turn=None)
    va.run()
    assert va.last_latency["endpoint"] == pytest.approx(0.55, abs=0.03) and "turn" not in va.last_latency


def test_make_vad_falls_back_without_silero():
    v = make_vad("auto")
    assert type(v).__name__ in ("WebRtcVad", "EnergyVad")
    with pytest.raises(FileNotFoundError):
        make_vad("silero")


# ---------------------------------------------------------------- features

def test_whisper_features_keep_the_end_and_pad_in_front():
    x = (np.sin(np.arange(16000) * 2 * np.pi * 440 / 16000) * 8000).astype(np.int16)
    f = whisper_log_mel(x)
    assert f.shape == (80, 800) and f.dtype == np.float32
    assert f[:, :650].std() < 1e-6 and f[:, -50:].std() > 0.01           # 7 s of padding, then the tone
    assert f.max() - f.min() <= 2.0 + 1e-5                                  # floored 8 decades below the peak, / 4
    long = np.concatenate([np.zeros(16000 * 10, np.int16), x])
    assert np.allclose(whisper_log_mel(long), f, atol=1e-5)                # the last 8 s only


def test_kaldi_fbank():
    x = (np.random.default_rng(0).normal(0, 3000, 16000)).astype(np.int16)
    f = kaldi_fbank(x)
    assert f.shape == (98, 80)                                             # 25 ms frames every 10 ms
    assert np.abs(f.mean(axis=0)).max() < 1e-4                             # mean-normalised
    assert kaldi_fbank(x[:100]).shape == (0, 80)


def test_kaldi_fbank_matches_kaldi():
    knf = pytest.importorskip("kaldi_native_fbank")
    x = (np.random.default_rng(1).normal(0, 3000, 8000)).astype(np.int16)
    opts = knf.FbankOptions()
    opts.frame_opts.dither = 0
    opts.frame_opts.window_type = "hamming"
    opts.mel_opts.num_bins = 80
    fb = knf.OnlineFbank(opts)
    fb.accept_waveform(16000, x.astype(np.float32).tolist())
    fb.input_finished()
    ref = np.array([fb.get_frame(i) for i in range(fb.num_frames_ready)])
    assert np.abs(ref - kaldi_fbank(x, cmn=False)).max() < 1e-3


# ---------------------------------------------------------------- the language of what Parakeet heard

@pytest.mark.parametrize("text, lang", [
    ("Coucou, c'est moi, Marvin, ton petit robot de bureau.", "fr"),
    ("Hey there, it's me, Marvin, your little desk robot.", "en"),
    ("Quelle heure est-il ?", "fr"),
    ("What time is it?", "en"),
    ("Mets le build sur la branche main.", "fr"),                          # anglicisms in a French sentence
    ("Marvin ?", None),
    ("OK.", None),
])
def test_detect_language(text, lang):
    assert detect_language(text)[0] == lang


def test_a_mixed_sentence_is_the_dominant_language_with_less_confidence():
    lang, share = detect_language("C'est une bonne idée, let me know when it's done.")
    assert lang in ("fr", "en") and 0.5 <= share < 0.8


class _FakeParakeetMlx:
    """parakeet_mlx's surface that ParakeetSTT uses."""

    def __init__(self, text="Bonjour Marvin, quelle heure est-il ?", confidence=0.9):
        self.text, self.confidence, self.loaded = text, confidence, []
        from types import SimpleNamespace
        self.audio = SimpleNamespace(get_logmel=lambda x, cfg: x)
        outer = self

        class Model:
            preprocessor_config = None

            def generate(self, mel):
                tok = SimpleNamespace(confidence=outer.confidence)
                return [SimpleNamespace(text=outer.text if len(mel) > 8000 else "",
                                        sentences=[SimpleNamespace(tokens=[tok, tok])])]

        self.from_pretrained = lambda path: outer.loaded.append(path) or Model()


def test_parakeet_backend_on_mlx(monkeypatch):
    from types import SimpleNamespace

    from marvin_host.voice import stt as stt_mod
    fake = _FakeParakeetMlx()
    monkeypatch.setitem(sys.modules, "parakeet_mlx", fake)
    monkeypatch.setitem(sys.modules, "parakeet_mlx.audio", fake.audio)
    core = SimpleNamespace(array=lambda a: a)
    monkeypatch.setitem(sys.modules, "mlx", SimpleNamespace(core=core))
    monkeypatch.setitem(sys.modules, "mlx.core", core)
    monkeypatch.setattr(stt_mod, "_local_snapshot", lambda repo, patterns=None: f"/cache/{repo}")
    monkeypatch.setattr(stt_mod, "parakeet_backend", lambda: "mlx")
    s = stt_mod.make_stt("auto")
    assert isinstance(s, stt_mod.ParakeetSTT) and fake.loaded == ["/cache/mlx-community/parakeet-tdt-0.6b-v3"]
    tr = s.transcribe(voice(1.5))
    assert tr.text == "Bonjour Marvin, quelle heure est-il ?" and tr.language == "fr" and tr.confident
    assert tr.confidence == pytest.approx(0.9) and tr.rejected is None
    fake.text = "What's the weather like today?"
    assert s.transcribe(voice(1.5)).language == "en"
    assert s.transcribe(voice(1.5), "fr").language == "fr"                 # a forced language stays
    fake.text = "Marvin ?"
    tr = s.transcribe(voice(1.5))
    assert tr.language is None and not tr.confident                        # the conversation's language stays
    fake.confidence = 0.05
    tr = s.transcribe(voice(1.5))
    assert tr.text == "" and tr.rejected.startswith("low confidence")
    # a Whisper model name chosen for Whisper is not Parakeet's
    assert stt_mod.make_stt("parakeet", "turbo").model_name == "mlx-community/parakeet-tdt-0.6b-v3"


def test_parakeet_failing_falls_back_to_whisper(monkeypatch):
    from marvin_host.voice import stt as stt_mod

    class CpuWhisper:
        def __init__(self, model, languages):
            self.model = model

    def broken(*a, **k):
        raise RuntimeError("no weights")

    monkeypatch.setattr(stt_mod, "WhisperSTT", CpuWhisper)
    monkeypatch.setattr(stt_mod, "mlx_available", lambda: False)
    monkeypatch.setattr(stt_mod, "parakeet_backend", lambda: "mlx")
    monkeypatch.setattr(stt_mod, "ParakeetSTT", broken)
    assert stt_mod.make_stt("auto").model == "small"
    with pytest.raises(RuntimeError):
        stt_mod.make_stt("parakeet")


# ---------------------------------------------------------------- the vocabulary

LEX = Lexicon(["Marvin", "Aashma", "Laurine", "Bosco"])


@pytest.mark.parametrize("heard, fixed", [
    ("Dis Marvine, quelle heure ?", "Dis Marvin, quelle heure ?"),
    ("Est-ce que Ashma est rentrée ?", "Est-ce que Aashma est rentrée ?"),
    ("Ash ma arrive ce soir.", "Aashma arrive ce soir."),
    ("Lorine est au port.", "Laurine est au port."),
    ("Bosko a planté.", "Bosco a planté."),
    ("Mar vin, tu m'entends ?", "Marvin, tu m'entends ?"),
    ("Le marin du matin et Martin.", "Le marin du matin et Martin."),      # common words stay
    ("I love marvels and marines.", "I love marvels and marines."),
])
def test_vocabulary_corrects_names(heard, fixed):
    assert LEX.correct(heard) == fixed


def test_phonetic_keys_and_terms():
    assert phonetic_key("Marvine") == phonetic_key("Marvin")
    assert phonetic_key("Ashma") == phonetic_key("Aashma")
    assert clean_terms([" Marvin ", "marvin", "", "Aashma"]) == ["Marvin", "Aashma"]
    assert not Lexicon([]) and Lexicon([]).correct("Marvine") == "Marvine"


def test_vocabulary_wraps_any_engine_and_biases_whisper():
    from marvin_host.voice import stt as stt_mod
    s = with_vocabulary(FakeSTT(["Ashma, c'est Marvine."]), ["Marvin", "Aashma"])
    assert isinstance(s, LexiconSTT) and s.transcribe(voice(1.0)).text == "Aashma, c'est Marvin."
    w = stt_mod.WhisperSTT.__new__(stt_mod.WhisperSTT)
    with_vocabulary(w, ["Marvin", "Laurine"])
    assert w.hotwords == "Marvin, Laurine"
    m = stt_mod.MlxWhisperSTT.__new__(stt_mod.MlxWhisperSTT)
    again = with_vocabulary(with_vocabulary(m, ["Marvin"]), ["Marvin", "Bosco"])      # re-applied, not stacked
    assert m.initial_prompt == "Marvin, Bosco." and again.inner is m


@pytest.mark.parametrize("heard, query", [
    ("Marvine, quelle heure est-il ?", "quelle heure est-il ?"),        # how Parakeet spells the French "Marvine"
    ("Marveen, what time is it?", "what time is it?"),
    ("Dis Marvigne, tu es là ?", "tu es là ?"),
    ("Hey Marvin.", ""),
])
def test_the_name_as_parakeet_writes_it(heard, query):
    from marvin_host.voice.wake import match_wake_word
    assert match_wake_word(heard) == query
    assert match_wake_word(LEX.correct(heard)) == query                    # and after the vocabulary


# ---------------------------------------------------------------- whose voice

def test_voiceprints_and_matching():
    enc = FakeEncoder()
    seb = Voiceprint.of("Sébastien", [enc.embed(voice(1.5, 140)), enc.embed(voice(2.0, 140))])
    assert seb.samples == 2 and abs(np.linalg.norm(seb.embedding) - 1) < 1e-5
    who = SpeakerId(enc, [seb, Voiceprint.of("Aashma", [enc.embed(voice(1.5, 230))])], threshold=0.45)
    m = who.identify(voice(1.5, 140))
    assert m.name == "Sébastien" and m.score > 0.9 and m.label() == "Sébastien"
    m = who.identify(voice(1.5, 190))
    assert m.name is None and m.label() == "unknown" and m.score < 0.45
    assert who.identify(voice(0.5, 140)).label() == ""                     # too short to tell
    assert SpeakerId(enc, []).identify(voice(1.5)).label() == ""            # nobody enrolled
    other_model = Voiceprint("Old", unit(np.ones(16)), model="another-model")
    assert SpeakerId(enc, [other_model]).voiceprints == []
    assert cosine([1, 0], [0, 1]) == 0.0


def _print(name, f0):
    e = FakeEncoder().embed(voice(1.5, f0))
    return (name, tuple(float(x) for x in e), MODEL_ID)


def test_the_core_is_told_who_is_speaking():
    va, stt, events = engine([("quiet", 0.5), ("say", 1.5, 140), ("quiet", 1.0), ("idle",),
                              ("say", 1.5, 230), ("quiet", 1.0), ("idle",)],
                             ["Marvin, rappelle-moi d'appeler Laurine.", "Marvin, quelle heure est-il ?"],
                             ["D'accord.", "Midi."], speaker=FakeEncoder(), voiceprints=(_print("Sébastien", 140),))
    va.run()
    h = heard(events)
    assert h[0]["speaker"] == "Sébastien" and h[0]["speaker_score"] > 0.9
    assert h[1]["speaker"] == "unknown" and h[1]["speaker_score"] < 0.45
    assert len(va.asked) == 2                                              # unknown voices are answered by default


def test_only_known_voices():
    va, stt, events = engine([("quiet", 0.5), ("say", 1.5, 230), ("quiet", 1.0), ("idle",),
                              ("say", 1.5, 140), ("quiet", 1.0), ("idle",)],
                             ["Marvin, quelle heure est-il ?", "Marvin, quelle heure est-il ?"], ["Midi."],
                             speaker=FakeEncoder(), voiceprints=(_print("Sébastien", 140),), known_voices_only=True)
    va.run()
    assert va.asked == ["quelle heure est-il ?"] and heard(events)[0]["speaker"] == "Sébastien"
    ignored = [d for k, d in events if k == "ignored"]
    assert ignored and ignored[0]["reason"].startswith("unknown voice")


def test_nobody_enrolled_costs_nothing():
    enc = FakeEncoder()
    va, stt, events = engine([("quiet", 0.5), ("say", 1.5), ("quiet", 1.0), ("idle",)],
                             ["Marvin, quelle heure est-il ?"], speaker=enc, known_voices_only=True)
    va.run()
    assert va.asked == ["quelle heure est-il ?"] and enc.calls == 0 and "speaker" not in heard(events)[0]


# ---------------------------------------------------------------- calibration and enrollment

def _levels(db, n, spread=1.0, seed=0):
    return np.random.default_rng(seed).normal(db, spread, n)


def test_calibration_thresholds_from_noise_and_speech():
    quiet = calibrate(_levels(-70, 75), _levels(-28, 200, 4))
    assert quiet.noise_dbfs == pytest.approx(-68.7, abs=0.5) and quiet.speech_dbfs == pytest.approx(-28, abs=1)
    assert quiet.vad_min_dbfs == pytest.approx(-62.7, abs=0.6)             # 6 dB above the noise
    assert quiet.speech_min_dbfs == pytest.approx(-46, abs=1)              # 18 dB under the voice
    assert quiet.vad_threshold == 0.5 and quiet.warnings == []
    noisy = calibrate(_levels(-42, 75), _levels(-30, 200, 4), noise_probs=_levels(0.62, 75, 0.05))
    assert noisy.vad_min_dbfs == pytest.approx(-55, abs=1)                 # 25 dB under the voice wins
    assert noisy.speech_min_dbfs == pytest.approx(-36, abs=1)              # never closer than 6 dB to the voice
    assert 0.75 <= noisy.vad_threshold <= 0.85                             # the noise sounds like speech
    assert len(noisy.warnings) == 2
    loud = calibrate(_levels(-60, 75), _levels(-3, 200, 1))
    assert any("clips" in w for w in loud.warnings)
    faint = calibrate(_levels(-80, 75), _levels(-50, 200, 1))
    assert any("quiet" in w for w in faint.warnings)
    assert -70 <= faint.speech_min_dbfs <= faint.speech_dbfs - 6


def test_text_match():
    assert text_match("Bonjour Marvin, je m'appelle Sébastien.", "bonjour marvin je m'appelle sebastien") == 1.0
    assert 0.6 < text_match("Hey Marvin, what's the weather like today?", "Hey Marvin, what is the weather today") < 1
    assert text_match("Bonjour", "") == 0.0


SENTENCES = ["Bonjour Marvin, je m'appelle {name}.", "Hey Marvin, what's the weather like today?"]


def test_enrollment_through_the_engine():
    va, stt, events = engine([("quiet", 1.2), ("say", 1.6), ("quiet", 1.2), ("say", 1.8), ("quiet", 1.2)],
                             ["Bonjour Marvin, je m'appelle Sébastien.", "Hey Marvin, what is the weather like today?"],
                             speaker=FakeEncoder())
    assert va.enroll("Sébastien", SENTENCES, noise_s=1.0)
    va.run()
    enr = [d for k, d in events if k == "enroll"]
    assert [d["state"] for d in enr] == ["noise", "sentence", "heard", "sentence", "heard", "done"]
    assert enr[1]["text"] == "Bonjour Marvin, je m'appelle Sébastien." and enr[3]["index"] == 1
    assert enr[2]["match"] == 1.0 and enr[4]["match"] > 0.7
    done = enr[-1]
    cal = done["calibration"]
    assert -64 < cal.noise_dbfs < -55 and -26 < cal.speech_dbfs < -14     # voicekit: room noise, a loud voice
    assert cal.vad_min_dbfs < cal.speech_min_dbfs < cal.speech_dbfs
    assert done["name"] == "Sébastien" and len(done["embeddings"]) == 2 and done["device"].kind == "computer"
    assert va.asked == [] and not va.enrolling                             # nothing was answered meanwhile


def test_enrollment_asks_again_then_gives_up():
    got = []
    enr = Enrollment("Ana", ["Bonjour Marvin."], vad=EnergyVad(margin_db=10, min_dbfs=-90),
                     transcribe=lambda pcm: Transcript(""), embed=None, emit=lambda s, **d: got.append((s, d)),
                     noise_s=0.5, timeout_s=3.0)
    audio = np.concatenate([silence(0.6)] + [np.concatenate([voice(1.2), silence(1.0)]) for _ in range(3)])
    for i in range(len(audio) // 320):
        enr.feed(audio[i * 320:(i + 1) * 320])
    states = [s for s, _ in got]
    assert states == ["noise", "sentence", "retry", "sentence", "retry", "sentence", "failed"]
    assert "could not make out" in got[-1][1]["message"]


def test_enrollment_times_out_and_can_be_cancelled():
    got = []
    enr = Enrollment("", [], vad=EnergyVad(), transcribe=lambda pcm: "x", embed=None,
                     emit=lambda s, **d: got.append(s), noise_s=0.2, timeout_s=1.0)
    assert len(enr.sentences) == len(enroll_mod.SENTENCES)
    for _ in range(80):
        enr.feed(silence(0.02))
    assert got == ["noise", "sentence", "failed"] and not enr.active
    enr = Enrollment("A", ["One."], vad=EnergyVad(), transcribe=lambda pcm: "x", embed=None,
                     emit=lambda s, **d: got.append(s))
    enr.cancel()
    assert got[-1] == "cancelled" and not enr.active


# ---------------------------------------------------------------- the models' files

class _Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()


def test_model_download_checks_the_file(tmp_path, monkeypatch):
    monkeypatch.setenv("MARVIN_MODELS_DIR", str(tmp_path))
    data = b"onnx" * 1000
    m = models.ModelFile("t", "Test", "https://example.invalid/t.onnx", "t.onnx", hashlib.sha256(data).hexdigest(),
                         len(data), "MIT", "here")
    assert not m.present()
    out = io.StringIO()
    assert models.download(m, out=out, opener=lambda url: _Response(data)) == tmp_path / "t.onnx"
    assert m.present() and "Test: 0.0 / 0.0 MB" in out.getvalue()
    bad = models.ModelFile("u", "Bad", "https://example.invalid/u.onnx", "u.onnx", "0" * 64, len(data), "MIT", "here")
    with pytest.raises(OSError, match="checksum"):
        models.download(bad, out=None, opener=lambda url: _Response(data))
    assert not (tmp_path / "u.onnx").exists() and not (tmp_path / "u.onnx.part").exists()


def test_models_check_lines(tmp_path, monkeypatch):
    monkeypatch.setenv("MARVIN_MODELS_DIR", str(tmp_path))
    lines, ok = models.check_lines()
    assert not ok and any("Smart Turn" in line and "not downloaded" in line for line in lines)
    assert models.path_of("smart-turn") is None
    assert models.main(["--check"]) == 3


# ---------------------------------------------------------------- the real models (when downloaded)

def _real(model_id):
    pytest.importorskip("onnxruntime")
    m = models.MODELS[model_id]
    path = real_models_dir() / m.filename
    if not (path.is_file() and path.stat().st_size == m.size):
        pytest.skip(f"{m.label} is not downloaded (python -m marvin_host.voice.models --prefetch)")
    return path


def _reference():
    from scipy.signal import resample_poly
    with wave.open(str(REFERENCE)) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), np.int16)
        rate = w.getframerate()
    return resample_poly(x.astype(np.float32), SAMPLE_RATE, rate).astype(np.int16)


def test_real_silero_vad():
    from marvin_host.voice.vad import SileroVad
    v = SileroVad(path=_real("silero-vad"))
    ref = _reference()
    decisions = [v.is_speech(ref[i:i + 320]) for i in range(0, len(ref) - 320, 320)]
    assert 0.5 < np.mean(decisions) < 0.9                                  # Marvin's reference: speech with pauses
    v.reset()
    noise = (np.random.default_rng(0).normal(0, 300, 3 * SAMPLE_RATE)).astype(np.int16)
    assert not any(v.is_speech(noise[i:i + 320]) for i in range(0, len(noise) - 320, 320))


def test_real_smart_turn():
    from marvin_host.voice.turn import SmartTurn
    st = SmartTurn(path=_real("smart-turn"))
    ref = _reference()
    tail = np.zeros(int(0.15 * SAMPLE_RATE), np.int16)
    # "Coucou, c'est moi, Marvin, ton petit robot de bureau. On va bien s'amuser aujourd'hui." ends at 3.88 s
    done = st.decide(np.concatenate([ref[:int(3.88 * SAMPLE_RATE)], tail]))
    assert done.complete and done.probability > 0.8
    cut = st.decide(np.concatenate([ref[:int(1.28 * SAMPLE_RATE)], tail]))  # "Coucou, c'est moi,"
    assert not cut.complete and cut.probability < 0.2
    assert done.seconds < 1.0                                              # tens of ms on a laptop


def test_real_speaker_model():
    from marvin_host.voice.speaker import WeSpeakerEncoder
    from scipy.signal import resample_poly
    enc = WeSpeakerEncoder(path=_real("speaker"))
    ref = _reference()
    fr, en = enc.embed(ref[:int(3.9 * SAMPLE_RATE)]), enc.embed(ref[int(4.9 * SAMPLE_RATE):])
    assert abs(np.linalg.norm(fr) - 1) < 1e-4
    same = cosine(fr, en)                                                  # one voice, two languages
    # the same recording 25 % higher and faster: another voice as far as the model is concerned
    other = enc.embed(resample_poly(ref[int(4.9 * SAMPLE_RATE):].astype(np.float32), 4, 5).astype(np.int16))
    assert same > 0.6 and cosine(fr, other) < same - 0.2


def test_real_parakeet_cpu():
    folder = __import__("os").environ.get("MARVIN_TEST_PARAKEET_ONNX")
    if not folder:
        pytest.skip("set MARVIN_TEST_PARAKEET_ONNX to a folder of istupakov/parakeet-tdt-0.6b-v3-onnx (int8)")
    pytest.importorskip("onnx_asr")
    from marvin_host.voice.stt import ParakeetSTT
    s = with_vocabulary(ParakeetSTT(folder, backend="onnx"), ["Marvin"])
    ref = _reference()
    fr = s.transcribe(ref[:int(3.9 * SAMPLE_RATE)])
    assert fr.text == "Coucou, c'est moi, Marvin, ton petit robot de bureau." and fr.language == "fr"
    en = s.transcribe(ref[int(5.0 * SAMPLE_RATE):])
    assert "Hey there, it's me, Marvin, your little desk robot." in en.text and en.language == "en"
