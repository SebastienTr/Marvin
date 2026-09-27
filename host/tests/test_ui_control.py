"""The app as Marvin's control center: the robot's devices and sensors (UISink), the log, the
conversation and the voice's controls over HTTP and SSE, and the quiet terminal of `marvin-host run`.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import http.client
import json
import logging
import socket
import threading
import time

import numpy as np
import pytest

from marvin_host import cli, ld2450, protocol
from marvin_host.brain import Brain
from marvin_host.receiver import Device
from marvin_host.ui import EventStore, UIServer
from marvin_host.ui import server as ui_server
from marvin_host.ui.sink import UISink, reduce_scan, rssi_bars
from marvin_host.voice.control import VoiceController
from test_ui import feed, get_json, request
from test_voice_control import FakeAssistant

JSON = {"Content-Type": "application/json"}


class Clock:
    def __init__(self, t=100.0):
        self.t = t

    def __call__(self):
        return self.t


def robot(board=3, flags=protocol.FLAG_SIMULATED | protocol.FLAG_AUDIO, rssi=-61, ip="192.168.1.42"):
    return Device(protocol.Hello(bytes.fromhex("024d56a1b2c3"), board, flags, rssi, 5000, "0.6.0"), (ip, 47101))


# ================================================================================ UISink

def test_reduce_scan_and_signal_bars():
    pts = np.array([[0, -1000, 133], [-500, 0, 133], [0, 2000, 133], [0, -1200, 133]], dtype=np.float32)
    r = reduce_scan(pts)
    assert len(r) == 360
    assert r[0] == 100                   # straight ahead (-Y), the nearest of two returns
    assert r[90] == 50                   # to the robot's right (-X): clockwise, like the lidar
    assert r[180] == 200                 # behind
    assert sum(1 for v in r if v) == 3
    assert reduce_scan(np.zeros((0, 3))) == [0] * 360
    assert [rssi_bars(x) for x in (-50, -60, -70, -80, -90, None)] == [4, 3, 2, 1, 0, None]


def test_ui_sink_aggregates_cheaply_and_notices_links():
    clock, wall = Clock(), Clock(1_700_000_000.0)
    sink = UISink(clock=clock, wall=wall, offline_after_s=6.0)
    notes = []
    sink.add_listener(lambda kind, info: notes.append((kind, info)))
    dev = robot()
    radar = Device(protocol.Hello(bytes.fromhex("024d56c6b0a2"), 4, 0, -70, 1000, "mr60-0.2"), ("192.168.1.57", 47101))
    sink.on_hello(dev)
    sink.on_hello(radar)
    sink.on_log(dev, 0, "firmware up")
    pts = np.array([[0, -1000, 133]] * 450, dtype=np.float32)
    for i in range(50):                  # 5 s of frames at 10 Hz
        clock.t += 0.1
        dev.stats.datagrams += 5
        dev.stats.lost += 1 if i % 25 == 0 else 0
        sink.on_scan(dev, i, pts, np.zeros(450), 3600)
        tgt = ld2450.Target(100, 900, -5, 320)
        sink.on_targets(dev, i, [tgt], [(-100.0, -950.0, 170.0)])
        radar.stats.datagrams += 1
        sink.on_vitals(radar, i, protocol.Vitals(True, 14.0, 66.0, 0.5, -0.2, 830))
        if i % 10 == 9:
            sink.tick()
    kinds = [k for k, _ in notes]
    assert kinds[:3] == ["connected", "connected", "log"]
    assert notes[2][1] == {"device": "marvin-a1b2c3", "text": "firmware up", "ts": wall.t}

    by_name = {d["name"]: d for d in sink.devices()}
    d = by_name["marvin-a1b2c3"]
    assert d["role"] == "robot" and d["board"] == protocol.BOARDS[3] and d["firmware"] == "0.6.0"
    assert d["ip"] == "192.168.1.42" and d["rssi"] == -61 and d["rssi_bars"] == 3
    assert d["simulated"] and d["audio"] and not d["camera"] and d["online"] and d["age_s"] < 1
    assert d["rates"]["scans"] == pytest.approx(10, abs=0.6) and d["rates"]["datagrams"] == pytest.approx(50, abs=3)
    assert d["points"] == 450 and 0 < d["loss_pct"] < 1 and d["crc_errors"] == 0
    v = by_name["marvin-c6b0a2"]
    assert v["role"] == "vitals" and v["label"] == "Vital signs radar"
    assert v["rates"]["vitals"] == pytest.approx(10, abs=0.6) and v["rates"]["scans"] == 0
    json.dumps(sink.devices())           # JSON as it is

    scene = sink.scene()
    assert scene["lidar"]["ranges_cm"][0] == 100 and scene["lidar"]["points"] == 450
    assert scene["targets"] == [[10, 95, -5]]            # right = -X, forward = -Y, cm
    assert 0 < len(scene["trail"]) <= 26
    vit = scene["vitals"]
    assert vit["valid"] and vit["breath_rate"] == 14.0 and vit["heart_rate"] == 66.0 and vit["distance_cm"] == 83
    assert len(vit["waves"]) == 50 and vit["waves"][0][1:] == [0.5, -0.2]
    assert 4 <= len(vit["rates"]) <= 6                   # once a second
    assert "rates" not in sink.scene(history=False)["vitals"]

    # the robot goes quiet, then comes back: one line each
    clock.t += 7
    radar.stats.datagrams += 1
    sink.tick()
    assert [k for k, _ in notes[3:]] == ["disconnected"]
    assert notes[3][1]["name"] == "marvin-a1b2c3" and not notes[3][1]["online"]
    clock.t += 1
    dev.stats.datagrams += 1
    sink.tick()
    assert [k for k, _ in notes[3:]] == ["disconnected", "reconnected"]


def test_ui_sink_callbacks_are_cheap():
    sink = UISink()
    dev = robot()
    pts = np.random.default_rng(0).normal(0, 1000, (450, 3)).astype(np.float32)
    t = time.perf_counter()
    for i in range(2000):
        sink.on_scan(dev, i, pts, None, 3600)
        sink.on_targets(dev, i, [], [])
        sink.on_vitals(dev, i, protocol.Vitals(False, 0, 0, 0, 0, 0))
    per_call = (time.perf_counter() - t) / 6000
    assert per_call < 50e-6              # a few microseconds each: no conversion, no I/O


# ================================================================================ HTTP

@pytest.fixture
def control(tmp_path):
    brain = Brain()
    sink = UISink()
    made = []

    def factory(config, settings):
        made.append(FakeAssistant(config, settings))
        return made[-1]
    voice = VoiceController(brain, factory=factory, check=None, path=tmp_path / "voice.json")
    store = EventStore(tmp_path / "marvin.db")
    srv = UIServer(brain, host="127.0.0.1", port=0, store=store, token="sekret", sample_period_s=0.05,
                   quiet=True, sink=sink, voice=voice).start()
    srv.made = made
    yield srv
    srv.stop()
    voice.close()
    store.close()


def post(srv, path, body, headers=JSON):
    r, data = request(srv, "POST", path, json.dumps(body), headers)
    return r.status, json.loads(data or b"{}")


def test_robot_endpoint(control):
    dev = robot()
    sink = control.sink
    sink.on_hello(dev)
    dev.stats.datagrams += 3
    sink.on_scan(dev, 0, np.array([[0, -1500, 133]], dtype=np.float32), None, 3600)
    sink.tick()
    status, body = get_json(control, "/api/robot")
    assert status == 200 and len(body["devices"]) == 1
    d = body["devices"][0]
    for key in ("name", "role", "board", "firmware", "rssi", "rssi_bars", "online", "age_s", "rates", "loss_pct",
                "crc_errors", "simulated", "camera", "audio", "shed"):
        assert key in d
    assert body["scene"]["lidar"]["ranges_cm"][0] == 150 and body["scene"]["radar"]["half_angle_deg"] == 60

    # the robot panel's stream
    c = http.client.HTTPConnection("127.0.0.1", control.port, timeout=5)
    c.request("GET", "/api/robot/stream", headers={"Host": f"127.0.0.1:{control.port}"})
    r = c.getresponse()
    assert r.status == 200 and r.getheader("Content-Type") == "text/event-stream"
    got = read_sse(r, lambda k, d: k == "devices")
    assert got[-1][1][0]["name"] == "marvin-a1b2c3" and any(k == "scene" for k, _ in got)
    c.close()


def test_voice_control_endpoints(control):
    status, body = get_json(control, "/api/voice")
    assert status == 200 and body["voice"]["state"] == "off" and body["settings"]["llm_model"]
    assert post(control, "/api/voice/ask", {"text": "Bonjour"})[0] == 409          # off: nothing to ask

    status, body = post(control, "/api/voice/on", {})
    assert status == 200
    control.voice.wait(5)
    assert control.voice.state == "on" and control.settings["voice"] is True       # and at the next start
    va = control.made[0]
    assert post(control, "/api/voice/ask", {"text": "Quelle heure est-il ?"})[0] == 200
    assert post(control, "/api/voice/listen", {})[0] == 200
    status, body = post(control, "/api/voice/mute", {"muted": True})
    assert status == 200 and body["voice"]["muted"] is True
    assert post(control, "/api/voice/stop-speaking", {})[0] == 200
    assert va.calls == [("ask", "Quelle heure est-il ?"), ("listen",), ("mute", True), ("stop",)]

    for path, bad in (("/api/voice/ask", {"text": 3}), ("/api/voice/ask", {"text": "x" * 501}),
                      ("/api/voice/mute", {"muted": "yes"}), ("/api/voice/ask", [1])):
        assert post(control, path, bad)[0] == 400, (path, bad)
    transcript = get_json(control, "/api/voice")[1]["transcript"]
    assert [e["kind"] for e in transcript] == ["note", "heard"] and transcript[1]["source"] == "typed"

    assert post(control, "/api/voice/off", {})[0] == 200
    control.voice.wait(5)
    assert va.closed and control.voice.state == "off" and control.settings["voice"] is False


def test_voice_endpoints_are_protected(control, monkeypatch):
    r, _ = request(control, "POST", "/api/voice/on", "{}", {**JSON, "Origin": "http://evil.example"})
    assert r.status == 403
    r, _ = request(control, "POST", "/api/voice/ask", "text=hi", {"Content-Type": "application/x-www-form-urlencoded"})
    assert r.status == 415
    r, _ = request(control, "POST", "/api/voice/on", "{}", {**JSON, "Host": "evil.example"})
    assert r.status == 403                                # DNS rebinding
    monkeypatch.setattr(ui_server._Handler, "_client_is_local", lambda self: False)
    for method, path in (("GET", "/api/voice"), ("GET", "/api/robot"), ("GET", "/api/log"),
                         ("GET", "/api/robot/stream"), ("POST", "/api/voice/on")):
        r, _ = request(control, method, path, "{}" if method == "POST" else None, JSON if method == "POST" else None)
        assert r.status == 401, path
    r, _ = request(control, "POST", "/api/voice/on", "{}", {**JSON, "Authorization": "Bearer sekret"})
    assert r.status == 200
    assert control.voice.state in ("starting", "on")


def test_voice_settings_round_trip(control, tmp_path):
    post(control, "/api/voice/on", {})
    control.voice.wait(5)
    status, body = post(control, "/api/voice/settings", {"llm_model": "qwen3:8b", "language": "fr", "wake": False,
                                                          "follow_up_s": 3.5, "reminders": False, "tts": "espeak"})
    assert status == 200 and body["settings"]["llm_model"] == "qwen3:8b" and body["settings"]["language"] == "fr"
    control.voice.wait(5)
    saved = json.loads((tmp_path / "voice.json").read_text())
    assert saved == {"llm_model": "qwen3:8b", "language": "fr", "wake": False, "follow_up_s": 3.5,
                     "reminders": False, "tts": "espeak"}
    assert len(control.made) == 2 and control.made[1].config.llm_model == "qwen3:8b"   # restarted with them
    assert control.made[1].config.language == "fr" and control.made[1].config.wake is False
    assert get_json(control, "/api/voice")[1]["settings"]["follow_up_s"] == 3.5
    for bad in ({"llm_model": ""}, {"language": "es"}, {"follow_up_s": -1}, {"what": 1}):
        assert post(control, "/api/voice/settings", bad)[0] == 400
    status, opts = get_json(control, "/api/voice/options")
    assert status == 200 and "llm_models" in opts and "voices" in opts


def read_sse(r, until, timeout=5.0):
    """(kind, data) messages from an SSE response until `until(kind, data)`."""
    out, cur = [], None
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        line = r.fp.readline().decode().rstrip("\n")
        if line.startswith("event: "):
            cur = line[7:]
        elif line.startswith("data: "):
            data = json.loads(line[6:])
            out.append((cur, data))
            if until(cur, data):
                return out
    raise AssertionError(f"not found in {[k for k, _ in out]}")


def test_sse_delivers_voice_status_and_transcript(control):
    control.voice.start(wait=True)
    va = control.made[0]
    c = http.client.HTTPConnection("127.0.0.1", control.port, timeout=5)
    c.request("GET", "/api/stream", headers={"Host": f"127.0.0.1:{control.port}"})
    r = c.getresponse()
    first = read_sse(r, lambda k, d: k == "voice")
    assert first[-1][1]["state"] == "on"
    va.status = "listening"
    va.emit("status", status="listening")
    got = read_sse(r, lambda k, d: k == "voice" and d["status"] == "listening")
    assert got[-1][1]["status"] == "listening"
    va.emit("heard", text="Quelle heure est-il ?", language="fr", source="voice")
    va.emit("reply", text="Midi.", language="fr", latency={"endpoint": 0.55, "audio_start": 0.8}, interrupted=False,
            proactive=False, error=None, hint="")
    got = read_sse(r, lambda k, d: k == "transcript" and d["kind"] == "reply")
    heard = [d for k, d in got if k == "transcript" and d["kind"] == "heard"]
    assert heard and heard[0]["text"] == "Quelle heure est-il ?"
    assert got[-1][1]["text"] == "Midi." and got[-1][1]["first_word_s"] == 1.35
    c.close()


def test_log_gathers_brain_devices_and_warnings(control):
    feed(control.brain, 0, 10)                           # someone walks in
    dev = robot()
    control.sink.on_hello(dev)
    control.sink.on_log(dev, 0, "firmware up")
    control.sink.tick()
    logging.getLogger("marvin.test").warning("the lidar is slow")
    time.sleep(0.1)
    entries = get_json(control, "/api/log")[1]["entries"]
    by_source = {}
    for e in entries:
        by_source.setdefault(e["source"], []).append(e["text"])
    assert "You came in" in by_source["brain"]
    assert "firmware up" in by_source["device"] and any("connected" in t for t in by_source["device"])
    warn = [e for e in entries if e["level"] == "warning" and e["source"] == "host"]
    assert warn and warn[0]["text"] == "the lidar is slow"
    assert entries[0]["id"] > entries[-1]["id"]          # newest first
    only = get_json(control, "/api/log?source=device")[1]["entries"]
    assert only and {e["source"] for e in only} == {"device"}
    control.stop()
    logging.getLogger("marvin.test").warning("after stop")
    assert all(e["text"] != "after stop" for e in control.log_entries())    # the handler is removed


def test_voice_unavailable_without_a_controller(tmp_path):
    srv = UIServer(Brain(), host="127.0.0.1", port=0, store=EventStore(":memory:"), token=None, quiet=True).start()
    try:
        status, body = get_json(srv, "/api/voice")
        assert status == 200 and body["voice"]["state"] == "unavailable" and body["settings"] is None
        assert post(srv, "/api/voice/on", {})[0] == 404
        assert get_json(srv, "/api/robot")[1] == {"devices": [], "scene": None}
    finally:
        srv.stop()


# ================================================================================ the terminal

def test_terminal_lines():
    out = []
    term = cli.Terminal(out=out.append)
    info = {"name": "marvin-a1b2c3", "board": "Wemos D1 mini (ESP8266)", "firmware": "0.4.1", "simulated": True,
            "ip": "192.168.1.40", "age_s": 6.2}
    term.device("connected", info)
    term.device("log", {"device": "marvin-a1b2c3", "text": "firmware up", "ts": 0})
    term.device("disconnected", info)
    term.voice("voice", {"state": "starting", "model": "qwen3:4b-instruct", "wake": True})
    term.voice("voice", {"state": "on", "model": "qwen3:4b-instruct", "wake": True})
    term.voice("voice", {"state": "on", "model": "qwen3:4b-instruct", "wake": True, "status": "listening"})
    term.voice("transcript", {"kind": "heard", "text": "Quelle heure est-il ?", "source": "voice"})
    term.voice("voice", {"state": "error", "error": "Ollama is not running", "fix": "ollama serve"})
    assert out == [
        "+ marvin-a1b2c3 connected (Wemos D1 mini (ESP8266), firmware 0.4.1, simulated sensors) at 192.168.1.40",
        "- marvin-a1b2c3 disconnected (nothing received for 6 s)",
        "voice: starting (qwen3:4b-instruct)...",
        "voice: on, say “Marvin, …”",
        "voice: Ollama is not running. Fix: ollama serve",
    ]
    out.clear()
    chatty = cli.Terminal(verbose=True, out=out.append)
    chatty.device("log", {"device": "marvin-a1b2c3", "text": "firmware up", "ts": 0})
    chatty.voice("transcript", {"kind": "heard", "text": "Quelle heure est-il ?", "source": "voice"})
    chatty.voice("transcript", {"kind": "reply", "text": "Midi.", "first_word_s": 1.3})
    chatty.voice("transcript", {"kind": "ignored", "text": "Merci.", "reason": "known hallucination"})
    assert out == ["[marvin-a1b2c3] firmware up", "  you: Quelle heure est-il ?", "  marvin: Midi.  (1.3 s)",
                   "  (ignored, known hallucination: Merci.)"]


def _free_udp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _run(args, seconds, capsys, tmp_path, monkeypatch):
    from marvin_host.sim import SimDevice
    monkeypatch.setenv("MARVIN_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("MARVIN_CONFIG_DIR", str(tmp_path / "config"))
    port = _free_udp_port()
    sim = threading.Thread(target=lambda: SimDevice(host="127.0.0.1", port=port).run(seconds - 0.8), daemon=True)
    threading.Timer(0.4, sim.start).start()
    root = logging.getLogger()
    saved = (root.level, list(root.handlers), logging.getLogger("marvin.receiver").level)
    try:
        cli.main([*args[:1], "run", "--no-viewer", "--port", str(port), "--ui-host", "127.0.0.1", "--ui-port", "0",
                  "--seconds", str(seconds), *args[1:]])
    finally:
        root.setLevel(saved[0])
        root.handlers[:] = saved[1]
        logging.getLogger("marvin.receiver").setLevel(saved[2])
    sim.join(5)
    return capsys.readouterr().out.splitlines()


def test_run_is_quiet_by_default(capsys, tmp_path, monkeypatch):
    lines = _run([], 2.5, capsys, tmp_path, monkeypatch)
    text = "\n".join(lines)
    assert lines[0].startswith("Marvin's app: http://")
    assert "listening for the robot on UDP" in text
    assert any(line.startswith("+ marvin-") and "connected (simulator" in line for line in lines)
    assert "scans/s" not in text and "  * " not in text          # no per-second summary, no events
    assert len(lines) <= 5


def test_run_verbose_adds_the_summary(capsys, tmp_path, monkeypatch):
    lines = _run(["-v"], 2.8, capsys, tmp_path, monkeypatch)
    assert sum("scans/s" in line for line in lines) >= 1


def test_console_sink_period():
    out = []
    sink = cli.ConsoleSink(period_s=5.0, logs=False)
    dev = robot()
    import builtins
    real = builtins.print
    builtins.print = lambda *a, **k: out.append(" ".join(map(str, a)))
    try:
        sink.on_log(dev, 0, "firmware up")
        for i in range(10):
            sink.on_targets(dev, i, [], [])
        sink.next = 0                                     # the period is over
        sink.on_targets(dev, 11, [], [])
    finally:
        builtins.print = real
    assert len(out) == 1 and "radar/s" in out[0] and out[0].count("firmware") == 0
    assert "2 radar/s" in out[0]                          # 11 frames over 5 s
