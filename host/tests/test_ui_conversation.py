"""The conversation kept with the history: the SQLite table and its migration, the API for past days
and search, the demo's conversation, what the voice reports with what it hears (the raw transcript,
how loud an ignored sound was), the simulated-sensors flag and the browser sounds setting.

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
from marvin_host.ui.demo import SCRIPT, seed_conversations, seed_today
from test_ui import get_json, request
from voicekit import make

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


# ================================================================================ the voice


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

def server(tmp_path):
    brain = Brain()
    store = EventStore(tmp_path / "marvin.db")
    srv = UIServer(brain, host="127.0.0.1", port=0, store=store, token=None, quiet=True).start()
    return srv, store


def test_conversation_api(tmp_path):
    srv, store = server(tmp_path)
    try:
        now = time.time()
        store.add_conversation({"id": 1, "t": now - 2, "kind": "heard", "text": "Is the kettle on?", "language": "en",
                                "source": "voice"})
        store.add_conversation({"id": 2, "t": now - 1, "kind": "reply", "text": "I have no kettle sensor.",
                                "language": "en", "latency": {}, "interrupted": False, "proactive": False})
        seed_conversations(store, now, days=3)
        status, body = get_json(srv, "/api/conversation")
        assert status == 200 and body["day"] == stats.local_day(now).isoformat()
        assert [e["kind"] for e in body["entries"]] == ["heard", "reply"]
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


# ================================================================================ demo

def test_the_demo_explains_its_answers(tmp_path):
    store = EventStore(tmp_path / "marvin.db")
    try:
        now = time.time()
        seed_today(store, now)
        entries = store.conversation(now - 3600, now + 1)
        assert len(entries) == len(SCRIPT)
        replies = [e for e in entries if e["kind"] == "reply" and not e["proactive"]]
        assert replies and all(e["context"].startswith("Context:") and e["model"] for e in replies)
        assert all(e["first_word_s"] for e in replies)
        kinds = [e["kind"] for e in entries]
        assert "ignored, ignored" in ", ".join(kinds)                   # something to group in the app
        heard = [e for e in entries if e["kind"] == "heard"]
        assert heard[0]["raw"].startswith("Marvin")
    finally:
        store.close()
