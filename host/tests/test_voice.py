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
    assert "breathing at 14 per minute, right now" in text
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
            try:
                self.wfile.write((json.dumps(line) + "\n").encode())
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return                                   # the client stopped reading (warm-up)
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
    """What the person said, from a user message sent to the model (between the context block and
    the language reminder)."""
    if message["role"] != "user":
        return message["content"]
    return message["content"].split("The person says: ", 1)[-1].split("\n\n(Answer in ", 1)[0]


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
                 [Transcript("What's the time?", "en", True), Transcript("Et demain alors ?", "fr", False)],
                 ["It's noon.", "Of course."], wake=False)
    va.run()
    assert [said(c[-1]) for c in t.llm.calls] == ["What's the time?", "Et demain alors ?"]
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
    assert "seated for 60 minutes" in system and "heart rate at 62" in system


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


def test_history_only_grows_at_the_end_until_it_is_halved():
    va, _ = make([("quiet", 0.1)], [], memory_turns=4)
    starts = []
    for i in range(1, 8):
        va._remember(f"q{i}", f"a{i}")
        starts.append(va.history[0]["content"])
    # q1 stays first while the history fills (the model server keeps its work), then half goes at once
    assert starts == ["q1", "q1", "q1", "q1", "q4", "q4", "q4"]
    assert len(va.history) == 2 * 4


def test_echo_filter_only_near_the_reply():
    from marvin_host.voice.echo import EchoFilter
    now = [100.0]
    f = EchoFilter(clock=lambda: now[0])
    f.said("Si tu veux mesurer tes signes vitaux, reste assis et immobile devant moi pendant quelques secondes.")
    assert f.is_own_voice("Je suis assis et immobile.", started=101.0)     # right after: could be the echo
    now[0] = 106.0
    assert not f.is_own_voice("Je suis assis et immobile.", started=105.5)  # later: the person answering
    assert f.is_own_voice("Je suis assis et immobile.")                    # no time given: as before
    f.speaking("reste assis et immobile")
    assert f.is_own_voice("assis et immobile", started=106.0)              # while it speaks: always


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


def test_ollama_warm_up_rehearses_a_real_question(ollama, caplog):
    """The warm-up must look exactly like a question (same options, stream, think, keep_alive,
    same system prompt), or Ollama reloads the model or reprocesses the prompt at the first one."""
    caplog.set_level("INFO", "marvin.voice.llm")
    llm = OllamaLLM(host=ollama)
    system = persona.persona_prompt("fr")
    assert llm.warm_up(system, persona.user_message("Bonjour.")) is not None
    warm = _FakeOllama.requests[-2:]
    assert warm[0] == warm[1]                             # load, then check that the cache is reused
    list(llm.stream_chat([{"role": "system", "content": system},
                          {"role": "user", "content": persona.user_message("Quelle heure est-il ?")}]))
    real = _FakeOllama.requests[-1]
    for key in ("model", "options", "keep_alive", "think", "stream"):
        assert warm[0][key] == real[key], key
    assert real["options"]["num_ctx"] == 8192 and real["keep_alive"] == "30m" and real["think"] is False
    assert warm[0]["messages"][0] == real["messages"][0]  # the cached prefix
    assert "prompt cached" in caplog.text
    assert OllamaLLM(host="http://127.0.0.1:9", timeout=1).warm_up("x") is None


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


# ---------------------------------------------------------------- Whisper hallucinations

def test_decoder_scores():
    from marvin_host.voice.filters import decoder_reason
    assert decoder_reason(0.1, -0.3, 1.2) is None                       # clear speech
    assert decoder_reason(None, None, None) is None                     # unknown: no opinion
    assert "no speech" in decoder_reason(0.8, -1.1, 1.0)
    assert decoder_reason(0.8, -0.4, 1.0) is None                       # sure of the words: keep
    assert "repetitive" in decoder_reason(0.1, -0.3, 3.1)
    assert "low confidence" in decoder_reason(0.1, -1.5, 1.0)


def test_known_hallucinations():
    from marvin_host.voice.filters import hallucination_reason
    for text in ["Thank you.", "thanks for watching!", "I'm going to go.", "Bye.", "...", "♪ ♪",
                 "Sous-titres réalisés par la communauté d'Amara.org", "Merci d'avoir regardé !",
                 "Merci d'avoir regardé cette vidéo", "SOUS-TITRAGE ST' 501", "you",
                 "the the the the the the the the"]:
        assert hallucination_reason(text), text
    for text in ["Thank you for the answer, what about tomorrow?", "Tu m'entends ?", "I'm going to go to Paris.",
                 "Merci, et quelle heure est-il ?", "Bye the way, what's the weather?"]:
        assert hallucination_reason(text) is None, text


def test_follow_up_decisions():
    from marvin_host.voice.filters import ACCEPT, CLOSE, IGNORE, follow_up_decision as d
    assert d("Et qu'est-ce que tu ressens ?", "fr", None, "fr")[0] == ACCEPT
    assert d("I'm going to go.", "en", None, "fr")[0] == IGNORE                # unsure language switch
    assert d("I'm going to go.", "en", 0.6, "fr")[0] == IGNORE
    assert d("What do you see now?", "en", 0.95, "fr")[0] == ACCEPT            # sure: the person switched
    assert d("Merci.", "fr", None, "fr")[0] == CLOSE
    assert d("OK, super, merci !", "fr", None, "fr")[0] == CLOSE
    assert d("OK.", "fr", None, "fr")[0] != CLOSE          # a sentence may follow
    assert d("D'accord.", "fr", None, "fr")[0] != CLOSE
    assert d("Ok merci", "fr", None, "fr")[0] == CLOSE
    assert d("Pourquoi ?", "fr", None, "fr")[0] == ACCEPT
    assert d("Oui.", "fr", None, "fr", "Une petite pause ?")[0] == ACCEPT      # answers its question
    assert d("Oui.", "fr", None, "fr", "Il est midi.")[0] == CLOSE
    assert d("Chaussette.", "fr", None, "fr")[0] == IGNORE                     # one word


def test_speech_evidence():
    from marvin_host.voice.filters import speech_evidence
    from marvin_host.voice.vad import Segment
    ok = Segment(np.concatenate([silence(0.3), voice(0.8), silence(0.15)]), 0, 1.25, 1.1, 1, 40)
    assert speech_evidence(ok) is None
    assert "speech" in speech_evidence(Segment(ok.pcm, 0, 1.25, 1.1, 1, 10))          # 0.2 s voiced
    assert "silence" in speech_evidence(Segment(np.concatenate([silence(3), voice(0.4)]), 0, 3.4, 3.4, 1, 16))
    assert "quiet" in speech_evidence(Segment((voice(1.0) // 200).astype(np.int16), 0, 1, 1, 1, 50))


def test_whisper_segments_are_filtered():
    from marvin_host.voice.stt import _keep_segments
    seg = SimpleNamespace
    text, scores, rejected = _keep_segments([
        seg(text=" Tu m'entends ?", no_speech_prob=0.05, avg_logprob=-0.3, compression_ratio=1.1),
        seg(text=" Merci d'avoir regardé.", no_speech_prob=0.9, avg_logprob=-1.4, compression_ratio=1.0)])
    assert text == "Tu m'entends ?" and rejected is None and scores["avg_logprob"] == -0.3
    text, _, rejected = _keep_segments([{"text": " I'm going to go.", "no_speech_prob": 0.85,
                                         "avg_logprob": -1.05, "compression_ratio": 0.9}])
    assert text == "" and "no speech" in rejected


FOLLOW_UP = [("say", 1.0), ("quiet", 1), ("idle",), ("quiet", 1), ("say", 1.0), ("quiet", 1), ("idle",)]


@pytest.mark.parametrize("heard, why", [
    (Transcript("I'm going to go.", "en", True), "blocklist + language"),
    (Transcript("Je vais y aller maintenant.", "fr", True, avg_logprob=-1.6), "low confidence"),
    (Transcript("Je vais y aller maintenant.", "fr", True, no_speech_prob=0.9, avg_logprob=-1.1), "no speech"),
    (Transcript("", "fr", True, rejected="no speech (p=0.91)"), "rejected by the decoder"),
    (Transcript("Where are you going next?", "en", False, language_prob=0.55), "unsure language switch"),
    (Transcript("Thanks for watching!", "fr", True), "blocklist"),
    (Transcript("Chaussette.", "fr", True), "one word"),
])
def test_no_turn_on_hallucinations_in_the_follow_up_window(heard, why, caplog):
    caplog.set_level("INFO", "marvin.voice")
    va, t = make(FOLLOW_UP, ["Marvin, tu m'entends ?", heard], ["Oui, très bien."], follow_up_s=5.0)
    va.run()
    assert len(t.llm.calls) == 1, why
    assert "(ignored:" in caplog.text


def test_thanks_closes_the_conversation(caplog):
    caplog.set_level("INFO", "marvin.voice")
    va, t = make(FOLLOW_UP, ["Marvin, tu m'entends ?", "Merci."], ["Oui, très bien."], follow_up_s=5.0)
    va.run()
    assert len(t.llm.calls) == 1 and t.log["status"][-2:] == ["listening", "idle"]
    assert "conversation closed" in caplog.text


def test_real_follow_up_questions_still_work():
    va, t = make(FOLLOW_UP + [("quiet", 1), ("say", 1.0), ("quiet", 1), ("idle",)],
                 ["Marvin, tu m'entends ?", "Et qu'est-ce que tu as comme capteurs ?", "Oui."],
                 ["Oui, très bien. Tu veux savoir quelque chose ?",
                  "Un lidar, deux radars et une caméra. Autre chose ?",
                  "Lequel ?"], follow_up_s=5.0)
    va.run()
    assert [said(c[-1]) for c in t.llm.calls] == ["tu m'entends ?", "Et qu'est-ce que tu as comme capteurs ?", "Oui."]


def test_weak_audio_is_not_even_transcribed(caplog):
    caplog.set_level("INFO", "marvin.voice")
    # 0.28 s of sound: long enough to make a segment (0.25 s), too short to be worth Whisper (0.3 s)
    va, t = make([("quiet", 1), ("say", 0.28), ("quiet", 1), ("idle",)], ["Thank you."], wake=False)
    va.run()
    assert t.stt.calls == [] and t.llm.calls == []
    assert "s of speech" in caplog.text


def test_wake_word_with_a_hallucinated_tail_just_listens():
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",)], ["Marvin. Thank you."], follow_up_s=5.0)
    va.run()
    assert t.llm.calls == [] and t.log["status"] == ["listening"]


def test_reply_language_reminder_and_voice_follow_the_text():
    from marvin_host.voice import persona
    from marvin_host.voice.text import guess_language
    msg = persona.user_message("est-ce que t'as faim ?", language="fr")
    assert msg.endswith("(Answer in French.)")
    assert guess_language("No, I do not get hungry. I am a program running on your computer.") == "en"
    assert guess_language("Non, je n'ai pas faim, je suis un programme.") == "fr"
    assert guess_language("OK.") is None


def test_live_signals_for_the_app():
    va, t = make([("quiet", 1), ("say", 1.5), ("quiet", 1), ("idle",)],
                 ["Marvin, quelle heure est-il ?"], ["Il est midi. Bon appétit."])
    events = []
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    kinds = [k for k, _ in events]
    levels = [d for k, d in events if k == "level"]
    assert 50 <= len(levels) <= 70                        # ~16 per second over 3.5 s of audio
    assert max(d["mic"] for d in levels) > 0.5 > min(d["mic"] for d in levels)
    assert any(d["speech"] for d in levels)
    utt = [d["state"] for k, d in events if k == "utterance"]
    assert utt == ["start", "end", "done"]
    assert kinds.index("heard") < len(kinds) - 1 - kinds[::-1].index("utterance")   # heard before done
    assert any(k == "partial" and d["text"] == "Marvin, quelle heure est-il ?" for k, d in events)
    says = [d for k, d in events if k == "say"]
    assert [d["text"] for d in says] == ["Il est midi.", "Bon appétit."]
    assert all(d["seconds"] > 0 and len(d["envelope"]) == int(d["seconds"] * 20) for d in says)
    assert kinds.index("say") < kinds.index("reply")


def test_envelope():
    from marvin_host.voice.assistant import envelope
    t = np.arange(16000) / 16000
    pcm = np.concatenate([np.zeros(8000), 8000 * np.sin(2 * np.pi * 220 * t[:8000])]).astype(np.int16)
    env = envelope(pcm, 16000)
    assert len(env) == 20 and env[0] == 0.0 and env[-1] > 0.7


def test_a_chosen_voice_only_reads_its_own_language():
    from marvin_host.voice.tts import voice_language, voices_for
    say = [("Daniel", "en_GB"), ("Thomas (Enhanced)", "fr_FR")]
    # an English voice picked while the conversation is French must not read French text
    assert voices_for("auto", "en_GB-alan-medium", "fr", say)["piper"] == {"en": "en_GB-alan-medium"}
    assert voices_for("auto", "Daniel", "fr", say) == {"piper": None, "say": {"en": "Daniel"}, "espeak": None}
    assert voices_for("auto", "Thomas", "en", say)["say"] == {"fr": "Thomas"}
    assert voices_for("say", "Unknown", "fr", say)["say"] == {"fr": "Unknown"}   # cannot tell: the language asked
    assert voices_for("auto", None, "fr", say) == {"piper": None, "say": None, "espeak": None}
    assert voice_language("/voices/fr_FR-siwis-medium.onnx") == "fr"
    assert voice_language("en-gb") == "en" and voice_language("fr") == "fr"


def test_talk_now_takes_what_comes():
    box = {}
    va, t = make([("quiet", 0.5), ("call", lambda: box["va"].listen_now()), ("quiet", 0.5), ("say", 0.8),
                  ("quiet", 1), ("idle",), ("quiet", 0.3)], ["Bonjour."], ["Bonjour !"])
    box["va"] = va
    va.run()
    # one word and no name: a follow-up would ignore it, but Talk now asked Marvin to listen
    assert t.log["transcript"] == ["Bonjour."]


def test_talk_now_can_be_stopped_and_says_how_long_is_left():
    box = {}
    left = []

    def open_and_close():
        box["va"].listen_now()
        left.append(box["va"].listen_remaining())
        assert box["va"].stop_listening()
        left.append(box["va"].listen_remaining())

    va, t = make([("quiet", 0.5), ("call", open_and_close), ("quiet", 0.5), ("say", 0.8), ("quiet", 1)],
                 ["Bonjour."], ["Bonjour !"])
    box["va"] = va
    va.run()
    assert 5.5 <= left[0] <= 6.5 and left[1] is None
    assert t.log["transcript"] == [] and t.log["status"] == ["listening", "idle"]


def test_context_says_why_there_are_no_vital_signs():
    near = dict(t_us=1, present=True, seated=True, distance_m=0.8)
    none = " ".join(persona.context_facts(PresenceState(**near)))
    assert "not connected yet" in none and "cannot measure" in none      # the MR60BHA2 is not there
    waiting = " ".join(persona.context_facts(PresenceState(**near, vitals_sensor=True)))
    assert "no reliable reading right now" in waiting and "still" in waiting
    reading = " ".join(persona.context_facts(PresenceState(**near, vitals_sensor=True, heart_rate=64.0)))
    assert "heart rate at 64 beats per minute" in reading and "no reliable" not in reading
    assert "Never promise a reading" in persona.persona_prompt("fr")


def test_context_says_the_sensors_are_simulated():
    text = " ".join(persona.context_facts(PresenceState(t_us=1, present=True, simulated=True, vitals_sensor=True,
                                                        heart_rate=66.0)))
    assert "simulated" in text and "heart rate at 66" in text
    assert "simulated" not in " ".join(persona.context_facts(PresenceState(t_us=1, present=True)))


# ---------------------------------------------------------------- one thought, one question

@pytest.mark.parametrize("text", [
    "Je veux aussi que tu saches que", "Ok.", "OK", "Bon...", "Alors,", "d'accord", "Marvin, ok",
    "Il fait beau, mais", "Je ne viens pas parce que", "Je voudrais un", "Et donc", "euh",
    "I went there and", "because", "OK so", "I was, um", "Well,", "It was great but",
])
def test_announces_more(text):
    from marvin_host.voice.filters import announces_more
    assert announces_more(text)


@pytest.mark.parametrize("text", [
    "", "Marvin.", "Je suis développeur.", "Quelle heure est-il ?", "Et alors ?", "Ça marche !",
    "J'ai faim.", "I think so.", "What time is it?", "Hello there.", "That's all.", "Merci beaucoup.",
])
def test_does_not_announce_more(text):
    from marvin_host.voice.filters import announces_more
    assert not announces_more(text)


def _segments(audio, endpoint=None, **cfg):
    seg = Segmenter(EnergyVad(), SegmenterConfig(**cfg))
    seg.endpoint = endpoint
    out = [s for i in range(len(audio) // FRAME_SAMPLES)
           if (s := seg.feed(audio[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES]))]
    return out


def test_segmenter_waits_longer_when_asked():
    audio = np.concatenate([silence(0.5), voice(1.0), silence(0.8), voice(1.0), silence(1.5)])
    assert len(_segments(audio)) == 2                                   # 0.8 s ends it at 0.55 s
    asked = []
    one = _segments(audio, endpoint=lambda: asked.append(1) or 1.1)
    assert len(one) == 1 and one[0].duration > 2.5 and len(asked) == 2  # asked once per pause
    assert one[0].speech_start == pytest.approx(0.5, abs=0.05)          # the first voiced frame, not the pre-roll
    assert len(_segments(audio, endpoint=lambda: None)) == 2
    assert len(_segments(audio, endpoint=lambda: 1.1, end_silence_long_s=0.0)) == 1     # the callback decides
    long_pause = np.concatenate([silence(0.5), voice(1.0), silence(1.3), voice(1.0), silence(1.5)])
    assert len(_segments(long_pause, endpoint=lambda: 1.1)) == 2        # longer than the long silence


@pytest.mark.parametrize("first, whole, one", [
    ("Marvin, je voudrais savoir et", "Marvin, je voudrais savoir et quelle heure il est.", True),
    ("Marvin, I'd like to know because", "Marvin, I'd like to know because I'm late.", True),
    ("Marvin, OK.", "Marvin, OK, what time is it?", True),
    ("Marvin, quelle heure est-il ?", "", False),
])
def test_adaptive_endpoint(first, whole, one):
    # a 0.8 s pause: longer than end_silence_s (0.55 s), shorter than end_silence_long_s (1.1 s)
    va, t = make([("quiet", 0.5), ("say", 1.0), ("quiet", 0.8), ("say", 1.0), ("quiet", 1.5), ("idle",)],
                 [first, whole], ["Bien.", "Bien."], continue_grace_s=0.0)
    va.run()
    if one:
        assert len(t.llm.calls) == 1 and said(t.llm.calls[0][-1]) == match_wake_word(whole)
        assert len(t.stt.calls) == 2 and t.stt.calls[1] > 2.0                   # one utterance, both parts
    else:
        assert said(t.llm.calls[0][-1]) == "quelle heure est-il ?"
        assert t.stt.calls[0] < 1.5                                             # it ended at the pause


def slow_llm(replies, delay_s=0.4):
    """A model that takes `delay_s` before its first word: the listening goes on meanwhile."""
    replies = list(replies)

    def reply(messages):
        time.sleep(delay_s)
        return replies.pop(0) if replies else ""

    return FakeLLM(reply)


def _events(va):
    events = []
    va.add_listener(lambda kind, d: events.append((kind, d)))
    return events


def test_speech_resuming_before_the_answer_continues_the_question(caplog):
    caplog.set_level("INFO", logger="marvin.voice")
    va, t = make([("quiet", 0.5), ("say", 1.5), ("quiet", 0.9), ("say", 1.0), ("quiet", 1.0), ("idle",)],
                 ["Marvin, je veux aussi que tu saches que je suis développeur.", "Et j'aime la voile."],
                 slow_llm(["C'est noté.", "Développeur et marin, c'est noté."]))
    events = _events(va)
    va.run()
    joined = "je veux aussi que tu saches que je suis développeur. Et j'aime la voile."
    assert [said(c[-1]) for c in t.llm.calls] == ["je veux aussi que tu saches que je suis développeur.", joined]
    assert " ".join(s for s, _ in t.tts.said) == "Développeur et marin, c'est noté."  # the first answer never played
    assert t.log["reply"] == ["Développeur et marin, c'est noté."]
    assert [m["content"] for m in va.history if m["role"] == "user"] == [t.llm.calls[1][-1]["content"]]
    heard = [d for k, d in events if k == "heard"]
    assert [h["text"] for h in heard] == ["je veux aussi que tu saches que je suis développeur.", joined]
    assert heard[1]["continues"] == [heard[0]["uid"]] and "continues" not in heard[0]
    assert heard[1]["cut"] == []                                                    # nothing was heard of its answer
    assert [d for k, d in events if k == "reply"][0]["interrupted"] is False        # no reply for the first one
    assert len([k for k, _ in events if k == "reply"]) == 1
    assert len(t.stt.calls) == 2                                                    # both transcribed speculatively
    assert "joined 2 utterances (before the answer)" in caplog.text


def test_no_merge_after_the_grace_period():
    va, t = make([("quiet", 0.5), ("say", 1.5), ("quiet", 2.0), ("say", 1.0), ("quiet", 1.0), ("idle",)],
                 ["Marvin, je suis développeur.", "Et j'aime la voile."],
                 slow_llm(["C'est noté.", "Autre chose."]))
    events = _events(va)
    va.run()
    assert [said(c[-1]) for c in t.llm.calls] == ["je suis développeur."]      # the rest needed the name
    assert t.log["reply"] == ["C'est noté."]
    assert not any("continues" in d for k, d in events if k == "heard")


def test_a_cough_after_the_question_only_delays_the_answer():
    # speech resumes within the grace period, but it is nothing: the answer plays, whole
    va, t = make([("quiet", 0.5), ("say", 1.5), ("quiet", 0.9), ("say", 0.6), ("quiet", 1.0), ("idle",)],
                 ["Marvin, quelle heure est-il ?", "Merci."], slow_llm(["Midi."]))
    va.run()
    assert [said(c[-1]) for c in t.llm.calls] == ["quelle heure est-il ?"]
    assert t.log["reply"] == ["Midi."]


class OnceBlockingSink(BlockingSink):
    """Blocks (a long answer being said) until it is stopped once; then plays at once."""

    def wait(self):
        if not self.stops:
            super().wait()


def test_barge_in_soon_after_the_question_joins_it():
    reply = "Il était une fois un phare au bout du monde. Son gardien parlait peu."
    va, t = make([("say", 1.0), ("quiet", 1), ("status", "speaking"),
                  ("say", 1.2), ("quiet", 1), ("idle",)],
                 ["Marvin, raconte-moi une histoire.", "Marvin, avec des bateaux."],
                 [reply, "Il était une fois un bateau."], sink=OnceBlockingSink(), duplex=True)
    events = _events(va)
    va.run()
    joined = "raconte-moi une histoire. avec des bateaux."
    assert [said(c[-1]) for c in t.llm.calls] == ["raconte-moi une histoire.", joined]
    # the cut answer is not in the history: the joined question says it all
    assert [said(m) for m in t.llm.calls[1][1:]] == [joined]
    assert [said(m) for m in va.history] == [joined, "Il était une fois un bateau."]
    heard = [d for k, d in events if k == "heard"]
    assert heard[1]["continues"] == [heard[0]["uid"]] and heard[1]["cut"] == [heard[0]["uid"]]
    replies = [d for k, d in events if k == "reply"]
    assert replies[0]["interrupted"] and replies[0]["text"]                       # what was said stays shown
    assert not replies[1]["interrupted"]


def test_barge_in_long_after_the_question_is_a_new_question():
    reply = "Il était une fois un phare au bout du monde. Son gardien parlait peu."
    va, t = make([("say", 1.0), ("quiet", 1), ("status", "speaking"),
                  ("quiet", 6), ("say", 1.2), ("quiet", 1), ("idle",)],
                 ["Marvin, raconte-moi une histoire.", "Marvin, quelle heure est-il ?"],
                 [reply, "Midi."], sink=OnceBlockingSink(), duplex=True)
    va.run()
    assert [said(c[-1]) for c in t.llm.calls] == ["raconte-moi une histoire.", "quelle heure est-il ?"]


def test_talk_now_after_cutting_the_answer_joins_the_question():
    box = {}
    reply = "Il était une fois un phare au bout du monde. Son gardien parlait peu."
    va, t = make([("say", 1.0), ("quiet", 1), ("status", "speaking"), ("call", lambda: box["va"].listen_now()),
                  ("quiet", 0.5), ("say", 1.2), ("quiet", 1), ("idle",)],
                 ["Marvin, raconte-moi une histoire.", "Avec des bateaux."],
                 [reply, "Il était une fois un bateau."], sink=OnceBlockingSink())
    box["va"] = va
    events = _events(va)
    va.run()
    assert said(t.llm.calls[-1][-1]) == "raconte-moi une histoire. Avec des bateaux."
    heard = [d for k, d in events if k == "heard"]
    assert heard[-1]["continues"] == [heard[0]["uid"]]


def test_ok_then_the_rest_in_a_follow_up_window():
    # "Ok" [a pause longer than the long endpoint] "j'ai pas encore mangé": one question
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",), ("quiet", 1.5), ("say", 0.5), ("quiet", 1.3),
                  ("say", 1.2), ("quiet", 1.5), ("idle",)],
                 ["Marvin, tu as mangé ?", "Ok.", "J'ai pas encore mangé, j'ai faim là."],
                 ["Non, je suis un robot.", "Alors mange quelque chose !"], follow_up_s=5.0)
    va.run()
    assert [said(c[-1]) for c in t.llm.calls] == ["tu as mangé ?", "Ok. J'ai pas encore mangé, j'ai faim là."]


def test_listening_window_waits_while_someone_talks():
    box = {}
    seen = []

    def during():
        seen.append((box["va"].listen_remaining(), box["va"].hearing, box["va"].status))

    va, t = make([("quiet", 0.5), ("call", lambda: box["va"].listen_now()), ("quiet", 5.0), ("say", 0.8),
                  ("call", during),                     # talking when the window would run out
                  ("quiet", 1.5), ("call", during),     # "Euh." was nothing: the window goes on
                  ("say", 1.2), ("quiet", 1), ("idle",)],
                 ["Euh.", "Quelle heure est-il ?"], ["Midi."])
    box["va"] = va
    va.run()
    assert seen[0] == (None, True, "listening")
    left, hearing, status = seen[1]
    assert status == "listening" and not hearing and 0.5 < left <= 1.5
    assert t.log["transcript"] == ["Quelle heure est-il ?"]


def test_a_question_with_an_image_says_so_and_the_history_keeps_a_note():
    ctx = persona.context_block(None)
    asked = persona.user_message("What is this?", context=ctx, language="en", image_note=persona.IMAGE_NOTE)
    assert asked.endswith(f"{persona.IMAGE_NOTE}\nThe person says: What is this?\n\n(Answer in English.)")
    kept = persona.user_message("What is this?", context=ctx, language="en", image_note=persona.IMAGE_SHOWN_NOTE)
    assert persona.IMAGE_NOTE not in kept and persona.IMAGE_SHOWN_NOTE in kept
    assert persona.user_message("Hi", context=ctx) == f"{ctx}\n\nThe person says: Hi"
    # the rule is in every system prompt: showing an image does not change it
    assert "The one exception is an image the person shows you" in persona.persona_prompt("fr")
