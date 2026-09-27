"""UDP receiver: answers the robot's HELLO, validates frames and hands them to a sink.

Two threads while `serve()` runs:

- the socket thread reads datagrams, checks the header, answers HELLO with HOST_ACK right away,
  keeps the per-device link statistics, parses the payload (lidar revolutions, radar targets,
  vitals...) and queues one sink call per result. It never waits for a sink, so the robot always
  gets its HOST_ACK in time and the kernel buffer never fills up behind a slow viewer or model.
- the dispatch thread makes the sink calls, in the order the datagrams came in.

If the sinks fall behind, the oldest lidar scans are dropped first (a newer revolution replaces
them); HELLO, LOG, VITALS, LD2450 and AUDIO_IN calls are dropped only when the whole queue is full.
Drops are counted per callback in `Device.stats.shed`, and `Receiver.timings()` tells how long each
callback takes and how long calls wait in the queue.

`handle()` outside `serve()` (tests, replay) calls the sink directly, on the caller's thread, so a
replay stays deterministic.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import collections
import logging
import socket
import threading
import time
from dataclasses import dataclass, field

import numpy as np

from . import frames, ld2450, ldrobot, protocol

log = logging.getLogger("marvin.receiver")

RCVBUF_BYTES = 4 << 20          # asked for; the OS may grant less (Linux: net.core.rmem_max, macOS: kern.ipc.maxsockbuf)
SLOW_CALLBACK_S = 0.2           # a sink callback longer than this is reported
WARN_EVERY_S = 10.0             # at most one warning of each kind per this many seconds


@dataclass
class Stats:
    datagrams: int = 0
    lidar_packets: int = 0
    crc_errors: int = 0
    radar_frames: int = 0
    lost: int = 0
    bad: int = 0
    # sink calls dropped because the sinks were behind, by callback ("scan", "targets", ...)
    shed: dict = field(default_factory=dict)


@dataclass
class Device:
    hello: protocol.Hello
    addr: tuple
    last_seq: int | None = None
    stats: Stats = field(default_factory=Stats)


class Sink:
    """Override the methods you need. Times are device microseconds.

    While `Receiver.serve()` runs, the methods are called from the receiver's dispatch thread (one
    thread, in the order the datagrams arrived), not from the socket thread.
    """

    def on_hello(self, dev: Device) -> None: ...
    def on_scan(self, dev: Device, t_us: int, points: np.ndarray, intensities: np.ndarray, speed_dps: int) -> None: ...
    def on_targets(self, dev: Device, t_us: int, targets: list[ld2450.Target], points: list[tuple]) -> None: ...
    def on_vitals(self, dev: Device, t_us: int, vitals: protocol.Vitals) -> None: ...
    def on_log(self, dev: Device, t_us: int, text: str) -> None: ...
    # AUDIO_IN: seq is the sample index of pcm[0] (u32), pcm int16 at 16 kHz (see protocol.AudioIn)
    def on_audio(self, dev: Device, t_us: int, seq: int, pcm: np.ndarray) -> None: ...


@dataclass
class _Timing:
    calls: int = 0
    total_s: float = 0.0
    max_s: float = 0.0
    slow: int = 0               # calls longer than SLOW_CALLBACK_S
    max_wait_s: float = 0.0     # longest time a call waited in the queue


class Receiver:
    def __init__(self, sink: Sink, port: int = protocol.HOST_PORT, bind: str = "0.0.0.0",
                 queue_size: int = 2000, max_scan_lag_s: float = 0.3, max_pending_scans: int = 10):
        """While serving, at most `queue_size` sink calls wait for the dispatch thread. A lidar scan is
        dropped when it has waited more than `max_scan_lag_s`, or to keep `max_pending_scans` at most."""
        self.sink = sink
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        for size in (RCVBUF_BYTES, 1 << 20):
            try:
                self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, size)
                break
            except OSError:          # macOS refuses more than kern.ipc.maxsockbuf allows
                continue
        self.sock.bind((bind, port))
        self.sock.settimeout(0.2)
        log.debug("UDP receive buffer: %d bytes", self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF))
        self.devices: dict[tuple, Device] = {}
        self.t0 = time.monotonic()
        self._tx_seq: dict[tuple, int] = {}      # per-destination sequence numbers for send()
        self._tx_lock = threading.Lock()
        # one lidar revolution is assembled from many packets
        self._rev: dict[tuple, _Revolution] = {}
        # extra AUDIO_IN consumers (robot_audio.RobotMicSource), called like Sink.on_audio
        self._audio_listeners: list = []
        # sink calls waiting for the dispatch thread: (callback name, dev, args, enqueue time)
        self.queue_size = max(1, queue_size)
        self.max_scan_lag_s = max_scan_lag_s
        self.max_pending_scans = max(1, max_pending_scans)
        self._queue: collections.deque = collections.deque()
        self._cond = threading.Condition()
        self._scan_times: collections.deque = collections.deque()   # when each queued scan was queued
        self._worker: threading.Thread | None = None
        self._worker_stop = False
        self._timings: dict[str, _Timing] = {}
        self._timings_lock = threading.Lock()
        self._warned: dict[str, float] = {}
        self._busy: tuple[str, float] | None = None   # the sink call in progress: (callback, perf_counter start)
        self.max_queue_depth = 0

    def add_audio_listener(self, fn) -> None:
        """Also call fn(dev, t_us, seq, pcm) for every AUDIO_IN, from the socket thread: keep it short."""
        self._audio_listeners.append(fn)

    def remove_audio_listener(self, fn) -> None:
        if fn in self._audio_listeners:
            self._audio_listeners.remove(fn)

    # ------------------------------------------------------------------ serving

    def serve(self, duration: float | None = None, stop=None, drain_timeout: float = 2.0) -> None:
        """Receive until `duration` s have passed or `stop` is set, dispatching to the sink from a thread.

        On the way out, the calls still queued are made (for at most `drain_timeout` s, the rest is dropped).
        """
        self._start_worker()
        try:
            start = time.monotonic()
            while (duration is None or time.monotonic() - start < duration) and not (stop and stop.is_set()):
                try:
                    data, addr = self.sock.recvfrom(4096)
                except socket.timeout:
                    continue
                except OSError:              # socket closed under us
                    if stop is not None and stop.is_set():
                        break
                    raise
                self.handle(data, addr)
        finally:
            self._stop_worker(drain_timeout)

    @property
    def serving(self) -> bool:
        """True while the dispatch thread runs: handle() queues the sink calls instead of making them."""
        return self._worker is not None

    def pending(self) -> int:
        """Sink calls waiting for the dispatch thread."""
        return len(self._queue)

    def timings(self) -> dict[str, dict]:
        """Per sink callback: calls, mean and max duration, slow calls, longest wait in the queue (ms)."""
        with self._timings_lock:
            return {name: {"calls": t.calls,
                           "mean_ms": round(1000 * t.total_s / t.calls, 3) if t.calls else 0.0,
                           "max_ms": round(1000 * t.max_s, 3),
                           "slow": t.slow,
                           "max_wait_ms": round(1000 * t.max_wait_s, 3)}
                    for name, t in self._timings.items()}

    def send(self, dev_or_addr: Device | tuple, msg_type: int, payload: bytes = b"") -> None:
        """Send a host -> robot message to a device (or an (ip, port) address), from the listening socket.

        The robot answers HELLOs from its own port, so `dev.addr` is where it listens. Each destination
        gets its own sequence number; the header clock is the host clock (as in HOST_ACK). Thread-safe.
        """
        addr = dev_or_addr.addr if isinstance(dev_or_addr, Device) else tuple(dev_or_addr)
        with self._tx_lock:
            seq = self._tx_seq.get(addr, 0)
            self._tx_seq[addr] = (seq + 1) & 0xFFFFFFFF
        t_us = int((time.monotonic() - self.t0) * 1e6)
        try:
            self.sock.sendto(protocol.pack(msg_type, seq, t_us, payload), addr)
        except OSError as e:                     # unreachable host, network down: drop, like UDP
            log.debug("send to %s failed: %s", addr, e)

    # ------------------------------------------------------------------ socket thread

    def handle(self, data: bytes, addr: tuple) -> None:
        """One datagram. While serving, sink calls are queued; otherwise they are made right here."""
        try:
            hdr, payload = protocol.unpack(data)
        except protocol.ProtocolError as e:
            log.debug("dropped datagram from %s: %s", addr, e)
            return
        if hdr.type == protocol.HELLO:
            self._hello(hdr, payload, addr)
        dev = self.devices.get(addr)
        if dev is None:          # frames before any HELLO: ignore until the device introduces itself
            return
        s = dev.stats
        s.datagrams += 1
        if dev.last_seq is not None:
            gap = (hdr.seq - dev.last_seq - 1) & 0xFFFFFFFF
            if gap < 1_000_000:
                s.lost += gap
        dev.last_seq = hdr.seq
        if hdr.type == protocol.HELLO:
            return
        if hdr.type == protocol.LIDAR:
            self._lidar(dev, addr, hdr, payload)
        elif hdr.type == protocol.LD2450:
            try:
                targets = ld2450.parse(payload)
            except ValueError:
                s.bad += 1
                return
            s.radar_frames += 1
            pts = [frames.ld2450_to_device(t.x_mm, t.y_mm) for t in targets]
            self._submit("targets", dev, (hdr.t_us, targets, pts))
        elif hdr.type == protocol.VITALS:
            try:
                v = protocol.Vitals.decode(payload)
            except Exception:
                s.bad += 1
                return
            self._submit("vitals", dev, (hdr.t_us, v))
        elif hdr.type == protocol.LOG:
            self._submit("log", dev, (hdr.t_us, payload.decode(errors="replace")))
        elif hdr.type == protocol.AUDIO_IN:
            try:
                a = protocol.AudioIn.decode(payload)
            except protocol.ProtocolError:
                s.bad += 1
                return
            for fn in list(self._audio_listeners):     # the microphone must not wait for the sinks
                fn(dev, hdr.t_us, a.index, a.pcm)
            self._submit("audio", dev, (hdr.t_us, a.index, a.pcm))

    def _hello(self, hdr, payload, addr) -> None:
        try:
            hello = protocol.Hello.decode(payload)
        except Exception:
            return
        ack = protocol.pack(protocol.HOST_ACK, 0, 0, int((time.monotonic() - self.t0) * 1e6).to_bytes(8, "little"))
        try:
            self.sock.sendto(ack, addr)
        except OSError as e:
            log.debug("HOST_ACK to %s failed: %s", addr, e)
        dev = self.devices.get(addr)
        if dev is None:
            dev = self.devices[addr] = Device(hello, addr)
            log.info("%s (%s, %s%s) at %s:%d", hello.device_name, protocol.BOARDS.get(hello.board, hello.board),
                     hello.firmware, ", simulated" if hello.simulated else "", *addr)
            self._submit("hello", dev, ())
        else:
            dev.hello = hello

    def _lidar(self, dev, addr, hdr, payload) -> None:
        if not payload:
            return
        rev = self._rev.setdefault(addr, _Revolution())
        for raw in ldrobot.split(payload[1:]):
            try:
                p = ldrobot.parse(raw)
            except ValueError:
                dev.stats.crc_errors += 1
                continue
            dev.stats.lidar_packets += 1
            start = float(p.angles_deg[0])
            if rev.last_start is not None and start < rev.last_start and rev.angles:   # wrapped past 0
                # the points are computed by the dispatch thread, only if the scan is not dropped
                self._submit("scan", dev, (hdr.t_us, rev.angles, rev.dist, rev.inten, rev.speed))
                rev.angles, rev.dist, rev.inten = [], [], []
            rev.angles.append(p.angles_deg)
            rev.dist.append(p.distances_mm)
            rev.inten.append(p.intensities)
            rev.last_start, rev.speed = start, p.speed_dps

    # ------------------------------------------------------------------ dispatch

    def _submit(self, name: str, dev: Device, args: tuple) -> None:
        if self._worker is None:
            self._call(name, dev, args)
            return
        now = time.monotonic()
        with self._cond:
            q = self._queue
            if name == "scan":                        # a newer revolution replaces the stale ones
                st = self._scan_times
                while st and (len(st) >= self.max_pending_scans or now - st[0] > self.max_scan_lag_s):
                    self._shed_oldest(scan_only=True)
                st.append(now)
            if len(q) >= self.queue_size:
                self._shed_oldest(scan_only=False)
            q.append((name, dev, args, now))
            self.max_queue_depth = max(self.max_queue_depth, len(q))
            self._cond.notify()

    def _shed_oldest(self, scan_only: bool) -> None:
        """Drop the oldest queued scan (or, if none and not scan_only, the oldest call). Holds _cond."""
        q = self._queue
        victim = None
        if self._scan_times:
            for i, item in enumerate(q):
                if item[0] == "scan":
                    victim = item
                    del q[i]
                    self._scan_times.popleft()
                    break
        if victim is None and not scan_only and q:
            victim = q.popleft()
            if victim[0] == "scan":
                self._scan_times.popleft()
        if victim is None:
            return
        self._count_shed(victim[0], victim[1])

    def _count_shed(self, name: str, dev: Device) -> None:
        dev.stats.shed[name] = dev.stats.shed.get(name, 0) + 1
        self._warn(f"shed-{name}", "%s is behind (%d calls queued): dropping %s calls, %d so far for %s; %s",
                   self._sink_name(), len(self._queue), name, dev.stats.shed[name], dev.hello.device_name, self._slowest())

    def _sink_name(self) -> str:
        """The sink's class, and its members' for a tee (an object with a `sinks` sequence)."""
        name = type(self.sink).__name__
        members = getattr(self.sink, "sinks", None)
        if isinstance(members, (list, tuple)):
            name += "(" + ", ".join(type(m).__name__ for m in members) + ")"
        return name

    def _slowest(self) -> str:
        busy = self._busy
        if busy is not None:
            return f"on_{busy[0]} running for {1000 * (time.perf_counter() - busy[1]):.0f} ms"
        slowest = max(self.timings().items(), key=lambda kv: kv[1]["max_ms"], default=None)
        return f"slowest: on_{slowest[0]} up to {slowest[1]['max_ms']:.0f} ms" if slowest else ""

    def _start_worker(self) -> None:
        if self._worker is not None:
            raise RuntimeError("Receiver.serve() is already running")
        with self._cond:
            self._worker_stop = False
            self._worker = threading.Thread(target=self._dispatch_loop, name="receiver-dispatch", daemon=True)
            self._worker.start()

    def _stop_worker(self, drain_timeout: float) -> None:
        worker = self._worker
        if worker is None:
            return
        with self._cond:
            self._worker_stop = True
            self._cond.notify_all()
        worker.join(drain_timeout)
        with self._cond:
            left = len(self._queue)
            for name, dev, _, _ in self._queue:      # a sink is still busy: give up on the rest
                dev.stats.shed[name] = dev.stats.shed.get(name, 0) + 1
            self._queue.clear()
            self._scan_times.clear()
            self._worker = None                       # a stuck worker exits after its current call
            self._cond.notify_all()
        if left:
            log.warning("receiver stopped with %d sink calls not made (a sink is still busy)", left)

    def _dispatch_loop(self) -> None:
        me = threading.current_thread()
        while True:
            with self._cond:
                while not self._queue and not self._worker_stop and self._worker is me:
                    self._cond.wait()
                if self._worker is not me or not self._queue:
                    return                            # stopped, and everything was dispatched
                name, dev, args, t_enq = self._queue.popleft()
                wait = time.monotonic() - t_enq
                if name == "scan":
                    self._scan_times.popleft()
                    if self._scan_times and wait > self.max_scan_lag_s:     # stale, and a newer one waits
                        self._count_shed(name, dev)
                        continue
            try:
                self._call(name, dev, args, wait)
            except Exception:
                self._warn(f"error-{name}", "sink on_%s failed", name, exc_info=True)

    def _call(self, name: str, dev: Device, args: tuple, wait: float = 0.0) -> None:
        sink = self.sink
        t = time.perf_counter()
        self._busy = (name, t)
        try:
            if name == "scan":
                t_us, angles, dist, inten, speed = args
                ang, dist, inten = (np.concatenate(v) for v in (angles, dist, inten))
                keep = (dist >= frames.LIDAR.min_mm) & (dist <= frames.LIDAR.max_mm)
                sink.on_scan(dev, t_us, frames.lidar_to_device(ang, dist), inten[keep], speed)
            elif name == "targets":
                sink.on_targets(dev, *args)
            elif name == "vitals":
                sink.on_vitals(dev, *args)
            elif name == "log":
                sink.on_log(dev, *args)
            elif name == "audio":
                sink.on_audio(dev, *args)
            elif name == "hello":
                sink.on_hello(dev)
        finally:
            dt = time.perf_counter() - t
            self._busy = None
            with self._timings_lock:
                tm = self._timings.get(name)
                if tm is None:
                    tm = self._timings[name] = _Timing()
                tm.calls += 1
                tm.total_s += dt
                tm.max_s = max(tm.max_s, dt)
                tm.max_wait_s = max(tm.max_wait_s, wait)
                slow = dt > SLOW_CALLBACK_S
                tm.slow += slow
                n_slow = tm.slow
            if slow:
                self._warn(f"slow-{name}", "%s.on_%s took %.0f ms (%d calls over %.0f ms so far)",
                           self._sink_name(), name, dt * 1000, n_slow, SLOW_CALLBACK_S * 1000)

    def _warn(self, key: str, msg: str, *args, exc_info=False) -> None:
        """log.warning, at most once per WARN_EVERY_S for each key."""
        now = time.monotonic()
        if now - self._warned.get(key, -WARN_EVERY_S) < WARN_EVERY_S:
            return
        self._warned[key] = now
        log.warning(msg, *args, exc_info=exc_info)


@dataclass
class _Revolution:
    angles: list = field(default_factory=list)
    dist: list = field(default_factory=list)
    inten: list = field(default_factory=list)
    last_start: float | None = None
    speed: int = 0
