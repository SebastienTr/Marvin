"""Record the robot's raw datagrams to a `.mvrec` file and replay them later, exactly as live.

A recording holds what arrived on the UDP socket, byte for byte, with the host time of arrival.
Replaying feeds the same bytes through `Receiver.handle`, so the brain, the viewer and the
console see the same calls as they did live. The brain only uses the device clock carried in the
frames, so a replay gives the same events at any speed.

File format, version 1 (little-endian; the whole file may be gzip-compressed, detected on read):

    header  "MVREC" (5 bytes), u8 version = 1, u32 n, then n bytes of UTF-8 JSON:
            {"host_start_unix": float, "host_start": ISO 8601 str, "marvin_host": str,
             "protocol": int, ...}                        (free-form; unknown keys are kept)
    record  u8 kind, u64 t_us, u16 n, then n bytes of body, repeated to the end of the file.
            t_us: host monotonic clock, microseconds since the recording started.
      kind 1  ADDRESS   u16 id + UTF-8 "ip:port": the sender behind `id` (written before its first datagram)
      kind 2  DATAGRAM  u16 id + the datagram exactly as received
      kind 3  DEVICE    UTF-8 JSON describing a robot, written when its HELLO is first handled:
                        {"address", "device_name", "board", "firmware", "simulated"}
      other kinds are skipped (room for later additions without a version change)

A file cut short (host killed, disk full) is read up to its last complete record.

    marvin-host run --record FILE.mvrec[.gz]
    marvin-host replay FILE.mvrec [--speed 2 | --fast] [--loop] [--no-viewer] [--info]

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import atexit
import datetime as _dt
import gzip
import json
import logging
import math
import os
import socket
import struct
import threading
import time
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Callable, Iterator

from . import __version__, ld2450, protocol
from .receiver import Receiver, Sink

log = logging.getLogger("marvin.record")

MAGIC = b"MVREC"
VERSION = 1
_HEAD = struct.Struct("<5sBI")
_REC = struct.Struct("<BQH")
_ID = struct.Struct("<H")
ADDRESS, DATAGRAM, DEVICE = 1, 2, 3
MAX_META = 1 << 20
FLUSH_S = 1.0                      # the writer flushes at least this often


class RecordingError(ValueError):
    """Not a recording, an unsupported version, or a corrupt record."""


def _addr_str(addr: tuple) -> str:
    return f"{addr[0]}:{addr[1]}"


def _parse_addr(s: str) -> tuple:
    host, _, port = s.rpartition(":")
    return host, int(port)


# ------------------------------------------------------------------------------------------ writing

class RecordingWriter:
    """Appends records to a new `.mvrec` file. Thread-safe. gzip when the name ends in `.gz` (or `compress`)."""

    def __init__(self, path: str | os.PathLike, meta: dict | None = None, compress: bool | None = None):
        self.path = Path(path)
        if compress is None:
            compress = self.path.suffix == ".gz"
        self._raw = open(self.path, "wb")
        self._f: BinaryIO = gzip.GzipFile(fileobj=self._raw, mode="wb", compresslevel=6) if compress else self._raw
        self._lock = threading.Lock()
        self._t0 = time.monotonic()
        self._ids: dict[tuple, int] = {}
        self._last_flush = self._t0
        self.records = 0
        now = time.time()
        head = {"host_start_unix": now,
                "host_start": _dt.datetime.fromtimestamp(now, _dt.timezone.utc).astimezone().isoformat(timespec="seconds"),
                "marvin_host": __version__, "protocol": protocol.VERSION}
        head.update(meta or {})
        blob = json.dumps(head).encode()
        self._f.write(_HEAD.pack(MAGIC, VERSION, len(blob)) + blob)
        self.closed = False
        atexit.register(self.close)

    def _record(self, kind: int, body: bytes, t: float | None) -> None:
        t_us = max(0, int(((time.monotonic() if t is None else t) - self._t0) * 1e6))
        self._f.write(_REC.pack(kind, t_us, len(body)) + body)
        self.records += 1

    def write_datagram(self, data: bytes, addr: tuple, t: float | None = None) -> None:
        """One received datagram. `t`: host monotonic time of arrival (default: now)."""
        if len(data) > 0xFFFF - _ID.size:
            return
        with self._lock:
            if self.closed:
                return
            addr = tuple(addr[:2])
            i = self._ids.get(addr)
            if i is None:
                if len(self._ids) > 0xFFFF:
                    return
                i = self._ids[addr] = len(self._ids)
                self._record(ADDRESS, _ID.pack(i) + _addr_str(addr).encode(), t)
            self._record(DATAGRAM, _ID.pack(i) + data, t)
            self._maybe_flush()

    def write_device(self, info: dict, t: float | None = None) -> None:
        with self._lock:
            if not self.closed:
                self._record(DEVICE, json.dumps(info).encode()[:0xFFFF], t)

    def _maybe_flush(self) -> None:
        now = time.monotonic()
        if now - self._last_flush >= FLUSH_S:
            self._flush()
            self._last_flush = now

    def _flush(self) -> None:
        self._f.flush()          # GzipFile.flush() is a sync flush: everything so far can be read back
        self._raw.flush()

    def flush(self) -> None:
        with self._lock:
            if not self.closed:
                self._flush()

    def close(self) -> None:
        with self._lock:
            if self.closed:
                return
            self.closed = True
            self._f.close()
            if self._f is not self._raw:
                self._raw.close()
        atexit.unregister(self.close)

    def __enter__(self) -> RecordingWriter:
        return self

    def __exit__(self, *exc) -> None:
        self.close()


def device_info(dev) -> dict:
    h = dev.hello
    return {"address": _addr_str(dev.addr), "device_name": h.device_name, "board": h.board,
            "firmware": h.firmware, "simulated": h.simulated}


class RecordingReceiver(Receiver):
    """A `Receiver` that writes every datagram to a recording before handling it."""

    def __init__(self, sink: Sink, path: str | os.PathLike, port: int = protocol.HOST_PORT, bind: str = "0.0.0.0",
                 meta: dict | None = None, compress: bool | None = None):
        super().__init__(sink, port=port, bind=bind)
        self.recorder = RecordingWriter(path, meta=meta, compress=compress)

    def handle(self, data: bytes, addr: tuple) -> None:
        t = time.monotonic()
        self.recorder.write_datagram(data, addr, t)
        known = addr in self.devices
        super().handle(data, addr)
        if not known and addr in self.devices:
            self.recorder.write_device(device_info(self.devices[addr]), t)

    def close(self) -> None:
        self.recorder.close()


# ------------------------------------------------------------------------------------------ reading

@dataclass
class Record:
    t: float            # seconds since the recording started (host clock)
    addr: tuple         # sender (ip, port)
    data: bytes         # the datagram


def _open(path: str | os.PathLike) -> BinaryIO:
    f = open(path, "rb")
    gz = f.read(2) == b"\x1f\x8b"
    f.seek(0)
    return gzip.GzipFile(fileobj=f, mode="rb") if gz else f


def _read(f: BinaryIO, n: int) -> bytes:
    """Up to n bytes; less only at the end of the data (a gzip stream cut short counts as the end)."""
    try:
        return f.read(n)
    except (EOFError, OSError, zlib.error):     # gzip.BadGzipFile is an OSError
        return b""


class RecordingReader:
    """Iterates over a recording's datagrams. `meta` is the header, `devices` the robots seen so far.

    Raises `RecordingError` for a file that is not a recording or has a corrupt record; a file cut
    short ends at its last complete record and sets `truncated`.
    """

    def __init__(self, path: str | os.PathLike):
        self.path = Path(path)
        self._f = _open(self.path)
        try:
            head = _read(self._f, _HEAD.size)
            if len(head) < _HEAD.size or not head.startswith(MAGIC):
                raise RecordingError(f"{self.path}: not a Marvin recording")
            _, self.version, n = _HEAD.unpack(head)
            if self.version != VERSION:
                raise RecordingError(f"{self.path}: unsupported recording version {self.version}")
            if n > MAX_META:
                raise RecordingError(f"{self.path}: corrupt header")
            blob = _read(self._f, n)
            try:
                self.meta: dict = json.loads(blob.decode())
            except (UnicodeDecodeError, json.JSONDecodeError) as e:
                raise RecordingError(f"{self.path}: corrupt header") from e
        except BaseException:
            self._f.close()
            raise
        self.devices: list[dict] = []
        self.truncated = False
        self.records = 0

    def close(self) -> None:
        self._f.close()

    def __enter__(self) -> RecordingReader:
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def __iter__(self) -> Iterator[Record]:
        addrs: dict[int, tuple] = {}
        f = self._f
        while True:
            head = _read(f, _REC.size)
            if not head:
                return
            if len(head) < _REC.size:
                break
            kind, t_us, n = _REC.unpack(head)
            body = _read(f, n)
            if len(body) < n:
                break
            self.records += 1
            if kind == DATAGRAM:
                if n < _ID.size:
                    raise RecordingError(f"{self.path}: corrupt record {self.records}")
                (i,) = _ID.unpack_from(body)
                addr = addrs.get(i)
                if addr is None:
                    raise RecordingError(f"{self.path}: record {self.records} comes from an unknown sender")
                yield Record(t_us / 1e6, addr, body[_ID.size:])
            elif kind == ADDRESS:
                try:
                    (i,) = _ID.unpack_from(body)
                    addrs[i] = _parse_addr(body[_ID.size:].decode())
                except (struct.error, UnicodeDecodeError, ValueError) as e:
                    raise RecordingError(f"{self.path}: corrupt record {self.records}") from e
            elif kind == DEVICE:
                try:
                    self.devices.append(json.loads(body.decode()))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    log.warning("%s: unreadable device record %d, skipped", self.path, self.records)
            # other kinds: from a later version, skipped
        self.truncated = True
        log.warning("%s: truncated after %d records", self.path, self.records)


def summarize(path: str | os.PathLike) -> dict:
    """Duration, datagram count and robots of a recording (reads the whole file)."""
    with RecordingReader(path) as r:
        n = size = 0
        t = 0.0
        senders = set()
        for rec in r:
            n += 1
            size += len(rec.data)
            t = rec.t
            senders.add(rec.addr)
        return {"meta": r.meta, "duration_s": t, "datagrams": n, "bytes": size, "senders": len(senders),
                "devices": r.devices, "truncated": r.truncated}


# ------------------------------------------------------------------------------------------ replay

class _NullSocket:
    """Stands in for the UDP socket during a replay: nothing is sent back to a robot."""

    def sendto(self, data, addr) -> int:
        return len(data)

    def recvfrom(self, n):
        time.sleep(0.2)
        raise socket.timeout

    def getsockname(self):
        return ("127.0.0.1", 0)

    def setsockopt(self, *a) -> None: ...
    def settimeout(self, *a) -> None: ...
    def close(self) -> None: ...


class ReplayReceiver(Receiver):
    """A `Receiver` with no network: datagrams come from `handle()` only, replies are dropped."""

    def __init__(self, sink: Sink):
        super().__init__(sink, port=0, bind="127.0.0.1")
        self.sock.close()
        self.sock = _NullSocket()


def replay(path: str | os.PathLike, sink: Sink, speed: float | None = 1.0, loop: bool = False,
           stop: threading.Event | None = None, receiver: Receiver | None = None) -> Receiver:
    """Feed a recording through `Receiver.handle` into `sink`.

    speed: 1.0 = real time, 2.0 = twice as fast, None / 0 / inf = as fast as possible.
    loop: start over at the end (the device clock jumps back, as when a robot reboots).
    stop: set it to end the replay early. Returns the receiver (its devices and their stats).
    """
    rx = receiver or ReplayReceiver(sink)
    fast = not speed or math.isinf(speed)
    while True:
        start = time.monotonic()
        with RecordingReader(path) as reader:
            for rec in reader:
                if stop is not None and stop.is_set():
                    return rx
                if not fast:
                    delay = rec.t / speed - (time.monotonic() - start)
                    if delay > 0 and stop is not None and stop.wait(delay):
                        return rx
                    elif delay > 0 and stop is None:
                        time.sleep(delay)
                rx.handle(rec.data, rec.addr)
        if not loop:
            return rx


# ------------------------------------------------------------------------------------------ simulation

SIM_ADDR = ("192.0.2.1", protocol.DEVICE_PORT)     # TEST-NET-1: a simulated robot's address


def simulated_datagrams(seconds: float, dev=None, start: float = 0.0) -> Iterator[tuple[float, bytes]]:
    """What `SimDevice.run` sends once linked, as (scene time s, datagram), on a virtual clock: no
    waiting, deterministic. HELLO every 2 s, lidar at its real rate, LD2450 + VITALS at 10 Hz."""
    from . import ldrobot, sim
    dev = dev or sim.SimDevice(host="127.0.0.1")
    pkt_rate = sim.LIDAR_RATE[dev.model] / ldrobot.POINTS
    step = 0.005
    angle = 0.0
    sent = int(start * pkt_rate)
    next_hello = next_radar = start
    seq = 0

    def pack(t, typ, payload):
        nonlocal seq
        d = protocol.pack(typ, seq, int(t * 1e6), payload)
        seq += 1
        return t, d

    for i in range(int(round(seconds / step))):
        t = start + i * step
        if t >= next_hello:
            h = protocol.Hello(dev.mac, 255, protocol.FLAG_SIMULATED, -40, int(t * 1000), "sim-0.1.0")
            yield pack(t, protocol.HELLO, h.encode())
            next_hello = t + 2.0
        due = int(t * pkt_rate) - sent
        while due >= dev.batch:
            pkts, angle = dev.lidar_packets(t, angle, dev.batch)
            yield pack(t, protocol.LIDAR, bytes([dev.model]) + b"".join(pkts))
            sent += dev.batch
            due -= dev.batch
        if t >= next_radar:
            yield pack(t, protocol.LD2450, ld2450.build(sim.ld2450_targets(t)))
            yield pack(t, protocol.VITALS, sim.vitals(t).encode())
            next_radar = t + 0.1


def write_simulated(path: str | os.PathLike, seconds: float, start: float = 0.0, **sim_options) -> Path:
    """Write a recording of the simulated robot (`SimDevice` options, e.g. model=2, yaw_offset_deg=30),
    much faster than real time. Handy for tests and for trying the tools without hardware."""
    from . import sim
    dev = sim.SimDevice(host="127.0.0.1", **sim_options)
    dev.sock.close()
    with RecordingWriter(path, meta={"simulated": True, "sim_options": sim_options}) as w:
        t0 = w._t0
        for t, data in simulated_datagrams(seconds, dev, start):
            w.write_datagram(data, SIM_ADDR, t0 + (t - start))
    return Path(path)


# ------------------------------------------------------------------------------------------ command line

def add_run_arguments(run_parser: argparse.ArgumentParser) -> None:
    """`marvin-host run --record FILE.mvrec`."""
    run_parser.add_argument("--record", metavar="FILE.mvrec",
                            help="also record the raw datagrams to this file (gzip if it ends in .gz)")


def make_receiver(args: argparse.Namespace, sink: Sink, **kw) -> Receiver:
    """The `run` receiver: recording when `--record` was given. Call `close_receiver` when done."""
    if getattr(args, "record", None):
        print(f"recording to {args.record}")
        return RecordingReceiver(sink, args.record, **kw)
    return Receiver(sink, **kw)


def close_receiver(rx: Receiver) -> None:
    if isinstance(rx, RecordingReceiver):
        rx.close()


def add_cli(subparsers) -> argparse.ArgumentParser:
    """`marvin-host replay FILE.mvrec [--speed X | --fast] [--loop] [--no-viewer] [--save FILE.rrd] [--info]`."""
    p = subparsers.add_parser("replay", help="play a recording back through the brain and the viewer")
    p.add_argument("file", help="a .mvrec recording (marvin-host run --record)")
    p.add_argument("--speed", type=float, default=1.0, help="playback speed, 1 = real time (default)")
    p.add_argument("--fast", action="store_true", help="as fast as possible")
    p.add_argument("--loop", action="store_true", help="start over at the end")
    p.add_argument("--no-viewer", action="store_true", help="console summary only")
    p.add_argument("--save", help="write the viewer's output to a .rrd file instead of opening the viewer")
    p.add_argument("--info", action="store_true", help="print what the recording holds and exit")
    return p


def run_replay(args: argparse.Namespace, make_sink: Callable[[], Sink]) -> None:
    """The `replay` command. `make_sink()` builds the same sink chain as `run` (brain, console, viewer)."""
    if args.info:
        s = summarize(args.file)
        m = s["meta"]
        print(f"{args.file}: recorded {m.get('host_start', '?')}, {s['duration_s']:.1f} s, {s['datagrams']} datagrams "
              f"({s['bytes'] / 1e6:.1f} MB) from {s['senders']} sender(s){', TRUNCATED' if s['truncated'] else ''}")
        for d in s["devices"]:
            print(f"  {d.get('device_name')} at {d.get('address')}: {protocol.BOARDS.get(d.get('board'), d.get('board'))}, "
                  f"firmware {d.get('firmware')}{', simulated' if d.get('simulated') else ''}")
        return
    speed = None if args.fast else args.speed
    print(f"replaying {args.file}" + (" as fast as possible" if speed is None else f" at x{speed:g}")
          + (", looping" if args.loop else ""))
    rx = replay(args.file, make_sink(), speed=speed, loop=args.loop)
    for d in rx.devices.values():
        s = d.stats
        print(f"{d.hello.device_name}: {s.datagrams} datagrams, {s.lidar_packets} lidar packets, "
              f"{s.radar_frames} radar frames, lost {s.lost}, crc {s.crc_errors}")
