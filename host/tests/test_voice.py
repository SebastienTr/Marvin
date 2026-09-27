"""The voice pipeline without microphone, speakers, models or network: fake STT, LLM and TTS,
synthetic audio, and a fake Ollama server on localhost. One optional test runs Whisper tiny.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

import numpy as np
import pytest

from marvin_host.audio import FRAME_SAMPLES, SAMPLE_RATE, AudioSink, AudioSource
from marvin_host.events import Event, EventKind, PresenceState
from marvin_host.voice import persona
from marvin_host.voice.assistant import VoiceAssistant, VoiceConfig
from marvin_host.voice.io import ArraySource, NullSink, StreamResampler, WavSink, read_wav, resample, write_wav
from marvin_host.voice.llm import FakeLLM, LLMUnavailable, OllamaLLM
from marvin_host.voice.proactive import ProactiveConfig, ProactiveSpeaker
from marvin_host.voice.stt import FakeSTT, Transcript
from marvin_host.voice.text import SentenceSplitter, clean_for_speech, split_sentences
from marvin_host.voice.tts import FakeTTS
from marvin_host.voice.vad import EnergyVad, Segmenter, SegmenterConfig
from marvin_host.voice.wake import TranscriptWakeWord, is_wake_word, match_wake_word


# ---------------------------------------------------------------- synthetic audio

def silence(s: float) -> np.ndarray:
    rng = np.random.default_rng(1)
    return (rng.normal(0, 30, int(s * SAMPLE_RATE))).astype(np.int16)        # quiet room noise


def voice(s: float, f0: float = 140.0) -> np.ndarray:
    """A buzzy harmonic tone with a syllable-like envelope: loud enough to be speech for the VAD."""
    t = np.arange(int(s * SAMPLE_RATE)) / SAMPLE_RATE
    x = sum(np.sin(2 * np.pi * f0 * k * t) / k for k in range(1, 8))
    env = 0.6 + 0.4 * np.abs(np.sin(2 * np.pi * 3 * t))
    return (x * env * 5000).astype(np.int16)


# ---------------------------------------------------------------- wake word

@pytest.mark.parametrize("text, query", [
    ("Marvin, quelle heure est-il ?", "quelle heure est-il ?"),
    ("Marvin.", ""),
    ("Marvin ?", ""),
    ("marvine", ""),
    ("Marvain, tu es là ?", "tu es là ?"),
    ("Marven what's the weather", "what's the weather"),
    ("Dis Marvin, tu vas bien ?", "tu vas bien ?"),
    ("Hey Marvin, what time is it?", "what time is it?"),
    ("OK Mar vin, stop", "stop"),
    ("Quelle heure est-il, Marvin ?", "Quelle heure est-il"),
    ("MARVIN ! Écoute.", "Écoute."),
])
def test_wake_word_matches(text, query):
    assert match_wake_word(text) == query


@pytest.mark.parametrize("text", [
    "", "Bonjour tout le monde", "J'ai parlé de Marvin à Paul hier soir",
    "Martin, tu viens ?", "Le marin est rentré", "Marvel a sorti un film", "Mardi prochain",
])
def test_wake_word_rejects(text):
    assert match_wake_word(text) is None


def test_fuzzy_single_words():
    for w in ("marvin", "marvine", "marven", "marvain", "mervin", "marvyn", "marvinn"):
        assert is_wake_word(w), w
    for w in ("marvel", "martin", "marin", "marv", "marvelousness", "melvin", "garvin"):
        assert not is_wake_word(w), w


def test_transcript_wake_word_prefilter():
    stt = FakeSTT(["Marvin, bonjour"])
    ww = TranscriptWakeWord(stt)
    assert ww.check(silence(1.0)) is None               # too quiet: Whisper not run
    assert ww.check(voice(0.1)) is None                 # too short
    assert ww.check(voice(20.0)) is None                # too long
    assert stt.calls == []
    m = ww.check(voice(1.0))
    assert m is not None and m.query == "bonjour" and len(stt.calls) == 1


# ---------------------------------------------------------------- VAD and segments

def test_segmenter_splits_utterances():
    audio = np.concatenate([silence(1.0), voice(0.8), silence(1.0), voice(0.05), silence(1.0),
                            voice(1.5), silence(1.2)])
    seg = Segmenter(EnergyVad())
    out = []
    for i in range(len(audio) // FRAME_SAMPLES):
        s = seg.feed(audio[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES])
        if s:
            out.append(s)
    assert len(out) == 2                                # the 50 ms click is dropped
    a, b = out
    assert a.t_start == pytest.approx(1.0 - 0.3, abs=0.1)       # pre-roll before the start
    assert a.duration == pytest.approx(0.3 + 0.8 + 0.2, abs=0.15)
    assert b.t_start == pytest.approx(3.85 - 0.3, abs=0.15)
    assert b.duration == pytest.approx(0.3 + 1.5 + 0.2, abs=0.15)


def test_segmenter_cuts_long_speech_and_flushes():
    seg = Segmenter(EnergyVad(), SegmenterConfig(max_segment_s=2.0))
    audio = np.concatenate([silence(0.5), voice(5.0)])
    out = [s for i in range(len(audio) // FRAME_SAMPLES)
           if (s := seg.feed(audio[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES]))]
    last = seg.flush()
    assert len(out) == 2 and all(s.duration == pytest.approx(2.0, abs=0.05) for s in out)
    assert last is not None and last.duration > 0.5


def test_webrtc_vad_on_silence():
    pytest.importorskip("webrtcvad")
    from marvin_host.voice.vad import WebRtcVad
    vad = WebRtcVad()
    assert not vad.is_speech(np.zeros(FRAME_SAMPLES, np.int16))
    assert isinstance(vad.is_speech(voice(0.02)), bool)


# ---------------------------------------------------------------- audio I/O

def test_resample_keeps_pitch_and_length():
    t = np.arange(48000) / 48000
    x = (np.sin(2 * np.pi * 440 * t) * 10000).astype(np.int16)
    y = resample(x, 48000, 16000)
    assert len(y) == 16000
    f = np.argmax(np.abs(np.fft.rfft(y))) * 16000 / len(y)
    assert f == pytest.approx(440, abs=2)
    # streaming, in odd-sized chunks, gives the same signal
    rs = StreamResampler(48000, 16000)
    z = np.concatenate([rs.process(x[i:i + 777]) for i in range(0, len(x), 777)])
    n = min(len(z), len(y)) - 100
    lag = 31 // 3 + 10                     # the streaming FIR is causal: a few samples of delay
    corr = [np.corrcoef(z[k:k + n - lag], y[:n - lag])[0, 1] for k in range(lag)]
    assert max(corr) > 0.99


def test_sources_and_sinks_follow_the_contract(tmp_path):
    path = tmp_path / "a.wav"
    write_wav(path, voice(0.5), SAMPLE_RATE)
    from marvin_host.voice.io import WavSource
    src = WavSource(path, tail_s=0.1)
    assert isinstance(src, AudioSource)
    frames = list(src.frames())
    assert all(len(f) == FRAME_SAMPLES and f.dtype == np.int16 for f in frames)
    assert len(frames) == 30
    sink = WavSink(tmp_path / "out.wav")
    assert isinstance(sink, AudioSink)
    sink.play(resample(voice(0.5), SAMPLE_RATE, 22050), 22050)    # 0.5 s at 22.05 kHz, resampled to 16 kHz
    sink.wait()
    sink.close()
    pcm, rate = read_wav(tmp_path / "out.wav")
    assert rate == SAMPLE_RATE and len(pcm) == pytest.approx(8000, abs=2)


def test_null_sink_realtime_busy_and_stop():
    sink = NullSink(realtime=True)
    sink.play(np.zeros(16000, np.int16))
    assert sink.busy
    threading.Timer(0.05, sink.stop).start()
    t = time.monotonic()
    sink.wait()
    assert time.monotonic() - t < 0.5 and not sink.busy


# ---------------------------------------------------------------- text

def test_sentence_splitter_streaming():
    text = "Il est 14 h 30. M. Dupont a appelé à 9.5 heures ! Oui. C'est ça. Tu veux le rappeler ?"
    sp = SentenceSplitter()
    out = []
    for ch in text:                                      # one character at a time, like a stream
        out += sp.feed(ch)
    first_before_end = list(out)
    out += sp.flush()
    assert out == ["Il est 14 h 30.", "M. Dupont a appelé à 9.5 heures !", "Oui. C'est ça.",
                   "Tu veux le rappeler ?"]
    assert first_before_end[0] == "Il est 14 h 30."     # available before the stream ended


def test_split_sentences_newlines_and_clean():
    assert split_sentences("Un\nDeux. Trois") == ["Un", "Deux.", "Trois"]
    text = "**Bonjour** ! 😀 Voir [ici](http://x.y) :\n- un\n- deux"
    assert clean_for_speech(text) == "Bonjour ! Voir ici :\nun\ndeux"


# ---------------------------------------------------------------- persona and context

def test_context_from_presence_state():
    s = PresenceState(t_us=int(3600e6), present=True, seated=True, distance_m=0.8, seated_s=42 * 60,
                      breath_rate=14.2, heart_rate=None)
    events = [Event(EventKind.ARRIVED, int(3600e6 - 50 * 60e6)), Event(EventKind.SAT_DOWN, int(3600e6 - 42 * 60e6)),
              Event(EventKind.VITALS_ACQUIRED, int(3600e6 - 60e6))]
    facts = persona.context_facts(s, events)
    text = " ".join(facts)
    assert "seated for 42 minutes" in text
    assert "breathing rate is 14 per minute" in text
    assert "heart" not in text                           # not reliable: not mentioned
    assert "sat down 42 minutes ago" in text and "arrived 50 minutes ago" in text
    prompt = persona.system_prompt("fr", s, events)
    assert "French" in prompt and "Marvin" in prompt and "0.8 metres" in prompt
    assert "markdown" in prompt


def test_context_when_nobody_is_there():
    facts = persona.context_facts(PresenceState(present=False, breath_rate=15.0))
    assert len(facts) == 1 and "nobody" in facts[0]
    assert persona.context_facts(None) == []
    assert "English" in persona.system_prompt("en")


# ---------------------------------------------------------------- Ollama client

class _FakeOllama(BaseHTTPRequestHandler):
    models = ["qwen3:4b-instruct"]
    requests: list[dict] = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/api/tags":
            self._json(200, {"models": [{"name": m} for m in self.models]})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        type(self).requests.append(body)
        if body["model"] not in self.models:
            return self._json(404, {"error": f"model '{body['model']}' not found"})
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.end_headers()
        for piece in ["Paris est ", "la capitale. ", "Voilà."]:
            line = {"message": {"role": "assistant", "content": piece}, "done": False}
            self.wfile.write((json.dumps(line) + "\n").encode())
            self.wfile.flush()
            time.sleep(0.01)
        self.wfile.write((json.dumps({"message": {"role": "assistant", "content": ""}, "done": True}) + "\n").encode())

    def _json(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@pytest.fixture
def ollama():
    _FakeOllama.requests = []
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _FakeOllama)
    th = threading.Thread(target=srv.serve_forever, daemon=True)
    th.start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def test_ollama_streams_chat(ollama):
    llm = OllamaLLM(host=ollama, think=False)
    assert llm.available()
    pieces = list(llm.stream_chat([{"role": "user", "content": "Capitale ?"}]))
    assert pieces == ["Paris est ", "la capitale. ", "Voilà."]
    req = _FakeOllama.requests[-1]
    assert req["stream"] is True and req["model"] == "qwen3:4b-instruct" and req["think"] is False
    assert req["messages"][0]["content"] == "Capitale ?" and "num_predict" in req["options"]


def test_ollama_missing_model(ollama):
    llm = OllamaLLM("nope:1b", host=ollama)
    assert not llm.available()
    with pytest.raises(LLMUnavailable) as e:
        list(llm.stream_chat([{"role": "user", "content": "?"}]))
    assert "ollama pull nope:1b" in e.value.hint


def test_ollama_not_running():
    llm = OllamaLLM(host="http://127.0.0.1:9", timeout=2)
    assert not llm.available()
    with pytest.raises(LLMUnavailable) as e:
        list(llm.stream_chat([{"role": "user", "content": "?"}]))
    assert "ollama" in e.value.hint.lower()


# ---------------------------------------------------------------- the assistant, end to end

class ScriptSource:
    """Plays a script of ("say", seconds), ("quiet", seconds), ("idle",) steps. "idle" waits until the
    assistant has finished answering, like a person waiting for the reply."""

    def __init__(self, script):
        self.script = script
        self.assistant = None
        self._closed = False

    def frames(self):
        for step in self.script:
            if self._closed:
                return
            if step[0] == "idle":
                assert self.assistant.wait_idle(timeout=5)
                continue
            if step[0] == "call":
                assert self.assistant.wait_idle(timeout=0.3) or True
                step[1]()
                continue
            if step[0] == "status":
                end = time.monotonic() + 5
                while self.assistant.status != step[1]:
                    assert time.monotonic() < end, f"never reached {step[1]}"
                    time.sleep(0.005)
                continue
            audio = voice(step[1]) if step[0] == "say" else silence(step[1])
            for i in range(len(audio) // FRAME_SAMPLES):
                yield audio[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES]

    def close(self):
        self._closed = True


def said(message: dict) -> str:
    """What the person said, from a user message sent to the model (after the context block)."""
    return message["content"].split("The person says: ", 1)[-1] if message["role"] == "user" else message["content"]


def make(script, stt_script, replies=("D'accord.",), sink=None, brain=None, **cfg):
    config = VoiceConfig(**{"follow_up_s": 0.0, **cfg})
    src = ScriptSource(script)
    stt = FakeSTT(stt_script)
    llm = FakeLLM(replies) if not isinstance(replies, FakeLLM) else replies
    tts = FakeTTS()
    sink = sink or NullSink()
    log = {"status": [], "transcript": [], "reply": []}
    va = VoiceAssistant(src, sink, brain=brain, config=config, stt=stt, llm=llm, tts=tts, vad=EnergyVad(),
                        on_status=log["status"].append, on_transcript=log["transcript"].append,
                        on_reply=log["reply"].append)
    src.assistant = va
    return va, SimpleNamespace(stt=stt, llm=llm, tts=tts, sink=sink, log=log)


def test_question_in_one_breath():
    va, t = make([("quiet", 1), ("say", 1.5), ("quiet", 1), ("idle",)],
                 ["Marvin, quelle heure est-il ?"], ["Il est midi. Bon appétit, si c'est l'heure."])
    va.run()
    assert t.log["transcript"] == ["quelle heure est-il ?"]
    msgs = t.llm.calls[0]
    assert msgs[0]["role"] == "system" and "French" in msgs[0]["content"]
    assert msgs[-1]["role"] == "user" and said(msgs[-1]) == "quelle heure est-il ?"
    assert "Context:" in msgs[-1]["content"] and "Context" not in msgs[0]["content"]   # cacheable system prompt
    assert [s for s, _ in t.tts.said] == ["Il est midi.", "Bon appétit, si c'est l'heure."]
    assert t.log["reply"] == ["Il est midi. Bon appétit, si c'est l'heure."]
    assert t.log["status"] == ["thinking", "speaking", "idle"]
    assert len(t.sink.played) == 2
    assert va.history[-1]["content"].startswith("Il est midi")
    assert {"endpoint", "stt", "llm_first_token", "first_chunk", "tts", "audio_start", "total"} <= set(va.last_latency)


def test_not_addressed_is_ignored():
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",)], ["Il fait beau aujourd'hui."])
    va.run()
    assert len(t.stt.calls) == 1 and t.llm.calls == [] and t.log["status"] == []


def test_name_then_question():
    va, t = make([("say", 0.6), ("quiet", 1.5), ("say", 1.2), ("quiet", 1), ("idle",)],
                 ["Marvin.", "Tu peux me rappeler de boire de l'eau ?"], ["Je n'ai pas de rappels, hélas."])
    va.run()
    assert t.log["status"][:2] == ["listening", "thinking"]
    assert said(t.llm.calls[0][-1]) == "Tu peux me rappeler de boire de l'eau ?"
    assert len(t.sink.played) == 3                       # the chime, then the answer in two chunks
    assert [s for s, _ in t.tts.said] == ["Je n'ai pas de rappels,", "hélas."]


def test_listening_window_expires():
    va, t = make([("say", 0.6), ("quiet", 7.5), ("say", 1.2), ("quiet", 1), ("idle",)],
                 ["Marvin.", "Tu peux me rappeler de boire de l'eau ?"])
    va.run()
    assert t.log["status"] == ["listening", "idle"]
    assert t.llm.calls == []


def test_follow_up_without_the_name():
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",), ("quiet", 1), ("say", 1.0), ("quiet", 1), ("idle",),
                  ("quiet", 6), ("say", 1.0), ("quiet", 1), ("idle",)],
                 ["Marvin, c'est quoi la capitale du Pérou ?", "Et du Chili ?", "Et de l'Argentine ?"],
                 ["Lima.", "Santiago."], follow_up_s=4.0)
    va.run()
    assert len(t.llm.calls) == 2                         # the third one came after the window
    second = t.llm.calls[1]
    assert [said(m) for m in second[1:]] == ["c'est quoi la capitale du Pérou ?", "Lima.", "Et du Chili ?"]


def test_no_wake_answers_everything_and_follows_language():
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",), ("quiet", 1), ("say", 1.0), ("quiet", 1), ("idle",)],
                 [Transcript("What's the time?", "en", True), Transcript("Merci.", "fr", False)],
                 ["It's noon.", "Of course."], wake=False)
    va.run()
    assert [said(c[-1]) for c in t.llm.calls] == ["What's the time?", "Merci."]
    assert "English" in t.llm.calls[0][0]["content"]
    assert "English" in t.llm.calls[1][0]["content"]     # unconfident detection: keep the conversation's language
    assert [lang for _, lang in t.tts.said] == ["en", "en"]


def test_llm_down_is_spoken():
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",)], ["Marvin, ça va ?"], FakeLLM(fail=True))
    va.run()
    assert t.tts.said == [(persona.phrase("llm_down", "fr"), "fr")]
    assert va.history == [] and t.log["reply"] == []


def test_brain_context_reaches_the_model():
    brain = SimpleNamespace(state=PresenceState(t_us=int(100e6), present=True, seated=True, seated_s=3600,
                                                heart_rate=62.0, distance_m=0.9),
                            events=[Event(EventKind.SAT_DOWN, int(40e6))])
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",)], ["Marvin, depuis combien de temps je suis assis ?"],
                 ["Une heure."], brain=brain)
    va.run()
    system = t.llm.calls[0][-1]["content"]
    assert "seated for 60 minutes" in system and "heart rate is 62" in system


class BlockingSink(NullSink):
    """A speaker that never finishes on its own: it is only ever stopped."""

    def __init__(self):
        super().__init__()
        self._stop = threading.Event()

    def play(self, pcm, rate=SAMPLE_RATE):
        super().play(pcm, rate)
        self._stop.clear()

    def wait(self):
        self._stop.wait(5)

    def stop(self):
        super().stop()
        self._stop.set()

    @property
    def busy(self):
        return not self._stop.is_set()


def test_barge_in_in_duplex_mode():
    reply = "Il était une fois un phare au bout du monde. Son gardien parlait peu."
    va, t = make([("say", 1.0), ("quiet", 1), ("status", "speaking"),
                  ("say", 1.0), ("quiet", 1),            # its own voice coming back: ignored
                  ("say", 0.6), ("quiet", 1), ("idle",)],    # "Marvin." from the user: stops it
                 ["Marvin, raconte-moi une histoire.", "Il était une fois un phare", "Marvin !"],
                 [reply], sink=BlockingSink(), duplex=True)
    va.run()
    assert len(t.llm.calls) == 1
    assert t.sink.stops >= 1
    assert t.log["reply"] == []                          # interrupted: no complete reply
    assert "listening" in t.log["status"][t.log["status"].index("speaking"):]
    assert va.history[-1]["content"].endswith("…")       # remembered as cut short


def test_no_barge_in_while_it_says_its_own_name():
    reply = "Je suis Marvin, votre robot de bureau. Je vais vous raconter une longue histoire."
    sink = BlockingSink()
    va, t = make([("say", 1.0), ("quiet", 1), ("status", "speaking"),
                  ("say", 1.0), ("quiet", 1),            # "Marvin, votre robot de bureau": its own voice
                  ("call", lambda: stops.append(sink.stops)), ("call", sink.stop), ("idle",)],
                 ["Marvin, présente-toi.", "Marvin, votre robot de bureau."], [reply], sink=sink, duplex=True)
    stops = []
    va.run()
    assert stops == [0] and len(t.llm.calls) == 1
    assert len(t.stt.calls) == 1                         # not even transcribed: the reply contains the name


# ---------------------------------------------------------------- it must never answer itself

class LoopSink(NullSink):
    """A loudspeaker next to the microphone: what is played comes back through `LoopSource`,
    `delay_s` later (output latency and room echo). `wait()` returns when the speaker has played
    everything, like a real one: the echo is still in the air."""

    def __init__(self):
        super().__init__()
        self.buf = np.zeros(0, np.int16)
        self.lock = threading.Lock()
        self.drained = threading.Event()
        self.drained.set()

    def play(self, pcm, rate=SAMPLE_RATE):
        super().play(pcm, rate)
        with self.lock:
            self.buf = np.concatenate([self.buf, resample(pcm, rate)])
            self.drained.clear()

    def take(self, n):
        with self.lock:
            out, self.buf = self.buf[:n], self.buf[n:]
            if len(self.buf) == 0:
                self.drained.set()
        return np.pad(out, (0, n - len(out)))

    def wait(self):
        self.drained.wait(10)

    def stop(self):
        super().stop()
        with self.lock:
            self.buf = np.zeros(0, np.int16)
            self.drained.set()

    @property
    def busy(self):
        return not self.drained.is_set()


class LoopSource:
    """The room: the user's voice (script) plus the speaker's sound, delayed. Paced a little so the
    assistant's threads keep up (20 ms of audio every 2 ms)."""

    def __init__(self, script, sink: LoopSink, delay_s: float = 0.3):
        self.script, self.sink = script, sink
        self.delay = [np.zeros(FRAME_SAMPLES, np.int16)] * int(delay_s * 1000 / 20)
        self.assistant = None
        self._closed = False

    def _room(self):
        self.delay.append(self.sink.take(FRAME_SAMPLES))
        return self.delay.pop(0)

    def frames(self):
        for step in self.script:
            if step[0] == "until_quiet":            # until it has finished and the echo has died out
                while (self.assistant._pending or self.sink.busy or any(np.any(f) for f in self.delay)) \
                        and not self._closed:
                    time.sleep(0.002)
                    yield self._room()
                continue
            user = voice(step[1]) if step[0] == "say" else np.zeros(int(step[1] * SAMPLE_RATE), np.int16)
            for i in range(len(user) // FRAME_SAMPLES):
                if self._closed:
                    return
                time.sleep(0.002)
                mix = user[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES].astype(np.int32) + self._room()
                yield np.clip(mix, -32768, 32767).astype(np.int16)

    def close(self):
        self._closed = True


def dominant_hz(pcm):
    spec = np.abs(np.fft.rfft(pcm.astype(np.float64) * np.hanning(len(pcm))))
    return np.argmax(spec) * SAMPLE_RATE / len(pcm)


REPLY = "I am running smoothly. The evening light is pleasant on my sensors, and I am content to sit here with you."


def loop(script, questions, replies, delay_s=0.3, **cfg):
    """An assistant in the room above. Whisper is faked: the user's voice (140 Hz) gives the next
    question, Marvin's (FakeTTS, 220 Hz) gives back what it said, as the real Whisper does."""
    sink = LoopSink()
    src = LoopSource(script, sink, delay_s)
    questions = list(questions)
    llm = FakeLLM(list(replies))
    tts = FakeTTS(ms_per_char=15)

    def whisper(pcm):
        if abs(dominant_hz(pcm) - 220) < 15:
            return Transcript(" ".join(s for s, _ in tts.said[-4:]), "en", True)
        return Transcript(questions.pop(0) if questions else "", "en", True)

    stt = FakeSTT(whisper)
    turns = []
    va = VoiceAssistant(src, sink, config=VoiceConfig(**cfg), stt=stt, llm=llm, tts=tts, vad=EnergyVad(),
                        on_transcript=turns.append)
    src.assistant = va
    return va, SimpleNamespace(llm=llm, stt=stt, tts=tts, sink=sink, turns=turns)


def test_never_answers_its_own_voice_half_duplex():
    va, t = loop([("quiet", 0.5), ("say", 1.5), ("quiet", 0.8), ("until_quiet",), ("quiet", 6)],
                 ["Marvin, how are you doing today?"], [REPLY])
    va.run()
    assert t.turns == ["how are you doing today?"]
    assert len(t.llm.calls) == 1
    assert len(t.stt.calls) == 1                         # its own voice was not even heard


@pytest.mark.parametrize("delay_s", [0.3, 2.5])
def test_never_answers_its_own_voice_duplex(delay_s, caplog):
    """Full duplex: it hears itself. With a long echo path (2.5 s, e.g. a buffered network speaker)
    the echo lands in the follow-up window, where only the transcript filter can catch it."""
    caplog.set_level("INFO", "marvin.voice")
    va, t = loop([("quiet", 0.5), ("say", 1.5), ("quiet", 0.8), ("until_quiet",), ("quiet", 6)],
                 ["Marvin, how are you doing today?"], [REPLY], delay_s=delay_s, duplex=True)
    va.run()
    assert len(t.stt.calls) >= 2                         # it heard itself...
    assert t.turns == ["how are you doing today?"]       # ...and ignored it
    assert len(t.llm.calls) == 1
    if delay_s > 2:
        assert "(ignored: own voice)" in caplog.text


def test_follow_up_still_accepts_a_real_question():
    va, t = loop([("quiet", 0.5), ("say", 1.5), ("quiet", 0.8), ("until_quiet",), ("quiet", 1.0),
                  ("say", 1.2), ("quiet", 0.8), ("until_quiet",), ("quiet", 2)],
                 ["Marvin, how are you doing today?", "What are your sensors?"],
                 [REPLY, "A lidar, two radars and a camera."])
    va.run()
    assert t.turns == ["how are you doing today?", "What are your sensors?"]
    assert len(t.llm.calls) == 2


def test_echo_filter():
    from marvin_host.voice.echo import EchoFilter
    f = EchoFilter()
    f.speaking("I am running smoothly. The evening light is pleasant on my sensors,")
    assert f.is_own_voice("I am running smoothly.")                      # while speaking
    f.said(REPLY)
    assert f.is_own_voice(REPLY)
    assert f.is_own_voice("I'm running smoothly, the evening light is pleasant on my sensor.")   # garbled
    assert f.is_own_voice("content to sit here with you")                # a chunk of it
    assert f.is_own_voice("with you.")                                   # the tail
    assert not f.is_own_voice("How are you?")
    assert not f.is_own_voice("What are your sensors?")
    assert not f.is_own_voice("Marvin.")                                 # the user calling
    assert not f.is_own_voice("Is the evening light bad for the lidar?")
    f.said("La capitale de la France est Paris.")
    assert not f.is_own_voice("Quelle est la capitale de l'Espagne ?")
    assert f.is_own_voice(REPLY)                                         # still remembers the previous one
    f.said("Troisième réponse, sans rapport.")
    assert not f.is_own_voice(REPLY)                                     # only the last two replies
    f.speaking("Je suis Marvin, votre robot.")
    assert f.current_has_wake_word()


def test_echo_gate():
    from marvin_host.voice.echo import EchoGate
    now = [0.0]
    sink = SimpleNamespace(busy=False, output_latency=0.1)
    g = EchoGate(sink, tail_s=0.8, clock=lambda: now[0])
    assert not g.closed()
    g.speaking(True)
    now[0] = 5
    assert g.closed()
    g.speaking(False)
    now[0] = 5.85
    assert g.closed()                                    # tail = 0.8 s + 0.1 s output latency
    now[0] = 5.95
    assert not g.closed()
    g.speaking(False)                                    # not speaking: no new tail
    assert not g.closed()
    assert not EchoGate(sink, enabled=False).closed()


def test_say_is_skipped_during_a_conversation():
    va, t = make([], [])
    assert va.say("Bonjour.")
    assert va.wait_idle(timeout=2)
    assert t.tts.said == [("Bonjour.", "fr")] and t.log["reply"] == ["Bonjour."]
    va._set_status(va._status.__class__.LISTENING)
    assert not va.say("Pause ?")
    va.close()


# ---------------------------------------------------------------- proactive speech

def test_proactive_reminders():
    said = []
    now = [0.0]
    fake = SimpleNamespace(language="fr", say=lambda text, lang: said.append((text, lang)) or True)
    p = ProactiveSpeaker(fake, clock=lambda: now[0])
    p(Event(EventKind.STILL_LONG, int(3000e6), data={"seated_s": 3000}))
    assert said == [("Tu es assis depuis 50 minutes. Et si tu faisais une pause ?", "fr")]
    now[0] = 60
    p(Event(EventKind.STILL_LONG, int(3600e6), data={"seated_s": 3600}))
    assert len(said) == 1                                # rate-limited
    now[0] = 3600
    fake.language = "en"
    p(Event(EventKind.STILL_LONG, int(7200e6), data={"seated_s": 3600}))
    assert said[-1] == ("You've been sitting for an hour. Time to stretch?", "en")
    # welcome back is off by default
    p(Event(EventKind.LEFT, int(8000e6)))
    now[0] = 99999
    p(Event(EventKind.ARRIVED, int(8000e6 + 3600e6)))
    assert len(said) == 2


def test_proactive_welcome_back_after_long_absence_only():
    said = []
    fake = SimpleNamespace(language="fr", say=lambda text, lang: said.append(text) or True)
    p = ProactiveSpeaker(fake, ProactiveConfig(welcome_back=True, min_interval_s=0))
    p(Event(EventKind.ARRIVED, int(10e6)))               # first arrival: no absence known
    p(Event(EventKind.LEFT, int(20e6)))
    p(Event(EventKind.ARRIVED, int(80e6)))               # back after a minute
    p(Event(EventKind.LEFT, int(90e6)))
    p(Event(EventKind.ARRIVED, int(90e6 + 3600e6)))      # back after an hour
    assert said == ["Re-bonjour."]


# ---------------------------------------------------------------- optional: real Whisper

def _speech_wav(tmp_path):
    """'Marvin, ...' spoken by Piper (if a voice is installed) or espeak-ng; None if neither."""
    from marvin_host.voice.tts import piper_dir
    try:
        from marvin_host.voice.tts import PiperTTS
        if (piper_dir() / "fr_FR-siwis-medium.onnx").exists():
            pcm, rate = PiperTTS(download=False).synthesize("Marvin, quelle est la capitale de la France ?", "fr")
            return resample(pcm, rate)
    except RuntimeError:
        pass
    exe = shutil.which("espeak-ng")
    if exe:
        path = tmp_path / "e.wav"
        subprocess.run([exe, "-v", "en-gb", "-w", str(path), "Marvin, what time is it?"], check=True)
        pcm, rate = read_wav(path)
        return resample(pcm, rate)
    return None


def test_whisper_tiny_hears_marvin(tmp_path):
    pytest.importorskip("faster_whisper")
    speech = _speech_wav(tmp_path)
    if speech is None:
        pytest.skip("no speech synthesizer (piper voice or espeak-ng) to make test audio")
    from marvin_host.voice.stt import WhisperSTT
    try:
        stt = WhisperSTT("tiny")
    except Exception as e:                               # no network to download the model
        pytest.skip(f"whisper tiny unavailable: {e}")
    audio = np.concatenate([silence(0.5), speech, silence(1.0)])
    fake = FakeLLM(["D'accord."])
    src = ArraySource(audio)
    va = VoiceAssistant(src, NullSink(), config=VoiceConfig(follow_up_s=0), stt=stt, llm=fake, tts=FakeTTS())
    va.run()
    assert len(fake.calls) == 1, "wake word not heard"
    assert len(said(fake.calls[0][-1])) > 5


# ---------------------------------------------------------------- TTS backends and CLI glue

SAY_LISTING = """Albert              en_US    # Hello! My name is Albert.
Amélie              fr_CA    # Bonjour, je m’appelle Amélie.
Daniel              en_GB    # Hello! My name is Daniel.
Eddy (French (France)) fr_FR    # Bonjour, je m’appelle Eddy.
Thomas              fr_FR    # Bonjour, je m’appelle Thomas.
Thomas (Enhanced)   fr_FR    # Bonjour, je m’appelle Thomas.
"""


def test_say_voice_choice():
    from marvin_host.voice.tts import MacSayTTS, parse_say_voices
    voices = parse_say_voices(SAY_LISTING)
    assert ("Eddy (French (France))", "fr_FR") in voices and len(voices) == 6
    tts = MacSayTTS.__new__(MacSayTTS)                   # no `say` here: skip the constructor's check
    tts._fixed, tts._installed, tts.rate_wpm = {}, voices, None
    assert tts.voice_for("fr") == "Thomas (Enhanced)"
    assert tts.voice_for("en") == "Daniel"
    assert tts.voice_for("de") is None
    tts._fixed = {"fr": "Amélie"}
    assert tts.voice_for("fr") == "Amélie"


def test_espeak_tts():
    if not shutil.which("espeak-ng"):
        pytest.skip("espeak-ng not installed")
    from marvin_host.voice.tts import EspeakTTS
    pcm, rate = EspeakTTS().synthesize("Bonjour.", "fr")
    assert pcm.dtype == np.int16 and len(pcm) > rate * 0.2


def test_cli_glue(tmp_path, monkeypatch):
    import argparse

    monkeypatch.setenv("MARVIN_CONFIG_DIR", str(tmp_path))

    from marvin_host.voice import add_cli, add_run_arguments, attach
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd")
    handler = add_cli(sub)
    run = sub.add_parser("run")
    add_run_arguments(run)
    assert callable(handler)
    a = ap.parse_args(["talk", "--lang", "en", "--no-wake", "--stt-model", "tiny", "--wav", "x.wav"])
    assert (a.lang, a.no_wake, a.stt_model, a.wav) == ("en", True, "tiny", "x.wav")
    r = ap.parse_args(["run"])
    assert attach(SimpleNamespace(), r) is None          # no --voice: nothing started
    r = ap.parse_args(["run", "--voice", "--voice-llm-model", "llama3.2:3b", "--voice-welcome"])
    from marvin_host.voice.cli import _config
    c = _config(r, "voice_")
    assert c.llm_model == "llama3.2:3b" and c.wake and r.voice_welcome


def _parser():
    import argparse

    from marvin_host.voice import add_cli, add_run_arguments
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd")
    add_cli(sub)
    add_run_arguments(sub.add_parser("run"))
    return ap


def test_settings_file_and_flags(tmp_path, monkeypatch):
    from marvin_host.voice.cli import _config, _flags, _settings, save_settings, settings_path
    monkeypatch.setenv("MARVIN_CONFIG_DIR", str(tmp_path))
    assert settings_path() == tmp_path / "voice.json"
    ap = _parser()
    c = _config(ap.parse_args(["talk"]))
    assert c.llm_model == "qwen3:4b-instruct" and not c.duplex and c.stt == "auto"      # built-in defaults
    save_settings({"llm_model": "qwen3:8b", "stt": "mlx", "end_silence_ms": 450, "input_device": 2})
    c = _config(ap.parse_args(["talk"]))
    assert (c.llm_model, c.stt, c.segmenter.end_silence_s) == ("qwen3:8b", "mlx", 0.45)
    assert _settings(ap.parse_args(["talk"]))["input_device"] == 2
    a = ap.parse_args(["talk", "--llm-model", "qwen3:1.7b", "--duplex", "--no-wake"])
    c = _config(a)
    assert (c.llm_model, c.stt, c.duplex, c.wake) == ("qwen3:1.7b", "mlx", True, False)     # flags win
    r = ap.parse_args(["run", "--voice", "--voice-echo-tail", "1.2"])
    c = _config(r, "voice_")
    assert c.echo_tail_s == 1.2 and c.llm_model == "qwen3:8b"
    # --save-defaults keeps the other keys
    save_settings(_flags(ap.parse_args(["talk", "--tts", "piper"])))
    data = json.loads((tmp_path / "voice.json").read_text())
    assert data["tts"] == "piper" and data["llm_model"] == "qwen3:8b"
    (tmp_path / "voice.json").write_text("{not json")
    assert _config(ap.parse_args(["talk"])).llm_model == "qwen3:4b-instruct"             # broken file: ignored


# ---------------------------------------------------------------- latency

def test_first_clause_is_released_early():
    sp = SentenceSplitter()
    out = []
    for ch in "La capitale de la France, sans hésiter, c'est Paris. Une belle ville, vraiment.":
        out += sp.feed(ch)
        if out and len(out) == 1:
            break
    assert out == ["La capitale de la France,"]           # before the end of the first sentence
    sp = SentenceSplitter()
    assert sp.feed("Oui, ") == []                         # too short to stand alone
    assert sp.feed("bien sûr. Et ") == ["Oui, bien sûr."]
    assert split_sentences("Un, deux, trois, quatre, cinq.") == ["Un, deux, trois, quatre, cinq."]


def test_speculative_transcription_is_used():
    va, t = make([("quiet", 1), ("say", 1.5), ("quiet", 1), ("idle",)], ["Marvin, quelle heure est-il ?"],
                 ["Midi."])
    va.run()
    assert len(t.stt.calls) == 1                          # started during the pause, reused at the end
    assert va.last_latency.get("speculative") == 1.0


def test_speculation_is_dropped_when_the_speaker_goes_on():
    # a 0.4 s pause (speculation starts at 0.25 s) inside the question, then more words
    va, t = make([("quiet", 1), ("say", 1.0), ("quiet", 0.4), ("say", 1.0), ("quiet", 1), ("idle",)],
                 ["Marvin, quelle", "Marvin, quelle heure est-il ?"], ["Midi."])
    va.run()
    assert len(t.stt.calls) == 2 and t.stt.calls[1] > t.stt.calls[0]
    assert said(t.llm.calls[0][-1]) == "quelle heure est-il ?"


def test_latency_line():
    from marvin_host.voice.assistant import format_latency
    line = format_latency({"endpoint": 0.55, "queue": 0.01, "stt": 0.12, "speculative": 1.0,
                           "llm_first_token": 0.25, "first_chunk": 0.4, "tts": 0.2, "audio_start": 0.8})
    assert line.startswith("first word 1.35 s after you stopped talking")
    assert "speech recognition 0.12 (speculative)" in line and "backlog" not in line


def test_ollama_warm_up_loads_the_model_and_caches_the_prompt(ollama):
    llm = OllamaLLM(host=ollama)
    assert llm.warm_up("You are Marvin.") is not None
    req = _FakeOllama.requests[-1]
    assert req["keep_alive"] == "30m" and req["options"]["num_predict"] == 1
    assert req["messages"][0] == {"role": "system", "content": "You are Marvin."} and req["think"] is False
    list(llm.stream_chat([{"role": "user", "content": "?"}]))
    assert _FakeOllama.requests[-1]["keep_alive"] == "30m"
    assert OllamaLLM(host="http://127.0.0.1:9", timeout=1).warm_up() is None


# ---------------------------------------------------------------- MLX Whisper (faked: no Apple GPU here)

class _FakeMlxWhisper:
    def __init__(self, language="fr", text=" Marvin, quelle heure est-il ?"):
        self.language, self.text, self.calls = language, text, []

    def transcribe(self, audio, **kw):
        self.calls.append(kw)
        return {"text": self.text, "language": kw.get("language") or self.language, "segments": []}


def test_mlx_whisper_backend(monkeypatch):
    import sys

    from marvin_host.voice.stt import MlxWhisperSTT
    fake = _FakeMlxWhisper()
    monkeypatch.setitem(sys.modules, "mlx_whisper", fake)
    stt = MlxWhisperSTT()                                 # warm-up call at load
    assert stt.repo == "mlx-community/whisper-large-v3-turbo" and len(fake.calls) == 1
    tr = stt.transcribe(voice(1.5))
    assert tr == Transcript("Marvin, quelle heure est-il ?", "fr", True, tr.seconds)
    kw = fake.calls[-1]
    assert kw["path_or_hf_repo"] == stt.repo and kw["language"] is None and kw["temperature"] == 0.0
    assert not stt.transcribe(voice(0.5)).confident       # too short to trust the language
    fake.language = "cy"                                  # detected as Welsh: decoded again in French
    tr = stt.transcribe(voice(1.5))
    assert tr.language == "fr" and not tr.confident and fake.calls[-1]["language"] == "fr"
    assert MlxWhisperSTT("small").repo == "mlx-community/whisper-small-mlx"
    assert MlxWhisperSTT("me/my-whisper").repo == "me/my-whisper"


def test_make_stt_picks_mlx_and_falls_back(monkeypatch):
    import sys

    from marvin_host.voice import stt as stt_mod

    class CpuWhisper:
        def __init__(self, model, languages):
            self.model = model

    monkeypatch.setattr(stt_mod, "WhisperSTT", CpuWhisper)
    monkeypatch.setitem(sys.modules, "mlx_whisper", _FakeMlxWhisper())
    monkeypatch.setattr(stt_mod, "mlx_available", lambda: True)
    assert isinstance(stt_mod.make_stt("auto"), stt_mod.MlxWhisperSTT)
    assert stt_mod.make_stt("auto", "small").repo == "mlx-community/whisper-small-mlx"
    assert stt_mod.make_stt("faster-whisper").model == "small"
    monkeypatch.setattr(stt_mod, "mlx_available", lambda: False)
    assert stt_mod.make_stt("auto").model == "small"      # not a Mac / not installed
    assert stt_mod.make_stt("auto", "tiny").model == "tiny"
    monkeypatch.setattr(stt_mod, "mlx_available", lambda: True)

    def broken(*a, **k):
        raise RuntimeError("Metal is unhappy")

    monkeypatch.setattr(stt_mod, "MlxWhisperSTT", broken)
    assert stt_mod.make_stt("auto").model == "small"      # MLX fails to load: CPU
    with pytest.raises(RuntimeError):
        stt_mod.make_stt("mlx")                           # asked for explicitly: no silent fallback
    with pytest.raises(ValueError):
        stt_mod.make_stt("whisper.cpp")


def test_mlx_only_on_apple_silicon(monkeypatch):
    import platform
    import sys

    from marvin_host.voice.stt import mlx_available
    monkeypatch.setattr(sys, "platform", "linux")
    assert not mlx_available()
    monkeypatch.setattr(sys, "platform", "darwin")
    monkeypatch.setattr(platform, "machine", lambda: "x86_64")
    assert not mlx_available()
