"""Marvin's app: a small web server that shows the owner what the robot sees, on any browser.

Standard library only: ``ThreadingHTTPServer`` for the pages and the JSON API, Server-Sent Events
for live updates, SQLite (store.py) for the history. The face is rendered here with face.py, from
the same brain state as the robot's screen, and served as PNG (png.py, no imaging library).

Access (see docs/ui.md):
- the server listens on the LAN (0.0.0.0) so a phone on the same Wi-Fi can open it;
- requests from this computer (loopback) need nothing;
- requests from other devices need the access key, a random token created on first run and kept
  next to the database (``ui_token``). The URL printed at start-up carries it once; the server then
  sets a cookie and redirects to a clean URL;
- the Host header must be an IP address or this machine's name when no key is checked (this
  blocks DNS-rebinding pages in the browser from reading the loopback API), and POST requests must
  be JSON from the same origin.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import hmac
import ipaddress
import json
import logging
import math
import queue
import secrets
import socket
import threading
import time
from collections import deque
from datetime import date, datetime
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlencode, urlsplit

from ..events import Event, EventKind
from . import png, stats
from .store import EventStore

log = logging.getLogger("marvin.ui")

STATIC = Path(__file__).parent / "static"
STATIC_TYPES = {
    "index.html": "text/html; charset=utf-8",
    "app.js": "text/javascript; charset=utf-8",
    "style.css": "text/css; charset=utf-8",
    "manifest.webmanifest": "application/manifest+json",
}
COOKIE = "marvin_key"
STATE_PERIOD_S = 0.5            # SSE state updates (2 Hz)
TODAY_PERIOD_S = 5.0            # SSE day summary refresh, besides after every event
FACE_MIN_PERIOD_S = 1 / 15      # the face is rendered at most this often, only when someone looks

DEFAULT_SETTINGS = {
    "break_interval_min": 50,   # seated this long -> time for a break (drives BrainConfig.still_long_s)
    "quiet_hours": {"enabled": False, "start": "22:00", "end": "07:00"},
    "voice": False,             # placeholder: the voice pipeline will read it
    "clock": "24h",             # "24h" or "12h"
}


def validate_settings(update: dict, current: dict) -> dict:
    """``current`` with ``update`` applied; ValueError on anything unexpected."""
    if not isinstance(update, dict):
        raise ValueError("settings must be a JSON object")
    out = json.loads(json.dumps(current))
    for key, value in update.items():
        if key == "break_interval_min":
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not 1 <= value <= 240:
                raise ValueError("break_interval_min must be a number of minutes between 1 and 240")
            out[key] = int(value) if float(value).is_integer() else float(value)
        elif key == "quiet_hours":
            if not isinstance(value, dict):
                raise ValueError("quiet_hours must be an object")
            q = dict(out["quiet_hours"])
            for k, v in value.items():
                if k == "enabled":
                    if not isinstance(v, bool):
                        raise ValueError("quiet_hours.enabled must be true or false")
                elif k in ("start", "end"):
                    _parse_hhmm(v)
                else:
                    raise ValueError(f"unknown setting quiet_hours.{k}")
                q[k] = v
            out[key] = q
        elif key == "voice":
            if not isinstance(value, bool):
                raise ValueError("voice must be true or false")
            out[key] = value
        elif key == "clock":
            if value not in ("24h", "12h"):
                raise ValueError('clock must be "24h" or "12h"')
            out[key] = value
        else:
            raise ValueError(f"unknown setting {key}")
    return out


def in_quiet_hours(settings: dict, ts: float) -> bool:
    q = settings.get("quiet_hours") or {}
    if not q.get("enabled"):
        return False
    t = datetime.fromtimestamp(ts)
    m = t.hour * 60 + t.minute
    a, b = _parse_hhmm(q["start"]), _parse_hhmm(q["end"])
    return a <= m < b if a <= b else (m >= a or m < b)


def _parse_hhmm(v) -> int:
    try:
        h, m = str(v).split(":")
        h, m = int(h), int(m)
    except ValueError:
        raise ValueError(f"expected a time HH:MM, got {v!r}") from None
    if not (0 <= h < 24 and 0 <= m < 60):
        raise ValueError(f"expected a time HH:MM, got {v!r}")
    return h * 60 + m


def lan_address() -> str | None:
    """This computer's address on the local network (no packet is sent)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))              # TEST-NET-1: only picks the outgoing interface
            ip = s.getsockname()[0]
    except OSError:
        return None
    return None if ip.startswith("127.") else ip


class _Hub:
    """Fans out messages to the connected SSE clients."""

    def __init__(self):
        self._clients: set[queue.Queue] = set()
        self._lock = threading.Lock()

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=256)
        with self._lock:
            self._clients.add(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            self._clients.discard(q)

    def publish(self, kind: str, payload) -> None:
        with self._lock:
            clients = list(self._clients)
        for q in clients:
            try:
                q.put_nowait((kind, payload))
            except queue.Full:
                pass                                # a stuck client misses messages, never blocks the brain

    def __len__(self) -> int:
        return len(self._clients)


class UIServer:
    """Serves the app for one brain. ``start()`` runs it in background threads; ``stop()`` ends it.

    - ``host``/``port``: where to listen (port 0 picks a free one, see ``.port``);
    - ``store``: the history (default: ``EventStore()`` in the data directory);
    - ``token``: ``"auto"`` (a random key kept next to the database), a string, or None (no key:
      anyone on the network can open the app);
    - ``clock``: wall clock for event times and days (the demo runs it faster than real time).
    """

    def __init__(self, brain, host: str = "0.0.0.0", port: int = 8765, store: EventStore | None = None,
                 token: str | None = "auto", clock: Callable[[], float] = time.time, face: bool = True,
                 offline_after_s: float = 15.0, sample_period_s: float = 1.0, quiet: bool = False):
        self.brain = brain
        self.host = host
        self.store = store if store is not None else EventStore()
        self.clock = clock
        self.token = self._load_token(token)
        self.offline_after_s = offline_after_s
        self.sample_period_s = sample_period_s
        self.quiet = quiet
        self.hub = _Hub()
        self.started_at: float | None = None

        self.settings = self._defaults()
        for k, v in self.store.get_settings().items():     # tolerate settings from other versions
            try:
                self.settings = validate_settings({k: v}, self.settings)
            except ValueError:
                log.warning("ignoring stored setting %s=%r", k, v)
        self._apply_settings()

        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []
        self._httpd = ThreadingHTTPServer((host, port), _Handler, bind_and_activate=True)
        self._httpd.daemon_threads = True
        self._httpd.block_on_close = False
        self._httpd.app = self                      # type: ignore[attr-defined]

        self.online = False
        self.ever_online = False
        self._online_lock = threading.Lock()
        self._last_t_us = getattr(brain.state, "t_us", 0)
        self._last_change = time.monotonic()
        self._left_at: float | None = None
        self._bucket: float | None = None
        self._acc = [0, 0.0, 0.0, [], []]           # n, present, seated, breaths, hearts
        self._today: dict | None = None
        self._today_at = 0.0

        self._face_enabled = face
        self._face = None
        self._face_lock = threading.Lock()
        self._face_events: deque[Event] = deque(maxlen=64)
        self._face_png: bytes | None = None
        self._face_t = -math.inf
        self._icon: bytes | None = None
        brain.add_listener(self._on_event)

    # ---------------------------------------------------------------- lifecycle

    @property
    def port(self) -> int:
        return self._httpd.server_address[1]

    def start(self) -> UIServer:
        self.started_at = self.clock()
        self._record(stats.HOST_STARTED)
        for target, name in ((self._httpd.serve_forever, "ui-http"), (self._sampler, "ui-sampler")):
            th = threading.Thread(target=target, name=name, daemon=True)
            th.start()
            self._threads.append(th)
        if not self.quiet:
            for line in self.describe_urls():
                print(line)
        return self

    def stop(self) -> None:
        if self._stop.is_set():
            return
        self._stop.set()
        self._httpd.shutdown()
        self._httpd.server_close()
        for th in self._threads:
            th.join(timeout=2)
        self._flush_sample()
        try:
            self.brain.remove_listener(self._on_event)
        except ValueError:
            pass
        self._record(stats.HOST_STOPPED)

    def __enter__(self) -> UIServer:
        return self.start()

    def __exit__(self, *exc) -> None:
        self.stop()

    def urls(self) -> dict[str, str | None]:
        """{"local": URL for this computer, "lan": URL for other devices (with the key) or None}."""
        key = f"?{urlencode({'token': self.token})}" if self.token else ""
        if self.host in ("0.0.0.0", "", "::"):
            ip = lan_address()
        elif _is_ip(self.host) and ipaddress.ip_address(self.host.strip("[]")).is_loopback:
            ip = None
        else:
            ip = self.host
        local = f"http://localhost:{self.port}/" if ip != self.host else f"http://{ip}:{self.port}/{key}"
        return {"local": local, "lan": f"http://{ip}:{self.port}/{key}" if ip else None}

    def describe_urls(self) -> list[str]:
        u = self.urls()
        lines = [f"Marvin's app: {u['local']}"]
        if u["lan"]:
            lines.append(f"  on a phone on the same Wi-Fi: {u['lan']}")
            if not self.token:
                lines.append("  (no access key: anyone on this network can open it)")
        return lines

    def _load_token(self, token: str | None) -> str | None:
        if token != "auto":
            return token or None
        if self.store.path == ":memory:":
            return secrets.token_urlsafe(12)
        path = Path(self.store.path).parent / "ui_token"
        try:
            value = path.read_text().strip()
            if value:
                return value
        except OSError:
            pass
        value = secrets.token_urlsafe(12)
        try:
            path.touch(mode=0o600)
            path.write_text(value + "\n")
        except OSError:
            log.warning("could not save the access key to %s", path)
        return value

    # ---------------------------------------------------------------- settings

    def _defaults(self) -> dict:
        d = json.loads(json.dumps(DEFAULT_SETTINGS))
        cfg = getattr(self.brain, "config", None)
        if cfg is not None and getattr(cfg, "still_long_s", None):
            d["break_interval_min"] = round(cfg.still_long_s / 60, 1)
            if float(d["break_interval_min"]).is_integer():
                d["break_interval_min"] = int(d["break_interval_min"])
        return d

    def update_settings(self, update: dict) -> dict:
        new = validate_settings(update, self.settings)
        changed = {k: v for k, v in new.items() if self.settings.get(k) != v}
        self.store.set_settings(changed)
        self.settings = new
        self._apply_settings()
        self.hub.publish("settings", self.settings)
        return new

    def _apply_settings(self) -> None:
        cfg = getattr(self.brain, "config", None)
        if cfg is not None and hasattr(cfg, "still_long_s"):
            cfg.still_long_s = float(self.settings["break_interval_min"]) * 60

    # ---------------------------------------------------------------- brain events

    def _on_event(self, ev: Event) -> None:
        if not self.online:                         # an event means frames: online right away
            self._set_online(True, from_event=True)
        ts = self.clock()
        if ev.kind == EventKind.LEFT:
            self._left_at = ts
        with self._face_lock:
            self._face_events.append(ev)
        self._publish_event(self.store.add_event(ev, ts))

    def _record(self, kind: str, **data) -> None:
        self._publish_event(self.store.add(kind, self.clock(), "", data))

    def _publish_event(self, e: stats.StoredEvent) -> None:
        self._today = None
        self.hub.publish("event", e.to_json())

    # ---------------------------------------------------------------- live state

    def snapshot(self) -> dict:
        s = self.brain.state
        now = self.clock()
        interval = float(self.settings["break_interval_min"]) * 60
        seated_s = float(s.seated_s) if s.seated else 0.0
        away = now - self._left_at if (self._left_at is not None and not s.present) else None
        st = stats.status(self.online, s.present, s.seated, seated_s, away, s.breath_rate, s.heart_rate,
                          interval, self.ever_online)
        return {
            "t": now,
            "online": self.online,
            "present": bool(s.present),
            "seated": bool(s.seated),
            "seated_s": round(seated_s, 1),
            "still_s": round(float(s.still_s), 1),
            "distance_m": None if s.distance_m is None else round(float(s.distance_m), 2),
            "breath_rate": None if s.breath_rate is None else round(float(s.breath_rate), 1),
            "heart_rate": None if s.heart_rate is None else round(float(s.heart_rate), 1),
            "targets": int(s.targets),
            "expression": self._face.expression if self._face is not None else None,
            "status": st["text"],
            "detail": st["detail"],
            "mood": st["mood"],
            "quiet": in_quiet_hours(self.settings, now),
            "break": {
                "interval_s": interval,
                "seated_s": round(seated_s, 1),
                "progress": round(min(1.0, seated_s / interval), 3) if interval > 0 else 0.0,
                "due": bool(s.seated and seated_s >= interval),
            },
        }

    def today(self) -> dict:
        """Today's stats, cached for a moment (recomputed after every event)."""
        t = time.monotonic()
        if self._today is None or t - self._today_at > 2.0:
            now = self.clock()
            self._today = self.store.day(stats.local_day(now), now, live=True)
            self._today_at = t
        return self._today

    def api_state(self) -> dict:
        return {"state": self.snapshot(), "today": self.today(), "settings": self.settings}

    # ---------------------------------------------------------------- face

    def face_png(self) -> bytes | None:
        """The current face as PNG, or None when the face is disabled or cannot be drawn."""
        if not self._face_enabled:
            return None
        with self._face_lock:
            t = time.monotonic()
            if self._face_png is not None and t - self._face_t < FACE_MIN_PERIOD_S:
                return self._face_png
            try:
                if self._face is None:
                    from ..face import Face
                    self._face = Face()
                while self._face_events:
                    self._face.on_event(self._face_events.popleft())
                frame = self._face.update(self.brain.state, t)
                self._face_png = png.encode(frame)
                self._face_t = t
            except Exception:
                log.exception("face rendering failed")
                self._face_enabled = False
                return None
            return self._face_png

    def icon_png(self) -> bytes | None:
        """App icon: the content face, square."""
        if self._icon is None:
            try:
                from ..face import EXPRESSIONS, render
                frame = render(EXPRESSIONS["content"])
                self._icon = png.encode(frame[26:266])
            except Exception:
                log.exception("icon rendering failed")
                return None
        return self._icon

    # ---------------------------------------------------------------- sampler

    def _sampler(self) -> None:
        next_today = time.monotonic() + TODAY_PERIOD_S
        while not self._stop.wait(self.sample_period_s):
            try:
                self._sample_once()
                if time.monotonic() >= next_today or self._today is None:
                    next_today = time.monotonic() + TODAY_PERIOD_S
                    if len(self.hub):
                        self.hub.publish("today", self.today())
            except Exception:
                log.exception("ui sampler failed")

    def _sample_once(self) -> None:
        s = self.brain.state
        mono = time.monotonic()
        if s.t_us != self._last_t_us:
            self._last_t_us, self._last_change = s.t_us, mono
            if not self.online:
                self._set_online(True)
        elif self.online and mono - self._last_change > self.offline_after_s:
            self._flush_sample()
            self._set_online(False)

        now = self.clock()
        bucket = math.floor(now / 60) * 60
        if self._bucket is not None and bucket != self._bucket:
            self._flush_sample()
        self._bucket = bucket
        if self.online:
            a = self._acc
            a[0] += 1
            a[1] += bool(s.present)
            a[2] += bool(s.seated)
            if s.breath_rate is not None and s.heart_rate is not None:
                a[3].append(float(s.breath_rate))
                a[4].append(float(s.heart_rate))

    def _set_online(self, online: bool, from_event: bool = False) -> None:
        with self._online_lock:
            if self.online == online:
                return
            self.online = online
            self._last_change = time.monotonic()
            if online:
                self.ever_online = True
                s = self.brain.state
                # from an event: the event itself says who is there
                self._record(stats.ROBOT_ONLINE, present=bool(s.present) and not from_event,
                             seated=bool(s.seated) and not from_event)
            else:
                self._record(stats.ROBOT_OFFLINE)

    def _flush_sample(self) -> None:
        n, pres, seat, br, hr = self._acc
        if n and self._bucket is not None:
            reliable = len(br) * 2 >= n                 # vitals for at least half of the minute
            self.store.add_sample(self._bucket, pres / n, seat / n,
                                  sum(br) / len(br) if reliable else None,
                                  sum(hr) / len(hr) if reliable else None)
        self._acc = [0, 0.0, 0.0, [], []]


def _is_ip(host: str) -> bool:
    try:
        ipaddress.ip_address(host.strip("[]"))
        return True
    except ValueError:
        return False


# ==================================================================================== HTTP

class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Marvin"
    sys_version = ""

    @property
    def app(self) -> UIServer:
        return self.server.app                     # type: ignore[attr-defined]

    def log_message(self, fmt, *args):             # quiet: the console is for the robot
        log.debug("%s " + fmt, self.address_string(), *args)

    # ------------------------------------------------------------ access

    def _client_is_local(self) -> bool:
        try:
            ip = ipaddress.ip_address(self.client_address[0])
        except ValueError:
            return False
        if getattr(ip, "ipv4_mapped", None):
            ip = ip.ipv4_mapped
        return ip.is_loopback

    def _host_is_known(self) -> bool:
        """Host header is an IP, localhost, or this machine's name (anti DNS rebinding)."""
        host = (self.headers.get("Host") or "").strip().lower()
        if not host:
            return False
        name = host.rsplit(":", 1)[0] if not host.startswith("[") else host.split("]")[0] + "]"
        if _is_ip(name) or name == "localhost" or name.endswith(".localhost"):
            return True
        me = socket.gethostname().lower().split(".")[0]
        return name.split(".")[0] == me

    def _presented_token(self, query: dict) -> str | None:
        if "token" in query:
            return query["token"][0]
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            return auth[7:].strip()
        c = SimpleCookie()
        try:
            c.load(self.headers.get("Cookie", ""))
        except Exception:
            return None
        return c[COOKIE].value if COOKIE in c else None

    def _access(self, query: dict) -> str:
        """"ok", "no-key" (a key is needed and missing / wrong) or "bad-host"."""
        token = self.app.token
        given = self._presented_token(query)
        if token and given and hmac.compare_digest(given.encode(), token.encode()):
            return "ok"
        if not token or self._client_is_local():
            return "ok" if self._host_is_known() else "bad-host"
        return "no-key"

    # ------------------------------------------------------------ responses

    def _send(self, status: int, body: bytes, ctype: str, headers: dict | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store" if not ctype.startswith(("text/css", "text/javascript"))
                         else "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        if ctype.startswith("text/html"):
            self.send_header("Content-Security-Policy",
                             "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                             "connect-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
                             "form-action 'self'")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, obj, status: int = 200) -> None:
        self._send(status, json.dumps(obj, separators=(",", ":")).encode(), "application/json")

    def _error(self, status: int, message: str) -> None:
        self._json({"error": message}, status)

    def _denied(self, why: str, path: str) -> None:
        if why == "bad-host":
            return self._error(HTTPStatus.FORBIDDEN, "unknown host name: open the app by IP address or localhost")
        if path.startswith("/api/") or not path.endswith(("/", ".html")):
            return self._error(HTTPStatus.UNAUTHORIZED, "access key required")
        self._send(HTTPStatus.UNAUTHORIZED, _LOCKED_PAGE, "text/html; charset=utf-8")

    # ------------------------------------------------------------ routing

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlsplit(self.path)
        path, query = url.path, parse_qs(url.query)
        why = self._access(query)
        public = path.startswith("/static/") or path in ("/icon.png", "/favicon.ico", "/manifest.webmanifest")
        if why != "ok" and not (public and why == "no-key"):
            return self._denied(why, path)
        key = self.app.token
        if (why == "ok" and key and "token" in query and not path.startswith("/api/")
                and hmac.compare_digest(query["token"][0].encode(), key.encode())):
            # remember the key in a cookie and drop it from the address bar
            rest = {k: v for k, v in query.items() if k != "token"}
            loc = path + (f"?{urlencode(rest, doseq=True)}" if rest else "")
            cookie = f"{COOKIE}={self.app.token}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict"
            return self._send(HTTPStatus.SEE_OTHER, b"", "text/plain", {"Location": loc, "Set-Cookie": cookie})
        try:
            self._route_get(path, query)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except ValueError as e:
            self._error(HTTPStatus.BAD_REQUEST, str(e))

    def _route_get(self, path: str, query: dict) -> None:
        app = self.app
        if path in ("/", "/index.html"):
            return self._static("index.html")
        if path.startswith("/static/"):
            return self._static(path[len("/static/"):])
        if path == "/manifest.webmanifest":
            return self._static("manifest.webmanifest")
        if path in ("/face.png", "/api/face"):
            data = app.face_png()
            if data is None:
                return self._error(HTTPStatus.SERVICE_UNAVAILABLE, "face rendering unavailable")
            return self._send(200, data, "image/png")
        if path in ("/icon.png", "/favicon.ico"):
            data = app.icon_png()
            if data is None:
                return self._error(HTTPStatus.NOT_FOUND, "no icon")
            return self._send(200, data, "image/png", {"Cache-Control": "max-age=86400"})
        if path == "/api/state":
            return self._json(app.api_state())
        if path == "/api/day":
            d = _query_date(query, app.clock())
            return self._json(app.store.day(d, app.clock(), live=True))
        if path == "/api/history":
            days = _query_int(query, "days", 7, 1, 62)
            last = _query_date(query, app.clock())
            return self._json({"days": app.store.history(last, days, app.clock(), live=True)})
        if path == "/api/events":
            since = _query_int(query, "since", 0, 0, 2**62)
            limit = _query_int(query, "limit", 50, 1, 500)
            exclude = stats.VITALS_KINDS if query.get("quiet", ["0"])[0] in ("1", "true") else ()
            evs = app.store.recent(limit, since, exclude)
            return self._json({"events": [e.to_json() for e in evs]})
        if path == "/api/settings":
            return self._json(self._settings_payload())
        if path == "/api/stream":
            return self._stream()
        self._error(HTTPStatus.NOT_FOUND, "not found")

    def do_POST(self):
        url = urlsplit(self.path)
        why = self._access(parse_qs(url.query))
        if why != "ok":
            return self._denied(why, url.path)
        if url.path != "/api/settings":
            return self._error(HTTPStatus.NOT_FOUND, "not found")
        origin = self.headers.get("Origin")
        if origin and urlsplit(origin).netloc.lower() != (self.headers.get("Host") or "").lower():
            return self._error(HTTPStatus.FORBIDDEN, "cross-origin request")
        if (self.headers.get("Content-Type") or "").split(";")[0].strip() != "application/json":
            return self._error(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "send JSON")
        n = int(self.headers.get("Content-Length") or 0)
        if n > 16384:
            return self._error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "too large")
        try:
            update = json.loads(self.rfile.read(n) or b"{}")
            self.app.update_settings(update)
        except (ValueError, json.JSONDecodeError) as e:
            return self._error(HTTPStatus.BAD_REQUEST, str(e))
        self._json(self._settings_payload())

    def _settings_payload(self) -> dict:
        app = self.app
        return {"settings": app.settings, "defaults": app._defaults(),
                "about": {"data_dir": str(Path(app.store.path).parent) if app.store.path != ":memory:" else None,
                          "access_key": bool(app.token)}}

    def _static(self, name: str) -> None:
        ctype = STATIC_TYPES.get(name)
        if ctype is None:
            return self._error(HTTPStatus.NOT_FOUND, "not found")
        try:
            body = (STATIC / name).read_bytes()
        except OSError:
            return self._error(HTTPStatus.NOT_FOUND, "not found")
        self._send(200, body, ctype)

    def _stream(self) -> None:
        app = self.app
        self.close_connection = True
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        q = app.hub.subscribe()
        try:
            self._sse("hello", {"settings": app.settings}, retry=3000)
            self._sse("today", app.today())
            next_state = 0.0
            while not app._stop.is_set():
                now = time.monotonic()
                if now >= next_state:
                    self._sse("state", app.snapshot())
                    next_state = now + STATE_PERIOD_S
                try:
                    kind, payload = q.get(timeout=max(0.01, next_state - time.monotonic()))
                except queue.Empty:
                    continue
                self._sse(kind, payload)
                if kind == "event":
                    self._sse("today", app.today())
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            app.hub.unsubscribe(q)

    def _sse(self, kind: str, payload, retry: int | None = None) -> None:
        msg = (f"retry: {retry}\n" if retry else "") + f"event: {kind}\ndata: {json.dumps(payload, separators=(',', ':'))}\n\n"
        self.wfile.write(msg.encode())
        self.wfile.flush()


def _query_date(query: dict, now: float) -> date:
    if "date" not in query:
        return stats.local_day(now)
    try:
        return date.fromisoformat(query["date"][0])
    except ValueError:
        raise ValueError("date must be YYYY-MM-DD") from None


def _query_int(query: dict, key: str, default: int, lo: int, hi: int) -> int:
    if key not in query:
        return default
    try:
        v = int(query[key][0])
    except ValueError:
        raise ValueError(f"{key} must be an integer") from None
    return max(lo, min(hi, v))


_LOCKED_PAGE = b"""<!doctype html>
<!-- SPDX-License-Identifier: MIT -->
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Marvin</title><link rel="stylesheet" href="/static/style.css"></head>
<body class="locked"><main class="locked-card">
<h1>Marvin</h1>
<p>This app needs its access key.</p>
<p class="muted">Open the address that <code>marvin-host</code> printed when it started, the one that ends
with <code>?token=</code>. Your browser will remember it.</p>
</main></body></html>
"""
