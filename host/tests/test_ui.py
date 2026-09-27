"""Marvin's app: daily statistics, the event store, and the HTTP API against a live server.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import http.client
import json
import os
import stat
import struct
import time
from datetime import date, datetime, timedelta

import pytest

from marvin_host.brain import Brain
from marvin_host.events import Event, EventKind as K
from marvin_host.ui import EventStore, UIServer, stats
from marvin_host.ui import server as ui_server
from marvin_host.ui.demo import DemoRobot, seed_history, step
from marvin_host.ui.stats import StoredEvent as E

DAY = date(2026, 3, 10)                         # a Tuesday, far from DST changes


def at(hhmm: str, day: date = DAY) -> float:
    h, m = hhmm.split(":")[:2]
    s = int(hhmm.split(":")[2]) if hhmm.count(":") == 2 else 0
    return datetime(day.year, day.month, day.day, int(h), int(m), s).timestamp()


def ev(kind, hhmm, day=DAY, **data) -> E:
    k = kind.value if isinstance(kind, K) else kind
    return E(at(hhmm, day), k, "", data)


# ================================================================================ statistics

def test_a_working_morning():
    events = [
        ev(K.ARRIVED, "09:00"), ev(K.SAT_DOWN, "09:05"), ev(K.STOOD_UP, "10:05"),
        ev(K.SAT_DOWN, "10:05:30"),                   # stood up for 30 s: same session
        ev(K.STOOD_UP, "11:00"), ev(K.SAT_DOWN, "11:10"), ev(K.STILL_LONG, "12:00"),
        ev(K.VITALS_ACQUIRED, "11:12"), ev(K.STOOD_UP, "12:30"), ev(K.LEFT, "12:31"),
    ]
    d = stats.day_stats(events, DAY, now=at("20:00"))
    assert d["date"] == "2026-03-10"
    assert d["seated_s"] == 3600 + 3270 + 4800
    assert d["present_s"] == 3.5 * 3600 + 60
    assert d["sessions"] == 2 and d["breaks"] == 1 and d["break_s"] == 600
    assert d["longest_s"] == 115 * 60
    assert d["reminders"] == 1 and d["timeline"]["reminders"] == [at("12:00")]
    assert d["first_arrival"] == at("09:00") and d["last_departure"] == at("12:31")
    assert d["now"] == at("20:00")
    assert len(d["timeline"]["seated"]) == 3 and d["timeline"]["breaks"] == [[at("11:00"), at("11:10")]]


def test_still_here_now():
    events = [ev(K.ARRIVED, "14:00"), ev(K.SAT_DOWN, "14:02")]
    d = stats.day_stats(events, DAY, now=at("15:02"))
    assert d["seated_s"] == 3600 and d["present_s"] == 3720
    assert d["last_departure"] is None and d["now"] == at("15:02")
    # the same history read while the host is not running: closed at the last sign of life
    d = stats.day_stats(events, DAY, now=at("15:02"), live=False, samples=[(at("14:30"), None, None)])
    assert d["seated_s"] == 28 * 60 and d["last_departure"] == at("14:30")


def test_across_midnight():
    nxt = DAY + timedelta(days=1)
    events = [ev(K.ARRIVED, "23:00"), ev(K.SAT_DOWN, "23:30"), ev(K.STOOD_UP, "00:30", nxt), ev(K.LEFT, "00:40", nxt)]
    d1 = stats.day_stats(events, DAY, now=at("12:00", nxt))
    d2 = stats.day_stats(events, nxt, now=at("12:00", nxt))
    assert d1["seated_s"] == 1800 and d2["seated_s"] == 1800
    assert d1["present_s"] == 3600 and d2["present_s"] == 2400
    assert d1["last_departure"] == at("00:00", nxt)   # cut at midnight
    assert d2["first_arrival"] == at("00:00", nxt)    # was already there at midnight
    assert d1["sessions"] == d2["sessions"] == 1


def test_a_crash_closes_at_the_last_sign_of_life():
    events = [ev(K.ARRIVED, "09:00"), ev(K.SAT_DOWN, "09:05"),
              ev(stats.HOST_STARTED, "11:00"),          # no STOOD_UP, no host_stopped: it crashed
              ev(K.ARRIVED, "11:01"), ev(K.LEFT, "11:02")]
    samples = [(at("09:20"), 14.0, 60.0), (at("09:40"), 16.0, 70.0)]
    d = stats.day_stats(events, DAY, now=at("18:00"), samples=samples)
    assert d["seated_s"] == 35 * 60
    assert d["present_s"] == 40 * 60 + 60
    assert d["breath_rate"] == 15.0 and d["heart_rate"] == 65.0
    # without samples: closed at the last event before the restart
    d = stats.day_stats(events, DAY, now=at("18:00"))
    assert d["seated_s"] == 0 and d["present_s"] == 5 * 60 + 60


def test_clean_stop_and_robot_offline():
    events = [ev(K.ARRIVED, "09:00"), ev(K.SAT_DOWN, "09:00"),
              ev(stats.ROBOT_OFFLINE, "09:30"),
              ev(stats.ROBOT_ONLINE, "10:00", present=True, seated=True),   # back, still seated
              ev(K.STOOD_UP, "10:30"), ev(stats.HOST_STOPPED, "11:00"),
              ev(stats.HOST_STARTED, "12:00")]
    d = stats.day_stats(events, DAY, now=at("13:00"))
    assert d["seated_s"] == 3600 and d["present_s"] == 90 * 60
    assert d["sessions"] == 2 and d["breaks"] == 1    # the offline half hour is a gap


def test_a_seated_person_is_present_even_if_arrived_was_missed():
    d = stats.day_stats([ev(K.SAT_DOWN, "09:00"), ev(K.LEFT, "09:10")], DAY, now=at("12:00"))
    assert d["seated_s"] == d["present_s"] == 600


def test_an_empty_day():
    d = stats.day_stats([], DAY, now=at("12:00"))
    assert d["seated_s"] == 0 and d["sessions"] == 0 and d["first_arrival"] is None
    assert d["breath_rate"] is None and d["timeline"]["present"] == []


def test_words():
    assert stats.duration(42) == "42 s" and stats.duration(12 * 60) == "12 min"
    assert stats.duration(65 * 60) == "1 h 05" and stats.duration(3 * 3600) == "3 h"
    assert stats.describe(E(0, "stood_up", "", {"seated_s": 2520})) == "You stood up after 42 min"
    assert stats.describe(E(0, "still_long", "", {"seated_s": 3000})) == "Time for a break: seated for 50 min"
    assert stats.describe(E(0, "vitals_acquired", "", {"breath_rate": 14.2, "heart_rate": 68})) == \
        "Breathing 14/min, heart 68/min"
    assert stats.describe(E(0, "left", "sensor restarted")).startswith("Marvin lost track")
    assert stats.describe(E(0, "something_new", "")) == "Something new"

    st = stats.status(True, True, True, 42 * 60, None, 14.0, 68.0, 3000)
    assert st["text"] == "You've been at your desk for 42 min" and st["detail"] == "Breathing 14/min, heart 68/min"
    assert stats.status(True, True, True, 55 * 60, None, None, None, 3000)["mood"] == "break"
    assert stats.status(True, False, False, 0, 600, None, None, 3000)["text"] == "Marvin is asleep. Nobody around."
    assert stats.status(False, False, False, 0, None, None, None, 3000, ever_online=False)["mood"] == "offline"


# ================================================================================ store

def test_store_round_trip(tmp_path):
    path = tmp_path / "marvin.db"
    db = EventStore(path)
    db.add_event(Event(K.ARRIVED, 1_000_000, "1.80 m away", {"distance_m": 1.8}), at("09:00"))
    db.add_event(Event(K.SAT_DOWN, 2_000_000, "0.86 m away", {"distance_m": 0.86}), at("09:01"))
    db.add_event(Event(K.STOOD_UP, 3_000_000, "after 59 min seated", {"seated_s": 3540.0}), at("10:00"))
    db.add_sample(at("09:30"), 1.0, 1.0, 14.0, 66.0)
    db.set_settings({"break_interval_min": 30, "clock": "12h"})
    before = db.day(DAY, now=at("18:00"))
    db.close()

    db = EventStore(path)                           # after a restart
    assert db.day(DAY, now=at("18:00")) == before
    assert before["seated_s"] == 59 * 60 and before["breath_rate"] == 14.0
    assert db.get_settings() == {"break_interval_min": 30, "clock": "12h"}
    rec = db.recent(10)
    assert [e.kind for e in rec] == ["stood_up", "sat_down", "arrived"]
    assert rec[0].data == {"seated_s": 3540.0} and rec[0].to_json()["text"] == "You stood up after 59 min"
    assert [e.kind for e in db.recent(10, since_id=rec[1].id)] == ["stood_up"]
    hist = db.history(DAY, 3, now=at("18:00"))
    assert [h["date"] for h in hist] == ["2026-03-08", "2026-03-09", "2026-03-10"]
    assert hist[-1]["seated_s"] == 59 * 60 and hist[0]["seated_s"] == 0
    db.close()


def test_data_dir_from_environment(tmp_path, monkeypatch):
    monkeypatch.setenv("MARVIN_DATA_DIR", str(tmp_path / "here"))
    db = EventStore()
    assert db.path == str(tmp_path / "here" / "marvin.db") and os.path.exists(db.path)
    db.close()


def test_demo_history_is_consistent(tmp_path):
    db = EventStore(":memory:")
    now = at("16:00")
    seed_history(db, now)
    days = db.history(DAY, 7, now)
    assert any(d["seated_s"] > 3 * 3600 for d in days)
    for d in days:
        assert 0 <= d["seated_s"] <= d["present_s"] <= 24 * 3600
    assert not db.events(now - 299, now + 1e6)      # nothing in the future, nor in the last minutes


# ================================================================================ HTTP

@pytest.fixture
def app(tmp_path):
    brain = Brain()
    store = EventStore(tmp_path / "marvin.db")
    srv = UIServer(brain, host="127.0.0.1", port=0, store=store, token="sekret",
                   sample_period_s=0.05, offline_after_s=0.5, quiet=True).start()
    yield srv
    srv.stop()
    store.close()


def request(srv, method, path, body=None, headers=None):
    c = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=5)
    h = {"Host": f"127.0.0.1:{srv.port}", **(headers or {})}
    c.request(method, path, body=body, headers=h)
    r = c.getresponse()
    data = r.read()
    c.close()
    return r, data


def get_json(srv, path, **kw):
    r, data = request(srv, "GET", path, **kw)
    return r.status, json.loads(data)


def feed(brain, t0, t1):
    for i in range(round(t0 / 0.1), round(t1 / 0.1)):
        step(brain, i * 0.1, i * 0.1)


def test_state_schema(app):
    status, body = get_json(app, "/api/state")
    assert status == 200 and set(body) == {"state", "today", "settings"}
    s = body["state"]
    for key in ("online", "present", "seated", "seated_s", "breath_rate", "heart_rate", "status", "detail",
                "mood", "quiet", "break"):
        assert key in s
    assert s["online"] is False and s["status"] == "Waiting for Marvin to connect"
    assert set(s["break"]) == {"interval_s", "seated_s", "progress", "due"}
    assert body["today"]["date"] == stats.local_day(time.time()).isoformat()
    assert body["settings"]["break_interval_min"] == 50

    feed(app.brain, 0, 40)                          # seated, vital signs readable
    time.sleep(0.2)
    s = get_json(app, "/api/state")[1]["state"]
    assert s["online"] and s["present"] and s["seated"] and s["breath_rate"] is not None
    assert s["status"].startswith("You") and s["detail"].startswith("Breathing")

    ev = get_json(app, "/api/events")[1]["events"]
    kinds = [e["kind"] for e in ev]
    assert kinds[-1] == "host_started" and "arrived" in kinds and "sat_down" in kinds
    assert "vitals_acquired" in kinds
    quiet = [e["kind"] for e in get_json(app, "/api/events?quiet=1")[1]["events"]]
    assert "vitals_acquired" not in quiet
    newest = ev[0]["id"]
    assert get_json(app, f"/api/events?since={newest}")[1]["events"] == []


def test_day_and_history(app):
    today = stats.local_day(time.time())
    status, d = get_json(app, f"/api/day?date={today.isoformat()}")
    assert status == 200 and d["date"] == today.isoformat()
    assert get_json(app, "/api/day?date=yesterday")[0] == 400
    status, h = get_json(app, "/api/history?days=5")
    assert status == 200 and len(h["days"]) == 5 and h["days"][-1]["date"] == today.isoformat()


def test_sse_delivers_state_and_events(app):
    c = http.client.HTTPConnection("127.0.0.1", app.port, timeout=5)
    c.request("GET", "/api/stream", headers={"Host": f"127.0.0.1:{app.port}"})
    r = c.getresponse()
    assert r.status == 200 and r.getheader("Content-Type") == "text/event-stream"

    def read_until(kind, pred=lambda d: True):
        cur = None
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            line = r.fp.readline().decode().rstrip("\n")
            if line.startswith("event: "):
                cur = line[7:]
            elif line.startswith("data: ") and cur == kind:
                data = json.loads(line[6:])
                if pred(data):
                    return data
        raise AssertionError(f"no {kind} message")

    assert "online" in read_until("state")
    feed(app.brain, 0, 10)                          # someone walks in
    e = read_until("event", lambda d: d["kind"] == "arrived")
    assert e["text"] == "You came in" and e["ts"] > 0
    assert read_until("today")["present_s"] >= 0
    c.close()


def test_face_png(app):
    r, data = request(app, "GET", "/face.png")
    assert r.status == 200 and r.getheader("Content-Type") == "image/png"
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h = struct.unpack(">II", data[16:24])
    assert (w, h) == (240, 280)
    r, data = request(app, "GET", "/icon.png")
    assert r.status == 200 and struct.unpack(">II", data[16:24]) == (240, 240)


def test_face_disabled_is_a_clean_503(tmp_path):
    srv = UIServer(Brain(), host="127.0.0.1", port=0, store=EventStore(":memory:"), token=None,
                   face=False, quiet=True).start()
    try:
        r, data = request(srv, "GET", "/face.png")
        assert r.status == 503 and "error" in json.loads(data)
    finally:
        srv.stop()


def test_settings_round_trip(app):
    hdr = {"Content-Type": "application/json"}
    r, data = request(app, "POST", "/api/settings", json.dumps({"break_interval_min": 25, "clock": "12h",
                      "quiet_hours": {"enabled": True, "start": "21:30"}}), hdr)
    assert r.status == 200
    s = get_json(app, "/api/settings")[1]["settings"]
    assert s["break_interval_min"] == 25 and s["clock"] == "12h"
    assert s["quiet_hours"] == {"enabled": True, "start": "21:30", "end": "07:00"}
    assert app.brain.config.still_long_s == 25 * 60      # the brain's break reminder follows
    assert app.store.get_settings()["break_interval_min"] == 25

    for bad in ({"break_interval_min": 0}, {"clock": "13h"}, {"nope": 1},
                {"quiet_hours": {"start": "25:00"}}, {"voice": "yes"}):
        r, data = request(app, "POST", "/api/settings", json.dumps(bad), hdr)
        assert r.status == 400, bad
    r, _ = request(app, "POST", "/api/settings", "break_interval_min=5",
                   {"Content-Type": "application/x-www-form-urlencoded"})
    assert r.status == 415
    r, _ = request(app, "POST", "/api/settings", "{}", {**hdr, "Origin": "http://evil.example"})
    assert r.status == 403
    assert get_json(app, "/api/settings")[1]["settings"]["break_interval_min"] == 25


def test_settings_survive_a_restart(tmp_path):
    store = EventStore(tmp_path / "m.db")
    store.set_settings({"break_interval_min": 40, "legacy_key": True})    # unknown keys are ignored
    brain = Brain()
    srv = UIServer(brain, host="127.0.0.1", port=0, store=store, quiet=True)
    assert srv.settings["break_interval_min"] == 40 and brain.config.still_long_s == 2400
    srv._httpd.server_close()


def test_access_key(app, monkeypatch):
    # from this computer: no key needed, but the Host must be ours (DNS rebinding)
    assert get_json(app, "/api/state")[0] == 200
    r, _ = request(app, "GET", "/api/state", headers={"Host": "evil.example"})
    assert r.status == 403

    # from another device
    monkeypatch.setattr(ui_server._Handler, "_client_is_local", lambda self: False)
    r, data = request(app, "GET", "/api/state")
    assert r.status == 401 and json.loads(data)["error"]
    r, data = request(app, "GET", "/")
    assert r.status == 401 and b"access key" in data
    assert request(app, "GET", "/api/state?token=wrong")[0].status == 401
    assert request(app, "GET", "/?token=wrong")[0].status == 401
    assert request(app, "GET", "/static/style.css")[0].status == 200       # public, no data in it

    r, _ = request(app, "GET", "/?token=sekret")
    assert r.status == 303 and r.getheader("Location") == "/"
    cookie = r.getheader("Set-Cookie")
    assert cookie.startswith("marvin_key=sekret;") and "HttpOnly" in cookie and "SameSite=Strict" in cookie
    assert request(app, "GET", "/api/state", headers={"Cookie": "marvin_key=sekret"})[0].status == 200
    assert request(app, "GET", "/", headers={"Cookie": "marvin_key=sekret"})[0].status == 200
    assert request(app, "GET", "/api/state", headers={"Authorization": "Bearer sekret"})[0].status == 200
    assert request(app, "GET", "/api/state", headers={"Host": "evil.example", "Cookie": "marvin_key=sekret"})[0].status == 200
    assert "token=sekret" in (app.urls()["lan"] or "token=sekret")


def test_no_key_means_open_to_the_network(tmp_path, monkeypatch):
    srv = UIServer(Brain(), host="127.0.0.1", port=0, store=EventStore(":memory:"), token=None, quiet=True).start()
    try:
        monkeypatch.setattr(ui_server._Handler, "_client_is_local", lambda self: False)
        assert request(srv, "GET", "/api/state")[0].status == 200
        assert request(srv, "GET", "/api/state", headers={"Host": "evil.example"})[0].status == 403
    finally:
        srv.stop()


def test_auto_key_is_kept_next_to_the_database(tmp_path):
    store = EventStore(tmp_path / "marvin.db")
    a = UIServer(Brain(), host="127.0.0.1", port=0, store=store, quiet=True)
    a._httpd.server_close()
    key = tmp_path / "ui_token"
    assert key.read_text().strip() == a.token and len(a.token) >= 16
    assert stat.S_IMODE(key.stat().st_mode) == 0o600
    b = UIServer(Brain(), host="127.0.0.1", port=0, store=store, quiet=True)
    b._httpd.server_close()
    assert b.token == a.token


def test_lifecycle_and_offline_detection(app):
    feed(app.brain, 0, 5)                           # frames: online
    time.sleep(0.2)
    assert app.online
    time.sleep(0.9)                                 # no frame for longer than offline_after_s
    assert not app.online
    kinds = [e.kind for e in app.store.recent(20)]
    assert kinds[0] == stats.ROBOT_OFFLINE and stats.ROBOT_ONLINE in kinds
    app.stop()
    assert app.store.recent(1)[0].kind == stats.HOST_STOPPED


def test_demo_robot_drives_the_brain():
    brain = Brain()
    robot = DemoRobot(brain, speed=1.0, visits=[(0.0, 0.5)], wall0=1000.0)
    times = robot.scene_times()
    for i in range(int(35 / 0.1)):                  # 35 s of demo time: walks in and sits down
        step(brain, next(times), i * 0.1)
    assert brain.state.present and brain.state.seated
    assert [e.kind for e in brain.events][:2] == [K.ARRIVED, K.SAT_DOWN]
    assert abs(robot.clock() - 1000.0) < 5
