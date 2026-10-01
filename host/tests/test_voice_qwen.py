"""Marvin's own voice (Qwen3-TTS) without the models: fake mlx-audio and qwen-tts packages, the
voice's reference, the choice of backend, the cached clone prompt, streaming and the fallback.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import sys
import types

import numpy as np
import pytest

from marvin_host.voice import qwen_tts, tts as tts_mod
from marvin_host.voice.tts import FakeTTS, FallbackTTS


def test_the_voice_is_in_the_repository():
    wav, text = qwen_tts.reference("marvin")
    assert wav.exists() and wav.stat().st_size < 1_000_000
    assert text.startswith("Coucou, c'est moi, Marvin") and "Hey there, it's me, Marvin" in text
    from marvin_host.voice.io import read_wav
    pcm, rate = read_wav(wav)
    assert rate == 24000 and 8 < len(pcm) / rate < 15
    assert qwen_tts.voices() == ["marvin"]
    with pytest.raises(RuntimeError):
        qwen_tts.reference("nobody")


# ---------------------------------------------------------------- mlx-audio, faked

class FakeMlxModel:
    sample_rate = 24000

    def __init__(self):
        self.calls = []

    def generate(self, text, ref_audio=None, ref_text=None, lang_code="auto", stream=False, streaming_interval=2.0,
                 verbose=False, **kw):
        self.calls.append(dict(text=text, ref_text=ref_text, lang_code=lang_code, stream=stream,
                               ref=ref_audio is not None))
        n = int(0.05 * len(text) * self.sample_rate)
        audio = (np.sin(np.arange(n) / 9) * 0.3).astype(np.float32)
        pieces = np.array_split(audio, 3) if stream else [audio]
        for p in pieces:
            yield types.SimpleNamespace(audio=p)


@pytest.fixture
def mlx(monkeypatch):
    model = FakeMlxModel()
    pkg = types.ModuleType("mlx_audio")
    tts_utils = types.ModuleType("mlx_audio.tts.utils")
    tts_utils.load_model = lambda repo: model.__setattr__("repo", repo) or model
    utils = types.ModuleType("mlx_audio.utils")
    utils.load_audio = lambda path, sample_rate=24000: np.zeros(10, np.float32)
    for name, mod in {"mlx_audio": pkg, "mlx_audio.tts": types.ModuleType("mlx_audio.tts"),
                      "mlx_audio.tts.utils": tts_utils, "mlx_audio.utils": utils}.items():
        monkeypatch.setitem(sys.modules, name, mod)
    monkeypatch.setattr(qwen_tts, "qwen_available", lambda: "mlx")
    return model


def test_mlx_clones_the_voice_with_each_clauses_language(mlx):
    t = qwen_tts.QwenTTS()
    assert (t.backend, t.repo) == ("mlx", "mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16")
    assert t.multilingual and not t.respells and t.name == "qwen"
    pieces = list(t.stream("C'est une bonne idée,", "fr"))
    assert len(pieces) == 3 and all(rate == 24000 and pcm.dtype == np.int16 for pcm, rate in pieces)
    pcm, rate = t.synthesize("let me know when it's done.", "en")
    assert rate == 24000 and len(pcm) == int(0.05 * 27 * 24000)
    assert [(c["lang_code"], c["stream"], c["ref"]) for c in mlx.calls] == [("french", True, True), ("english", True, True)]
    assert all(c["ref_text"].startswith("Coucou, c'est moi, Marvin") for c in mlx.calls)


def test_the_bigger_model_on_request(mlx, monkeypatch):
    monkeypatch.setenv("MARVIN_QWEN_TTS_SIZE", "1.7b")
    assert qwen_tts.QwenTTS().repo == "mlx-community/Qwen3-TTS-12Hz-1.7B-Base-bf16"
    monkeypatch.setenv("MARVIN_QWEN_TTS_SIZE", "huge")
    assert qwen_tts.QwenTTS().repo.endswith("0.6B-Base-bf16")


def test_auto_prefers_marvins_voice_where_it_is_fast(mlx, monkeypatch):
    classic = FakeTTS()
    monkeypatch.setattr(tts_mod, "_classic", lambda kind, voice, language: classic)
    t = tts_mod.make_tts("auto", None, "fr")
    assert isinstance(t, FallbackTTS) and isinstance(t.primary, qwen_tts.QwenTTS) and t.fallback is classic
    assert t.multilingual and not t.respells
    assert tts_mod.make_tts("say", None, "fr") is classic                    # asked for by name: as asked
    assert isinstance(tts_mod.make_tts("qwen", "marvin", "fr").primary, qwen_tts.QwenTTS)


def test_auto_skips_a_slow_qwen(monkeypatch):
    classic = FakeTTS()
    monkeypatch.setattr(tts_mod, "_classic", lambda kind, voice, language: classic)
    monkeypatch.setattr(qwen_tts, "qwen_available", lambda: "torch")
    monkeypatch.setattr(qwen_tts, "fast_enough", lambda backend: False)          # PyTorch on a CPU
    assert tts_mod.make_tts("auto", None, "fr") is classic
    monkeypatch.setattr(qwen_tts, "qwen_available", lambda: None)
    assert tts_mod.make_tts("auto", None, "fr") is classic
    with pytest.raises(RuntimeError, match="not installed"):
        tts_mod.make_tts("qwen", None, "fr")


def test_a_qwen_that_fails_falls_back_before_its_first_sound():
    class Broken:
        multilingual, respells = True, False

        def stream(self, text, language="fr"):
            raise RuntimeError("Metal is unhappy")
            yield                                                           # noqa: unreachable: a generator

        def synthesize(self, text, language="fr"):
            raise RuntimeError("Metal is unhappy")

    t = FallbackTTS(Broken(), FakeTTS())
    assert t.multilingual and not t.respells
    pieces = list(t.stream("Bonjour Marvin.", "fr"))
    assert len(pieces) == 1 and t.fallback.said == [("Bonjour Marvin.", "fr")]
    assert not t.multilingual and t.respells                                # the fallback's own ways now


# ---------------------------------------------------------------- qwen-tts (PyTorch), faked

@pytest.fixture
def torch_backend(monkeypatch, tmp_path):
    saved = {}
    torch = types.ModuleType("torch")
    torch.__version__ = "2.14.1"
    torch.bfloat16, torch.float16 = "bf16", "f16"
    torch.cuda = types.SimpleNamespace(is_available=lambda: False)
    torch.backends = types.SimpleNamespace(mps=types.SimpleNamespace(is_available=lambda: True))

    def save(obj, path):
        saved[str(path)] = obj
        open(path, "wb").write(b"prompt")

    torch.save = save
    torch.load = lambda path, weights_only=False: saved[str(path)]

    class Model:
        made = []

        def __init__(self, repo, device_map, dtype):
            self.repo, self.device, self.dtype = repo, device_map, dtype
            self.prompts, self.calls = 0, []

        @classmethod
        def from_pretrained(cls, repo, device_map="cpu", dtype=None):
            m = cls(repo, device_map, dtype)
            cls.made.append(m)
            return m

        def create_voice_clone_prompt(self, ref_audio, ref_text):
            self.prompts += 1
            return {"ref": ref_audio, "text": ref_text}

        def generate_voice_clone(self, text, language, voice_clone_prompt):
            self.calls.append((text, language, voice_clone_prompt["text"][:6]))
            return [np.zeros(2400, np.float32) + 0.1], 24000

    qt = types.ModuleType("qwen_tts")
    qt.Qwen3TTSModel = Model
    monkeypatch.setitem(sys.modules, "torch", torch)
    monkeypatch.setitem(sys.modules, "qwen_tts", qt)
    monkeypatch.setattr(qwen_tts, "qwen_available", lambda: "torch")
    monkeypatch.setenv("MARVIN_VOICES_DIR", str(tmp_path))
    import importlib.metadata
    monkeypatch.setattr(importlib.metadata, "version", lambda name: "0.1.1")
    return Model, tmp_path


def test_torch_caches_the_clone_prompt(torch_backend):
    Model, folder = torch_backend
    t = qwen_tts.QwenTTS()
    m = Model.made[-1]
    assert (m.repo, m.device, m.dtype) == ("Qwen/Qwen3-TTS-12Hz-0.6B-Base", "mps", "f16")
    pcm, rate = t.synthesize("Bonjour.", "fr")
    assert rate == 24000 and pcm.dtype == np.int16 and m.calls == [("Bonjour.", "french", "Coucou")]
    cached = list(folder.glob("*.pt"))
    assert [p.name for p in cached] == ["marvin-Qwen--Qwen3-TTS-12Hz-0.6B-Base-qwen-tts-0.1.1-torch-2.14.1.pt"]
    qwen_tts.QwenTTS()                                                       # the next start reads it
    assert Model.made[-1].prompts == 0 and m.prompts == 1
    assert qwen_tts.fast_enough("torch")


def test_the_sidecar_offers_marvins_voice(mlx, monkeypatch):
    pytest.importorskip("grpc")
    from marvin_host.sidecar.voice import server
    monkeypatch.setattr(qwen_tts, "weights_downloaded", lambda backend, model_size=None: True)
    o = server.options()
    assert any(b.name == "qwen" and b.installed for b in o.tts)
    assert [(v.id, v.engine, v.installed) for v in o.voices if v.engine == "qwen"] == [("marvin", "qwen", True)]
