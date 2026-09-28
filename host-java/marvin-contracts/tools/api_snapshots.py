# SPDX-License-Identifier: MIT
"""JSON snapshots of the app's API, recorded from the Python host in demo mode (docs/ui.md).

Starts what `marvin-host ui --demo` starts, in this process: a simulated robot and a simulated
past week, the scripted voice (never the real one, so the snapshots do not depend on the machine),
the app on a free port with the access key "contract-key". Then it calls every endpoint and
listens to both event streams. Written to golden/api/:

    get/<name>.json, post/<name>.json, access/<name>.json
        {"request": {...}, "status": int, "headers": {...}, "body": JSON or null, "shape": ...}
    sse.json         per stream: the event names seen, a sample payload and the shape of each
    index.json       what was recorded, and which parts of the bodies change from run to run

`shape` is the body with every value replaced by its JSON type ("string", "number", "boolean",
"null"); a list becomes the list of the distinct shapes of its items. Values change with time
(the demo's clock runs 10 times faster than real time): the Java web adapter must answer with
the same shapes, and with the same values wherever index.json does not list them as volatile.

    python3 api_snapshots.py [--seconds 6]
"""
from __future__ import annotations

import argparse
import http.client
import json
import logging
import os
import shutil
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from _common import GOLDEN, rel, write_json

from marvin_host.ui.server import COOKIE, lan_address  # noqa: E402

OUT = GOLDEN / "api"
KEY = "contract-key"
# the demo's clock starts here: Monday 21 September 2026, 09:30 UTC
WALL0 = datetime(2026, 9, 21, 9, 30, tzinfo=timezone.utc).timestamp()
KEEP_HEADERS = ("content-type", "cache-control", "location", "set-cookie", "content-security-policy",
                "x-content-type-options", "x-frame-options", "referrer-policy")


def shape(v):
    if isinstance(v, dict):
        return {k: shape(x) for k, x in v.items()}
    if isinstance(v, list):
        seen, out = set(), []
        for x in v:
            s = shape(x)
            key = json.dumps(s, sort_keys=True)
            if key not in seen:
                seen.add(key)
                out.append(s)
        return out
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "boolean"
    if isinstance(v, (int, float)):
        return "number"
    return "string"


class Demo:
    def __init__(self, folder: Path, speed: float):
        from marvin_host.brain import Brain
        from marvin_host.ui import UISink, UIServer
        from marvin_host.ui.demo import DemoRobot, DemoVoice, seed_conversations, seed_history
        from marvin_host.ui.store import EventStore
        from marvin_host.voice import cli as voice_cli
        from marvin_host.voice.control import VoiceController

        self.brain = Brain()
        self.sink = UISink()
        self.robot = DemoRobot(self.brain, speed=speed, sink=self.sink, wall0=WALL0)
        self.store = EventStore(folder / "marvin.db")
        seed_history(self.store, self.robot.clock())
        seed_conversations(self.store, self.robot.clock())
        path = folder / "voice.json"
        voice_cli.save_settings({}, path)
        robot = self.robot
        self.voice = VoiceController(self.brain, factory=lambda config, settings: DemoVoice(self.brain, robot.clock),
                                     check=None, path=path, clock=robot.clock)
        self.voice.attach_store(self.store)
        self.voice.start(wait=True)
        self.server = UIServer(self.brain, host="0.0.0.0", port=0, store=self.store, token=KEY,
                               clock=robot.clock, sink=self.sink, voice=self.voice, quiet=True)
        self.server.start()
        self.sink.start()
        self.robot.start()

    def stop(self) -> None:
        for fn in (self.robot.stop, self.sink.stop, self.server.stop, self.voice.close, self.store.close):
            try:
                fn()
            except Exception:           # noqa: BLE001 - best effort on the way out
                pass


class Client:
    def __init__(self, port: int, host: str = "127.0.0.1"):
        self.port, self.host = port, host

    def call(self, method: str, path: str, body=None, headers: dict | None = None, raw_body: bytes | None = None,
             host: str | None = None) -> dict:
        c = http.client.HTTPConnection(host or self.host, self.port, timeout=10)
        h = {"Host": f"localhost:{self.port}"} if (host or self.host) == "127.0.0.1" else {}
        h.update(headers or {})
        data = raw_body
        if body is not None:
            data = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
        c.request(method, path, body=data, headers=h)
        r = c.getresponse()
        payload = r.read()
        c.close()
        ctype = r.getheader("Content-Type", "")
        out = {"request": {"method": method, "path": path, **({"headers": headers} if headers else {}),
                           **({"body": body} if body is not None else {}),
                           **({"raw_body": raw_body.decode()} if raw_body is not None else {})},
               "status": r.status,
               "headers": {k: r.getheader(k) for k in KEEP_HEADERS if r.getheader(k) is not None}}
        if ctype.startswith("application/json"):
            out["body"] = json.loads(payload)
            out["shape"] = shape(out["body"])
        else:
            out["body"] = None
            out["body_bytes"] = len(payload)
            if ctype.startswith("text/html") and len(payload) < 4096:
                out["body_text"] = payload.decode()
        return out


class SseRecorder(threading.Thread):
    """Reads an SSE stream in the background until stop(): (event, data) pairs."""

    def __init__(self, port: int, path: str):
        super().__init__(daemon=True, name=f"sse {path}")
        self.port, self.path = port, path
        self.events: list[tuple[str, object]] = []
        self.retry: int | None = None
        self._done = threading.Event()
        self._ready = threading.Event()

    def run(self) -> None:
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        c.request("GET", self.path, headers={"Host": f"localhost:{self.port}", "Accept": "text/event-stream"})
        r = c.getresponse()
        self.status, self.content_type = r.status, r.getheader("Content-Type")
        self._ready.set()
        kind, data = None, []
        while not self._done.is_set():         # "state" comes twice a second: the flag is checked often
            try:
                line = r.fp.readline()
            except (TimeoutError, OSError):
                break
            if not line:
                break
            line = line.decode().rstrip("\n")
            if line.startswith("retry: "):
                self.retry = int(line[7:])
            elif line.startswith("event: "):
                kind = line[7:]
            elif line.startswith("data: "):
                data.append(line[6:])
            elif line == "" and kind is not None:
                self.events.append((kind, json.loads("\n".join(data))))
                kind, data = None, []
        c.close()

    def start(self) -> "SseRecorder":
        super().start()
        self._ready.wait(10)
        return self

    def stop(self) -> list[tuple[str, object]]:
        self._done.set()
        self.join(5)
        return self.events


def summarize_sse(events) -> dict:
    out: dict[str, dict] = {}
    for kind, data in events:
        e = out.setdefault(kind, {"count": 0, "sample": data, "shapes": []})
        e["count"] += 1
        s = shape(data)
        if s not in e["shapes"]:
            e["shapes"].append(s)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--seconds", type=float, default=6.0, help="real seconds of demo before the snapshots")
    args = ap.parse_args()

    tmp = Path(tempfile.mkdtemp(prefix="marvin-contracts-"))
    os.environ["MARVIN_DATA_DIR"] = str(tmp)
    os.environ["MARVIN_CONFIG_DIR"] = str(tmp)
    shutil.rmtree(OUT, ignore_errors=True)
    demo = Demo(tmp, speed=10.0)
    try:
        port = demo.server.port
        cl = Client(port)
        stream = SseRecorder(port, "/api/stream").start()      # from the start: brain events, devices, log
        time.sleep(args.seconds)
        now = demo.robot.clock()
        today = datetime.fromtimestamp(now, timezone.utc).date()
        yesterday = today - timedelta(days=1)
        past = today - timedelta(days=3)                   # the last working day (seed_conversations)

        gets = {
            "state": "/api/state",
            "day_today": "/api/day",
            "day_yesterday": f"/api/day?date={yesterday.isoformat()}",
            "history_7": "/api/history?days=7",
            "history_3_until_yesterday": f"/api/history?days=3&date={yesterday.isoformat()}",
            "events": "/api/events?limit=20",
            "events_quiet": "/api/events?limit=20&quiet=1",
            "settings": "/api/settings",
            "robot": "/api/robot",
            "log": "/api/log?limit=50",
            "log_devices": "/api/log?source=device&limit=20",
            "conversation_today": "/api/conversation",
            "conversation_past_day": f"/api/conversation?day={past.isoformat()}",
            "conversation_search": "/api/conversation?q=stretch",
            "conversation_search_limit": "/api/conversation?q=e&limit=3",
            "voice": "/api/voice",
            "voice_options": "/api/voice/options",
            "error_not_found": "/api/nope",
            "error_bad_date": "/api/day?date=21-09-2026",
            "error_bad_integer": "/api/history?days=many",
            "app_index": "/",
            "app_script": "/static/app.js",
            "app_style": "/static/style.css",
            "app_manifest": "/manifest.webmanifest",
            "face_png": "/face.png",
            "icon_png": "/icon.png",
        }
        for name, path in gets.items():
            write_json(OUT / "get" / f"{name}.json", cl.call("GET", path))

        posts = [
            ("settings_break_45", "/api/settings", {"break_interval_min": 45}),
            ("settings_quiet_hours", "/api/settings", {"quiet_hours": {"enabled": True, "start": "22:00", "end": "07:30"}}),
            ("settings_invalid", "/api/settings", {"break_interval_min": 0}),
            ("voice_mute_on", "/api/voice/mute", {"muted": True}),
            ("voice_mute_off", "/api/voice/mute", {"muted": False}),
            ("voice_mute_invalid", "/api/voice/mute", {"muted": "yes"}),
            ("voice_listen", "/api/voice/listen", {}),
            ("voice_listen_off", "/api/voice/listen", {"on": False}),
            ("voice_stop_speaking", "/api/voice/stop-speaking", {}),
            ("voice_ask_too_long", "/api/voice/ask", {"text": "x" * 501}),
            ("voice_ask_not_text", "/api/voice/ask", {"text": 42}),
            ("voice_settings_language", "/api/voice/settings", {"language": "en"}),
            ("voice_settings_invalid", "/api/voice/settings", {"follow_up_s": "soon"}),
            ("voice_off", "/api/voice/off", {}),
            ("voice_ask_while_off", "/api/voice/ask", {"text": "Are you there?"}),
            ("voice_on", "/api/voice/on", {}),
            ("not_found", "/api/nope", {}),
        ]
        for name, path, body in posts:
            write_json(OUT / "post" / f"{name}.json", cl.call("POST", path, body))
            if name == "voice_on":
                time.sleep(0.5)                        # the voice starts in the background
        # the rules around POST: JSON only, same origin only
        write_json(OUT / "post" / "wrong_content_type.json",
                   cl.call("POST", "/api/settings", raw_body=b"break_interval_min=45",
                           headers={"Content-Type": "application/x-www-form-urlencoded"}))
        write_json(OUT / "post" / "cross_origin.json",
                   cl.call("POST", "/api/settings", {"break_interval_min": 45},
                           headers={"Origin": "http://evil.example"}))
        write_json(OUT / "post" / "bad_json.json",
                   cl.call("POST", "/api/settings", raw_body=b"{not json", headers={"Content-Type": "application/json"}))

        # live turns, for the stream: Talk now (level, utterance, partial, heard, the spoken reply),
        # then a typed question with a tool call, a settings change, a host warning
        time.sleep(1.0)
        write_json(OUT / "post" / "voice_listen_turn.json", cl.call("POST", "/api/voice/listen", {}))
        time.sleep(7.5)
        write_json(OUT / "post" / "voice_ask.json", cl.call("POST", "/api/voice/ask", {"text": "What's the weather in Paris?"}))
        time.sleep(6.0)
        write_json(OUT / "post" / "settings_break_50.json", cl.call("POST", "/api/settings", {"break_interval_min": 50}))
        logging.getLogger("marvin.contracts").warning("a warning from the host, for the log stream")
        time.sleep(1.0)
        robot = SseRecorder(port, "/api/robot/stream").start()
        time.sleep(2.5)
        robot_events = robot.stop()
        events = stream.stop()
        write_json(OUT / "get" / "voice_after_turns.json", cl.call("GET", "/api/voice"))
        write_json(OUT / "get" / "conversation_search_after_turns.json", cl.call("GET", "/api/conversation?q=weather"))
        write_json(OUT / "get" / "conversation_today_after_turns.json", cl.call("GET", "/api/conversation"))

        # access key rules: from this computer no key is needed; another device needs it
        access = {"bad_host": cl.call("GET", "/api/state", headers={"Host": "evil.example"})}
        lan = lan_address()
        if lan:
            other = Client(port, host=lan)
            access.update({
                "no_key_api": other.call("GET", "/api/state"),
                "no_key_page": other.call("GET", "/"),
                "no_key_static_is_public": other.call("GET", "/static/style.css"),
                "wrong_key": other.call("GET", "/api/state", headers={"Authorization": "Bearer nope"}),
                "key_as_bearer": other.call("GET", "/api/settings", headers={"Authorization": f"Bearer {KEY}"}),
                "key_as_cookie": other.call("GET", "/api/settings", headers={"Cookie": f"{COOKIE}={KEY}"}),
                "key_in_url_sets_cookie": other.call("GET", f"/?token={KEY}&tab=talk"),
                "key_in_url_on_api": other.call("GET", f"/api/settings?token={KEY}"),
                "no_key_post": other.call("POST", "/api/settings", {"break_interval_min": 30}),
            })
        for name, r in access.items():
            write_json(OUT / "access" / f"{name}.json", r)

        sse = {"about": "Server-Sent Events of the app, from the Python host in demo mode, while a question "
                        "was typed. Each message is 'event: <name>' and 'data: <compact JSON>'; the first "
                        "message of each stream also carries 'retry: 3000'.",
               "streams": {"/api/stream": {"status": stream.status, "content_type": stream.content_type,
                                           "retry": stream.retry, "first": [k for k, _ in events[:5]],
                                           "events": summarize_sse(events)},
                           "/api/robot/stream": {"status": robot.status, "content_type": robot.content_type,
                                                 "retry": robot.retry, "first": [k for k, _ in robot_events[:3]],
                                                 "events": summarize_sse(robot_events)}},
               "documented_not_seen": sorted({"state", "event", "today", "settings", "voice", "transcript", "log",
                                              "devices", "level", "utterance", "partial", "say"}
                                             - {k for k, _ in events})}
        write_json(OUT / "sse.json", sse)
        index = {
            "about": "App API snapshots from the Python host in demo mode (host-java/marvin-contracts/tools/api_snapshots.py).",
            "demo": {"wall0": WALL0, "wall0_iso": datetime.fromtimestamp(WALL0, timezone.utc).isoformat(),
                     "speed": 10.0, "time_zone": "UTC", "access_key": KEY, "lan_address_available": bool(lan)},
            "get": sorted([*gets, "voice_after_turns", "conversation_search_after_turns",
                           "conversation_today_after_turns"]), "post": [p[0] for p in posts] + ["wrong_content_type", "cross_origin", "bad_json"],
            "access": sorted(access),
            "volatile": ["times (t, ts, id, since, uptime, ages, durations)", "brain state values (seated_s, still_s, "
                         "distance_m, breath_rate, heart_rate, targets, expression, status, detail, mood)",
                         "today's stats and timeline", "device counters and rates", "sensor scene values",
                         "log entries", "voice status", "about.data_dir"],
            "machine_dependent": ["get/voice_options.json: Ollama models, speech backends and voices installed on "
                                  "the machine that recorded it; compare the shape only"],
        }
        write_json(OUT / "index.json", index)
        print(f"wrote {rel(OUT)}: {len(gets)} GET, {len(index['post'])} POST, {len(access)} access, "
              f"SSE events {sorted(sse['streams']['/api/stream']['events'])} and "
              f"{sorted(sse['streams']['/api/robot/stream']['events'])}; not seen: {sse['documented_not_seen']}")
    finally:
        demo.stop()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
