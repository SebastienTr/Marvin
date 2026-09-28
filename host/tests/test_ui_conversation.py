"""The conversation kept with the history: the SQLite table and its migration, the controller
bringing it back after a restart, the API for past days and search, and what the app shows with
each answer (the context the model was given, the raw transcript, how loud an ignored sound was),
the simulated-sensors flag and the browser sounds setting.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import http.client
import itertools
import json
import sqlite3
import time
from datetime import timedelta

from marvin_host.brain import Brain
from marvin_host.ui import EventStore, UIServer, stats
from marvin_host.ui.demo import DemoVoice, seed_conversations
from marvin_host.voice.control import VoiceController
from test_ui import get_json, request
from test_voice import make, said
from test_voice_control import FakeAssistant

JSON = {"Content-Type": "application/json"}


# ================================================================================ store

V1_SCHEMA = """
CREATE TABLE events (id INTEGER PRIMARY KEY, ts REAL NOT NULL, kind TEXT NOT NULL,
                     detail TEXT NOT NULL DEFAULT '', data TEXT NOT NULL DEFAULT '{}', t_us INTEGER);
CREATE INDEX events_ts ON events(ts);
CREATE TABLE samples (ts REAL PRIMARY KEY, present REAL NOT NULL, seated REAL NOT NULL, breath REAL, heart REAL);
CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
PRAGMA user_version = 1;
"""


def test_an_existing_database_is_upgraded_in_place(tmp_path):
    path = tmp_path / "marvin.db"
    db = sqlite3.connect(path)
    db.executescript(V1_SCHEMA)
    db.execute("INSERT INTO events (ts, kind, detail, data) VALUES (1000.0, 'arrived', '1.80 m away', '{}')")
    db.execute("INSERT INTO settings VALUES ('clock', '\"12h\"')")
    db.commit()
    db.close()

    store = EventStore(path)
    assert [e.kind for e in store.recent(10)] == ["arrived"]       # nothing lost
    assert store.get_settings() == {"clock": "12h"}
    store.add_conversation({"id": 5, "t": 1001.0, "kind": "heard", "text": "Bonjour", "source": "voice"})
    store.close()

    store = EventStore(path)                                       # and again: nothing changes
    assert store._db.execute("PRAGMA user_version").fetchone()[0] == 2
    assert store.conversation(0, 2000) == [{"id": 5, "t": 1001.0, "kind": "heard", "text": "Bonjour",
                                            "source": "voice"}]
    store.close()


def test_conversation_round_trip_and_search(tmp_path):
    store = EventStore(tmp_path / "marvin.db")
    day = stats.local_day(time.time())
    lo, _ = stats.day_bounds(day)
    t = lo + 10 * 3600
    store.add_conversation({"id": 1, "t": t, "kind": "heard", "text": "What's 100% of my break_time?"})
    store.add_conversation({"id": 2, "t": t + 1, "kind": "reply", "text": "All of it.", "latency": {"stt": 0.1},
                            "context": "Context:\n- It is noon.", "prompt": "Context:\n...", "model": "m"})
    store.add_conversation({"id": 3, "t": t + 2, "kind": "ignored", "text": "100%", "reason": "known hallucination",
                            "dbfs": -40.5})
    store.add_conversation({"id": 4, "t": t - 86400, "kind": "heard", "text": "Yesterday's question"})
    got = store.conversation_day(day)
    assert [e["id"] for e in got] == [1, 2, 3]
    assert got[1]["latency"] == {"stt": 0.1} and got[1]["context"].startswith("Context:")
    assert got[2]["dbfs"] == -40.5
    assert [e["id"] for e in store.conversation_day(day - timedelta(days=1))] == [4]
    # plain substring, % and _ are not wildcards, ignored lines are not searched, newest first
    assert [e["id"] for e in store.search_conversation("100%")] == [1]
    assert store.search_conversation("break_") and not store.search_conversation("break%")
    assert [e["id"] for e in store.search_conversation("QUESTION")] == [4]
    assert [e["id"] for e in store.search_conversation("i")] == [2, 1, 4]
    assert store.search_conversation("  ") == []
    assert store.max_conversation_id() == 4
    store.close()


# ================================================================================ controller

def controller(tmp_path, store=None):
    made = []

    def factory(config, settings):
        made.append(FakeAssistant(config, settings))
        return made[-1]
    ctl = VoiceController(factory=factory, check=None, path=tmp_path / "voice.json")
    if store is not None:
        ctl.attach_store(store)
    return ctl, made


def test_the_conversation_survives_a_restart(tmp_path):
    store = EventStore(tmp_path / "marvin.db")
    ctl, made = controller(tmp_path, store)
    ctl.start(wait=True)
    va = made[0]
    va.emit("heard", text="quelle heure est-il ?", raw="Marvin, quelle heure est-il ?", language="fr", source="voice")
    va.emit("reply", text="Midi.", language="fr", latency={"endpoint": 0.5, "audio_start": 0.7}, interrupted=False,
            proactive=False, error=None, hint="", context="Context:\n- It is noon.",
            prompt="Context:\n- It is noon.\n\nThe person says: quelle heure est-il ?", model="qwen3:4b-instruct")
    va.emit("ignored", text="", reason="too quiet (-52 dBFS)", dbfs=-52.4)
    ctl.close()
    before = ctl.recent()
    assert [e["kind"] for e in before] == ["note", "heard", "reply", "ignored"]
    assert before[1]["raw"] == "Marvin, quelle heure est-il ?"
    assert before[2]["context"] == "Context:\n- It is noon." and before[2]["model"] == "qwen3:4b-instruct"
    assert before[2]["prompt"].endswith("quelle heure est-il ?") and before[3]["dbfs"] == -52.4

    # marvin-host again, with a clock that went backwards: the ids still grow
    store.add_conversation({**before[-1], "id": before[-1]["id"] + 10_000_000})
    again, _ = controller(tmp_path)
    again._ids = itertools.count(1)
    again.attach_store(store)
    back = again.recent()
    assert [e["id"] for e in back[:4]] == [e["id"] for e in before]
    assert back[2] == before[2]
    again._note("Voice on")
    assert again.recent()[-1]["id"] > before[-1]["id"] + 10_000_000
    assert store.conversation_day(stats.local_day(time.time()))[-1]["text"] == "Voice on"
    store.close()


def test_only_todays_entries_come_back(tmp_path):
    store = EventStore(tmp_path / "marvin.db")
    lo, _ = stats.day_bounds(stats.local_day(time.time()))
    store.add_conversation({"id": 1, "t": lo - 3600, "kind": "heard", "text": "last night"})
    for i in range(5):
        store.add_conversation({"id": 10 + i, "t": lo + 60 * i, "kind": "heard", "text": f"today {i}"})
    ctl, _ = controller(tmp_path)
    ctl.attach_store(store, limit=3)
    assert [e["text"] for e in ctl.recent()] == ["today 2", "today 3", "today 4"]
    store.close()


# ================================================================================ assistant

def test_answers_carry_what_the_model_was_given():
    events = []
    va, t = make([("quiet", 0.5), ("say", 1.5), ("quiet", 1), ("idle",)],
                 ["Marvin, quelle heure est-il ?"], ["Il est midi."], brain=Brain())
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    heard = next(d for k, d in events if k == "heard")
    assert heard["text"] == "quelle heure est-il ?" and heard["raw"] == "Marvin, quelle heure est-il ?"
    reply = next(d for k, d in events if k == "reply")
    assert reply["context"].startswith("Context:\n- It is ") and "radar sees nobody" in reply["context"]
    assert reply["prompt"] == t.llm.calls[0][-1]["content"]              # exactly what was sent
    assert reply["prompt"].startswith(reply["context"]) and said({"role": "user", "content": reply["prompt"]}) \
        == "quelle heure est-il ?"
    assert reply["model"]


def test_typed_questions_have_no_raw_transcript():
    events = []
    box = {}
    va, t = make([("quiet", 0.3), ("call", lambda: box["va"].ask("What time is it?")), ("idle",), ("quiet", 0.2)],
                 [], ["Noon."])
    box["va"] = va
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    assert "raw" not in next(d for k, d in events if k == "heard")


def test_ignored_utterances_say_how_loud_they_were():
    events = []
    va, t = make([("say", 1.0), ("quiet", 1), ("idle",)], ["Thanks for watching!"], wake=False)
    va.add_listener(lambda kind, data: events.append((kind, data)))
    va.run()
    ignored = [d for k, d in events if k == "ignored"]
    assert ignored and -60 < ignored[0]["dbfs"] < 0


# ================================================================================ HTTP

def server(tmp_path, voice=None):
    brain = Brain()
    store = EventStore(tmp_path / "marvin.db")
    srv = UIServer(brain, host="127.0.0.1", port=0, store=store, token=None, quiet=True, voice=voice).start()
    return srv, store


def test_conversation_api(tmp_path):
    ctl, made = controller(tmp_path)
    srv, store = server(tmp_path, ctl)
    try:
        assert ctl.store is store                                  # the app keeps the conversation
        ctl.start(wait=True)
        made[0].emit("heard", text="Is the kettle on?", language="en", source="voice")
        made[0].emit("reply", text="I have no kettle sensor.", language="en", latency={}, interrupted=False,
                     proactive=False, error=None, hint="")
        now = time.time()
        seed_conversations(store, now, days=3)
        status, body = get_json(srv, "/api/conversation")
        assert status == 200 and body["day"] == stats.local_day(now).isoformat()
        assert [e["kind"] for e in body["entries"]] == ["note", "heard", "reply"]
        workday = stats.local_day(now) - timedelta(days=1)          # the demo talks on working days
        while workday.weekday() >= 5:
            workday -= timedelta(days=1)
        status, body = get_json(srv, f"/api/conversation?day={workday.isoformat()}")
        assert status == 200 and body["entries"][0]["kind"] == "note" and len(body["entries"]) >= 4
        replies = [e for e in body["entries"] if e["kind"] == "reply" and not e["proactive"]]
        assert replies and replies[0]["context"].startswith("Context:") and replies[0]["prompt"]
        status, body = get_json(srv, "/api/conversation?q=kettle")
        assert status == 200 and [e["text"] for e in body["results"]] == ["I have no kettle sensor.",
                                                                          "Is the kettle on?"]
        assert get_json(srv, "/api/conversation?q=%25%25")[1]["results"] == []
        assert get_json(srv, "/api/conversation?day=tomorrow")[0] == 400
        assert len(get_json(srv, "/api/conversation?q=a&limit=5000")[1]["results"]) <= 100
    finally:
        srv.stop()
        ctl.close()
        store.close()


def test_state_says_when_the_sensors_are_simulated(tmp_path):
    srv, store = server(tmp_path)
    try:
        assert get_json(srv, "/api/state")[1]["state"]["simulated"] is False
        srv.brain.state.simulated = True
        state = get_json(srv, "/api/state")[1]["state"]
        assert state["simulated"] is True and state["vitals_sensor"] is False
        # the live stream sends the same snapshot
        c = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=5)
        c.request("GET", "/api/stream", headers={"Host": f"127.0.0.1:{srv.port}"})
        r = c.getresponse()
        cur = None
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            line = r.fp.readline().decode().rstrip("\n")
            if line.startswith("event: "):
                cur = line[7:]
            elif line.startswith("data: ") and cur == "state":
                assert json.loads(line[6:])["simulated"] is True
                break
        else:
            raise AssertionError("no state message")
        c.close()
    finally:
        srv.stop()
        store.close()


def test_ui_sounds_setting(tmp_path):
    srv, store = server(tmp_path)
    try:
        assert get_json(srv, "/api/settings")[1]["settings"]["ui_sounds"] is True     # on by default
        r, _ = request(srv, "POST", "/api/settings", json.dumps({"ui_sounds": False}), JSON)
        assert r.status == 200 and store.get_settings()["ui_sounds"] is False
        for bad in ("yes", 1, None):
            r, _ = request(srv, "POST", "/api/settings", json.dumps({"ui_sounds": bad}), JSON)
            assert r.status == 400, bad
        assert get_json(srv, "/api/settings")[1]["settings"]["ui_sounds"] is False
    finally:
        srv.stop()
        store.close()


# ================================================================================ demo

def test_demo_voice_explains_its_answers(tmp_path):
    brain = Brain()
    ctl = VoiceController(brain, factory=lambda config, settings: DemoVoice(brain), check=None,
                          path=tmp_path / "voice.json")
    ctl.start(wait=True)
    try:
        entries = ctl.recent()
        replies = [e for e in entries if e["kind"] == "reply" and not e["proactive"]]
        assert replies and all(e["context"].startswith("Context:") and e["model"] for e in replies)
        kinds = [e["kind"] for e in entries]
        assert "ignored, ignored" in ", ".join(kinds)                   # something to group in the app
        heard = [e for e in entries if e["kind"] == "heard"]
        assert heard[0]["raw"].startswith("Marvin")
    finally:
        ctl.close()
