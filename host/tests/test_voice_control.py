"""Controlling the voice from code (the app): listen now, mute, stop speaking, typed questions,
listeners, and `VoiceController` (on and off at run time, settings, conversation, errors).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import threading
import time

import pytest

from marvin_host.voice import cli as voice_cli
from marvin_host.voice.control import VoiceController, VoiceOff, VoiceUnavailable, validate
from test_voice import BlockingSink, make, said


# ---------------------------------------------------------------- VoiceAssistant

def test_listen_now_opens_a_window_without_the_name():
    box = {}
    va, t = make([("quiet", 0.5), ("call", lambda: box["va"].listen_now()), ("status", "listening"),
                  ("say", 1.2), ("quiet", 1), ("idle",)],
                 ["Tu peux me rappeler de boire de l'eau ?"], ["Je n'ai pas de rappels, hélas."])
    box["va"] = va
    va.run()
    assert t.log["status"][:2] == ["listening", "thinking"]
    assert said(t.llm.calls[0][-1]) == "Tu peux me rappeler de boire de l'eau ?"
    assert len(t.sink.played) == 3                       # the chime, then the answer in two chunks


def test_mute_ignores_the_microphone():
    box, events = {}, []
    va, t = make([("call", lambda: box["va"].mute(True)), ("say", 1.0), ("quiet", 1),
                  ("call", lambda: box["va"].mute(False)), ("say", 1.0), ("quiet", 1), ("idle",)],
                 ["Marvin, quelle heure est-il ?"], ["Midi."])
    box["va"] = va
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    assert len(t.stt.calls) == 1 and len(t.llm.calls) == 1   # the muted utterance was not even heard
    assert [d["muted"] for k, d in events if k == "muted"] == [True, False]
    assert not va.muted


def test_typed_question_and_listeners():
    box, events = {}, []
    va, t = make([("quiet", 0.3), ("call", lambda: box["va"].ask("  What time is it, and what day is it today?  ")),
                  ("idle",), ("quiet", 0.2)], [], ["It's noon."])
    box["va"] = va
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    kinds = [k for k, _ in events]
    heard = next(d for k, d in events if k == "heard")
    reply = next(d for k, d in events if k == "reply")
    assert heard["text"] == "What time is it, and what day is it today?" and heard["source"] == "typed"
    assert heard["language"] == "en"                     # guessed from the text
    assert reply["text"] == "It's noon." and not reply["interrupted"] and not reply["proactive"]
    assert reply["error"] is None and "total" in reply["latency"] and reply["t"] > 1e9
    assert kinds.index("heard") < kinds.index("reply")
    assert [d["status"] for k, d in events if k == "status"][:2] == ["thinking", "speaking"]
    va.ask("   ")                                        # nothing to ask: nothing happens
    assert len(t.llm.calls) == 1


def test_stop_speaking():
    box, stopped, events = {}, [], []
    reply = "Il était une fois un phare au bout du monde. Son gardien parlait peu."
    va, t = make([("say", 1.0), ("quiet", 1), ("status", "speaking"),
                  ("call", lambda: stopped.append(box["va"].stop_speaking())), ("idle",)],
                 ["Marvin, raconte-moi une histoire."], [reply], sink=BlockingSink())
    box["va"] = va
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    assert stopped == [True] and t.sink.stops >= 1
    r = next(d for k, d in events if k == "reply")
    assert r["interrupted"] and r["text"]
    assert va.stop_speaking() is False                   # nothing left to stop
    assert va.status == "idle"


def test_ignored_utterances_are_reported():
    events = []
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",)], ["Thanks for watching!"], wake=False)
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    ignored = [d for k, d in events if k == "ignored"]
    assert ignored and ignored[0]["text"] == "Thanks for watching!" and ignored[0]["reason"]
    assert t.llm.calls == []


def test_llm_down_is_reported_with_the_fix():
    from marvin_host.voice.llm import FakeLLM
    events = []
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",)], ["Marvin, ça va ?"], FakeLLM(fail=True))
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    r = next(d for k, d in events if k == "reply")
    assert r["error"] == "llm_down" and "fake LLM" in r["hint"] and r["text"]


# ---------------------------------------------------------------- VoiceController

class FakeAssistant:
    """The part of VoiceAssistant that VoiceController and the app use."""

    def __init__(self, config=None, settings=None):
        self.config, self.settings = config, settings
        self.status = "idle"
        self.muted = False
        self.language = "fr"
        self.listeners = []
        self.calls = []
        self.started = self.closed = False

    def add_listener(self, fn):
        self.listeners.append(fn)

    def remove_listener(self, fn):
        self.listeners.remove(fn)

    def emit(self, kind, **data):
        data.setdefault("t", time.time())
        for fn in list(self.listeners):
            fn(kind, data)

    def start(self):
        self.started = True
        return self

    def close(self):
        self.closed = True

    def ask(self, text):
        self.calls.append(("ask", text))
        self.emit("heard", text=text, language="fr", source="typed")

    def listen_now(self):
        self.calls.append(("listen",))
        self.status = "listening"
        self.emit("status", status="listening")
        return True

    def mute(self, muted=True):
        self.calls.append(("mute", muted))
        self.muted = muted
        self.emit("muted", muted=muted)

    def stop_speaking(self):
        self.calls.append(("stop",))
        return False

    def say(self, text, language=None, force=False):
        self.calls.append(("say", text))
        return True


def controller(tmp_path, **kw):
    made = []

    def factory(config, settings):
        made.append(FakeAssistant(config, settings))
        return made[-1]
    ctl = VoiceController(factory=factory, check=None, path=tmp_path / "voice.json", **kw)
    return ctl, made


def test_controller_life_and_commands(tmp_path):
    ctl, made = controller(tmp_path)
    seen = []
    ctl.add_listener(lambda kind, p: seen.append((kind, p)))
    assert ctl.snapshot()["state"] == "off"
    with pytest.raises(VoiceOff):
        ctl.ask("Bonjour")
    ctl.start(wait=True)
    va = made[0]
    assert ctl.state == "on" and va.started and ctl.snapshot()["status"] == "idle"
    ctl.ask("Quelle heure est-il ?")
    ctl.listen_now()
    ctl.mute(True)
    ctl.stop_speaking()
    assert va.calls == [("ask", "Quelle heure est-il ?"), ("listen",), ("mute", True), ("stop",)]
    assert ctl.snapshot()["muted"] is True and ctl.snapshot()["status"] == "listening"
    with pytest.raises(ValueError):
        ctl.ask(" ")
    va.emit("reply", text="Il est midi.", language="fr", latency={"endpoint": 0.5, "audio_start": 0.7, "stt": 0.1},
            interrupted=False, proactive=False, error=None, hint="")
    va.emit("ignored", text="Merci.", reason="known hallucination")
    kinds = [e["kind"] for e in ctl.recent()]
    assert kinds == ["note", "heard", "reply", "ignored"]
    reply = ctl.recent()[2]
    assert reply["first_word_s"] == 1.2 and reply["latency"]["stt"] == 0.1
    assert ctl.recent(since=reply["id"])[0]["kind"] == "ignored"
    assert any(k == "voice" and p["state"] == "on" for k, p in seen)
    assert any(k == "transcript" and p["kind"] == "heard" for k, p in seen)

    ctl.stop(wait=True)
    assert ctl.state == "off" and va.closed and va.listeners == []
    ctl.start(wait=True)
    assert len(made) == 2 and made[1].calls == [("mute", True)]      # muted stays muted across restarts
    ctl.close()
    assert made[1].closed


def test_controller_reports_what_is_missing(tmp_path):
    def check(settings):
        raise VoiceUnavailable("Ollama is not running at http://localhost:11434", "ollama serve")
    ctl = VoiceController(factory=lambda c, s: FakeAssistant(), check=check, path=tmp_path / "voice.json")
    ctl.start(wait=True)
    snap = ctl.snapshot()
    assert snap["state"] == "error" and "Ollama" in snap["error"] and snap["fix"] == "ollama serve"
    ctl.stop(wait=True)                                  # clears the error
    assert ctl.snapshot()["state"] == "off" and ctl.snapshot()["error"] == ""


def test_controller_settings_are_voice_json(tmp_path, monkeypatch):
    monkeypatch.setenv("MARVIN_CONFIG_DIR", str(tmp_path))
    voice_cli.save_settings({"llm_model": "qwen3:8b", "input_device": 2})
    ctl, made = controller(tmp_path, overrides={"llm_model": "llama3.2:3b", "reminders": False})
    assert ctl.app_settings()["llm_model"] == "llama3.2:3b"            # the command line wins...
    assert ctl.app_settings()["reminders"] is False
    ctl.start(wait=True)
    new = ctl.update_settings({"llm_model": "qwen3:14b", "language": "auto", "stt_model": "auto", "wake": False,
                               "follow_up_s": 3, "tts_voice": ""})
    ctl.wait(5)
    assert new["llm_model"] == "qwen3:14b" and new["language"] is None and new["wake"] is False
    assert new["reminders"] is False                                   # ...until the app changes that setting
    data = json.loads((tmp_path / "voice.json").read_text())
    assert data == {"llm_model": "qwen3:14b", "input_device": 2, "language": None, "stt_model": None,
                    "wake": False, "follow_up_s": 3.0, "tts_voice": None}
    assert len(made) == 2 and made[0].closed and made[1].config.llm_model == "qwen3:14b"   # restarted
    assert made[1].config.follow_up_s == 3.0 and made[1].config.wake is False
    # `marvin-host talk` reads the same defaults
    import argparse
    ap = argparse.ArgumentParser()
    voice_cli.add_cli(ap.add_subparsers(dest="cmd"))
    assert voice_cli._config(ap.parse_args(["talk"])).llm_model == "qwen3:14b"
    ctl.close()


@pytest.mark.parametrize("bad", [{"llm_model": "rm -rf /"}, {"stt": "cloud"}, {"tts": "alexa"}, {"language": "de"},
                                 {"wake": "yes"}, {"follow_up_s": 99}, {"nope": 1}, [1, 2]])
def test_voice_settings_validation(bad):
    with pytest.raises(ValueError):
        validate(bad)


def test_proactive_reminders_follow_the_settings(tmp_path):
    from marvin_host.brain import Brain
    from marvin_host.events import EventKind
    brain = Brain()
    ctl, made = controller(tmp_path)
    ctl.brain = brain
    ctl.start(wait=True)
    brain._emit(EventKind.STILL_LONG, 1_000_000, "", seated_s=3000.0)
    assert made[0].calls and made[0].calls[0][0] == "say"
    ctl.update_settings({"reminders": False})
    ctl.wait(5)
    brain._emit(EventKind.STILL_LONG, 2_000_000_000, "", seated_s=3000.0)
    assert not any(c[0] == "say" for c in made[1].calls)
    ctl.stop(wait=True)
    assert len(brain._listeners) == 0                    # nothing left behind


def test_preflight_names_the_missing_extra(monkeypatch):
    import importlib.util as iu
    from marvin_host.voice import control
    real = iu.find_spec
    monkeypatch.setattr(control.importlib.util, "find_spec",
                        lambda name, *a: None if name in ("sounddevice", "faster_whisper", "mlx_whisper") else real(name, *a))
    with pytest.raises(VoiceUnavailable) as e:
        control.preflight({})
    assert "sounddevice" in str(e.value) and ".[voice]" in e.value.fix


def test_ollama_models_and_options(monkeypatch):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from marvin_host.voice import control

    class Tags(BaseHTTPRequestHandler):
        def do_GET(self):
            body = json.dumps({"models": [{"name": "qwen3:4b-instruct"}, {"name": "gemma3:4b"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Tags)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        host = f"http://127.0.0.1:{srv.server_address[1]}"
        assert control.ollama_models(host) == ["gemma3:4b", "qwen3:4b-instruct"]
        o = control.options({"ollama_host": host})
        assert o["ollama"]["ok"] and o["llm_models"] == ["gemma3:4b", "qwen3:4b-instruct"]
        assert {b["name"] for b in o["tts_backends"]} == {"auto", "say", "piper", "espeak"}
        assert "turbo" in o["stt_models"] and o["languages"] == ["auto", "fr", "en"]
    finally:
        srv.shutdown()
        srv.server_close()
    o = control.options({"ollama_host": "http://127.0.0.1:9"})
    assert not o["ollama"]["ok"] and "ollama serve" in o["ollama"]["fix"] and o["llm_models"] == []


def test_live_signals_pass_through_and_ids_survive_a_restart(tmp_path):
    from marvin_host.voice.control import LIVE_KINDS
    a = VoiceController(check=None, path=tmp_path / "voice.json")
    got = []
    a.add_listener(lambda kind, payload: got.append(kind))
    for kind in LIVE_KINDS:
        a._on_voice(kind, {"t": 0.0})
    a._on_voice("heard", {"t": 0.0, "text": "bonjour"})
    assert got == [*LIVE_KINDS, "transcript"]
    assert [e["kind"] for e in a.recent()] == ["heard"]  # live signals are not kept
    time.sleep(0.01)
    b = VoiceController(check=None, path=tmp_path / "voice.json")   # marvin-host again: an open page
    b._on_voice("heard", {"t": 0.0, "text": "encore"})    # must not take this for an entry it has
    assert b.recent()[0]["id"] > a.recent()[0]["id"]
