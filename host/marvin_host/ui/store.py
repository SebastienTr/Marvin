"""The day history: brain events, per-minute samples, the conversation with Marvin and the app
settings, in one SQLite file.

The file lives in the data directory, ``$MARVIN_DATA_DIR`` or ``$XDG_DATA_HOME/marvin`` or
``~/.local/share/marvin``, as ``marvin.db``. Nothing leaves the computer. Deleting the file
erases the history and the settings.

Schema (``PRAGMA user_version = 2``)::

    events       (id INTEGER PRIMARY KEY, ts REAL, kind TEXT, detail TEXT, data TEXT (JSON), t_us INTEGER)
    samples      (ts REAL PRIMARY KEY, present REAL, seated REAL, breath REAL, heart REAL)
    settings     (key TEXT PRIMARY KEY, value TEXT (JSON))
    conversation (id INTEGER PRIMARY KEY, ts REAL, kind TEXT, text TEXT, data TEXT (JSON))   -- since 2

``ts`` is the host's wall clock (Unix seconds); ``t_us`` the robot's clock, kept for reference.
A sample row summarises one minute: the fraction of it someone was present / seated, and the
average breathing and heart rates over the reliable readings (NULL when there were none).
A conversation row is one entry of the Talk panel (voice/control.py): ``kind`` is heard, reply,
ignored or note, ``id`` the entry's id (milliseconds, growing across restarts), ``data`` the rest
of the entry (latency, the context the model was given, why an utterance was not answered...).

Opening a file written by an older version upgrades it in place (``_MIGRATIONS``); nothing that
is already there changes.

The store is shared between threads (the brain's, the web server's): one connection, one lock.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
from datetime import date, timedelta
from pathlib import Path

from ..events import Event
from .stats import StoredEvent, day_bounds, day_stats

SCHEMA_VERSION = 2
LOOKBACK_S = 36 * 3600          # events read before a day, to know the state at midnight

_SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY,
    ts REAL NOT NULL,
    kind TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    data TEXT NOT NULL DEFAULT '{}',
    t_us INTEGER
);
CREATE INDEX IF NOT EXISTS events_ts ON events(ts);
CREATE TABLE IF NOT EXISTS samples (
    ts REAL PRIMARY KEY,
    present REAL NOT NULL,
    seated REAL NOT NULL,
    breath REAL,
    heart REAL
);
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

# user_version -> what brings a file from the version before up to it
_MIGRATIONS = {
    2: """
CREATE TABLE IF NOT EXISTS conversation (
    id INTEGER PRIMARY KEY,
    ts REAL NOT NULL,
    kind TEXT NOT NULL,
    text TEXT NOT NULL DEFAULT '',
    data TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS conversation_ts ON conversation(ts);
""",
}

CONVERSATION_KINDS = ("heard", "reply", "ignored", "note")


def data_dir() -> Path:
    """Where Marvin keeps its data on this computer."""
    env = os.environ.get("MARVIN_DATA_DIR")
    if env:
        return Path(env).expanduser()
    xdg = os.environ.get("XDG_DATA_HOME")
    return (Path(xdg).expanduser() if xdg else Path.home() / ".local" / "share") / "marvin"


def default_path() -> Path:
    return data_dir() / "marvin.db"


class EventStore:
    """SQLite store. ``path=":memory:"`` keeps everything in memory (tests, demos)."""

    def __init__(self, path: str | os.PathLike | None = None):
        self.path = str(default_path() if path is None else path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._db = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
        with self._lock:
            if self.path != ":memory:":
                self._db.execute("PRAGMA journal_mode=WAL")
                self._db.execute("PRAGMA synchronous=NORMAL")
            self._db.executescript(_SCHEMA)
            version = self._db.execute("PRAGMA user_version").fetchone()[0]
            for v in sorted(_MIGRATIONS):
                if v > version:
                    self._db.executescript(_MIGRATIONS[v])
            self._db.execute(f"PRAGMA user_version = {max(version, SCHEMA_VERSION)}")

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ---------------------------------------------------------------- events

    def add(self, kind: str, ts: float, detail: str = "", data: dict | None = None,
            t_us: int | None = None) -> StoredEvent:
        payload = json.dumps(data or {}, default=_json_default)
        with self._lock:
            cur = self._db.execute("INSERT INTO events (ts, kind, detail, data, t_us) VALUES (?, ?, ?, ?, ?)",
                                   (float(ts), kind, detail, payload, t_us))
        return StoredEvent(float(ts), kind, detail, json.loads(payload), cur.lastrowid)

    def add_event(self, event: Event, ts: float) -> StoredEvent:
        """Stores a brain event received at wall-clock time ``ts``."""
        return self.add(event.kind.value, ts, event.detail, event.data, event.t_us)

    def events(self, start: float, end: float) -> list[StoredEvent]:
        """Events with start <= ts < end, oldest first."""
        return self._query("SELECT id, ts, kind, detail, data FROM events WHERE ts >= ? AND ts < ? "
                           "ORDER BY ts, id", (start, end))

    def recent(self, limit: int = 50, since_id: int = 0, exclude: tuple[str, ...] = ()) -> list[StoredEvent]:
        """The latest events (newest first), only those with id > since_id."""
        sql = "SELECT id, ts, kind, detail, data FROM events WHERE id > ?"
        args: list = [since_id]
        if exclude:
            sql += f" AND kind NOT IN ({','.join('?' * len(exclude))})"
            args += list(exclude)
        return self._query(sql + " ORDER BY ts DESC, id DESC LIMIT ?", (*args, limit))

    def _query(self, sql: str, args: tuple) -> list[StoredEvent]:
        with self._lock:
            rows = self._db.execute(sql, args).fetchall()
        return [StoredEvent(ts, kind, detail, json.loads(data), i) for i, ts, kind, detail, data in rows]

    # ---------------------------------------------------------------- samples

    def add_sample(self, ts: float, present: float, seated: float,
                   breath: float | None = None, heart: float | None = None) -> None:
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO samples VALUES (?, ?, ?, ?, ?)",
                             (float(ts), float(present), float(seated), breath, heart))

    def add_samples(self, rows) -> None:
        """Many (ts, present, seated, breath, heart) rows in one transaction."""
        with self._lock:
            self._db.execute("BEGIN")
            self._db.executemany("INSERT OR REPLACE INTO samples VALUES (?, ?, ?, ?, ?)", rows)
            self._db.execute("COMMIT")

    def samples(self, start: float, end: float) -> list[tuple[float, float | None, float | None]]:
        """(ts, breath, heart) for start <= ts < end, oldest first."""
        with self._lock:
            return self._db.execute("SELECT ts, breath, heart FROM samples WHERE ts >= ? AND ts < ? ORDER BY ts",
                                    (start, end)).fetchall()

    # ---------------------------------------------------------------- settings

    def get_settings(self) -> dict:
        with self._lock:
            rows = self._db.execute("SELECT key, value FROM settings").fetchall()
        return {k: json.loads(v) for k, v in rows}

    def set_settings(self, values: dict) -> None:
        with self._lock:
            self._db.execute("BEGIN")
            try:
                for k, v in values.items():
                    self._db.execute("INSERT OR REPLACE INTO settings VALUES (?, ?)", (k, json.dumps(v)))
                self._db.execute("COMMIT")
            except BaseException:
                self._db.execute("ROLLBACK")
                raise

    # ---------------------------------------------------------------- conversation

    def add_conversation(self, entry: dict) -> None:
        """Keeps one conversation entry (``{"id", "t", "kind", "text", ...}``); the same id again
        replaces it."""
        data = {k: v for k, v in entry.items() if k not in ("id", "t", "kind", "text")}
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO conversation (id, ts, kind, text, data) VALUES (?, ?, ?, ?, ?)",
                             (int(entry["id"]), float(entry["t"]), str(entry["kind"]), str(entry.get("text") or ""),
                              json.dumps(data, default=_json_default)))

    def conversation(self, start: float, end: float, limit: int = 1000) -> list[dict]:
        """Entries with start <= ts < end, oldest first (the last ``limit`` of them)."""
        return self._entries("SELECT id, ts, kind, text, data FROM conversation WHERE ts >= ? AND ts < ? "
                             "ORDER BY ts DESC, id DESC LIMIT ?", (start, end, limit))[::-1]

    def conversation_day(self, day: date, limit: int = 1000) -> list[dict]:
        lo, hi = day_bounds(day)
        return self.conversation(lo, hi, limit)

    def search_conversation(self, query: str, limit: int = 100) -> list[dict]:
        """What was said (heard and replies) containing ``query`` (case-insensitive for ASCII
        letters), newest first."""
        q = query.strip()
        if not q:
            return []
        like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return self._entries("SELECT id, ts, kind, text, data FROM conversation WHERE kind IN ('heard', 'reply') "
                             "AND text LIKE ? ESCAPE '\\' ORDER BY ts DESC, id DESC LIMIT ?", (like, limit))

    def max_conversation_id(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COALESCE(MAX(id), 0) FROM conversation").fetchone()[0]

    def _entries(self, sql: str, args: tuple) -> list[dict]:
        with self._lock:
            rows = self._db.execute(sql, args).fetchall()
        out = []
        for i, ts, kind, text, data in rows:
            try:
                extra = json.loads(data)
            except ValueError:
                extra = {}
            out.append({**(extra if isinstance(extra, dict) else {}), "id": i, "t": ts, "kind": kind, "text": text})
        return out

    # ---------------------------------------------------------------- statistics

    def day(self, day: date, now: float, live: bool = True) -> dict:
        """``stats.day_stats`` for one local day, reading what it needs from the database."""
        lo, hi = day_bounds(day)
        return day_stats(self.events(lo - LOOKBACK_S, hi), day, now, live,
                         samples=self.samples(lo - LOOKBACK_S, hi))

    def history(self, last: date, days: int, now: float, live: bool = True) -> list[dict]:
        """Short summaries for the ``days`` days ending with ``last``, oldest first."""
        out = []
        for k in range(days - 1, -1, -1):
            d = self.day(last - timedelta(days=k), now, live)
            out.append({key: d[key] for key in ("date", "seated_s", "present_s", "sessions", "breaks", "longest_s")})
        return out


def _json_default(o):
    if hasattr(o, "item"):          # numpy scalars
        return o.item()
    if isinstance(o, (tuple, set)):
        return list(o)
    return str(o)
