"""UDP receiver: answers the robot's HELLO, validates frames and hands them to a sink.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import socket
import threading
import time
from dataclasses import dataclass, field

import numpy as np

from . import frames, ld2450, ldrobot, protocol

log = logging.getLogger("marvin.receiver")


@dataclass
class Stats:
    datagrams: int = 0
    lidar_packets: int = 0
    crc_errors: int = 0
    radar_frames: int = 0
    lost: int = 0
    bad: int = 0


@dataclass
class Device:
    hello: protocol.Hello
    addr: tuple
    last_seq: int | None = None
    stats: Stats = field(default_factory=Stats)


class Sink:
    """Override the methods you need. Times are device microseconds."""

    def on_hello(self, dev: Device) -> None: ...
    def on_scan(self, dev: Device, t_us: int, points: np.ndarray, intensities: np.ndarray, speed_dps: int) -> None: ...
    def on_targets(self, dev: Device, t_us: int, targets: list[ld2450.Target], points: list[tuple]) -> None: ...
    def on_vitals(self, dev: Device, t_us: int, vitals: protocol.Vitals) -> None: ...
    def on_log(self, dev: Device, t_us: int, text: str) -> None: ...
    # AUDIO_IN: seq is the sample index of pcm[0] (u32), pcm int16 at 16 kHz (see protocol.AudioIn)
    def on_audio(self, dev: Device, t_us: int, seq: int, pcm: np.ndarray) -> None: ...


class Receiver:
    def __init__(self, sink: Sink, port: int = protocol.HOST_PORT, bind: str = "0.0.0.0"):
        self.sink = sink
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
        self.sock.bind((bind, port))
        self.sock.settimeout(0.2)
        self.devices: dict[tuple, Device] = {}
        self.t0 = time.monotonic()
        self._tx_seq: dict[tuple, int] = {}      # per-destination sequence numbers for send()
        self._tx_lock = threading.Lock()
        # one lidar revolution is assembled from many packets
        self._rev: dict[tuple, _Revolution] = {}
        # extra AUDIO_IN consumers (robot_audio.RobotMicSource), called like Sink.on_audio
        self._audio_listeners: list = []

    def add_audio_listener(self, fn) -> None:
        """Also call fn(dev, t_us, seq, pcm) for every AUDIO_IN (from the receiving thread)."""
        self._audio_listeners.append(fn)

    def remove_audio_listener(self, fn) -> None:
        if fn in self._audio_listeners:
            self._audio_listeners.remove(fn)

    def serve(self, duration: float | None = None, stop=None) -> None:
        start = time.monotonic()
        while (duration is None or time.monotonic() - start < duration) and not (stop and stop.is_set()):
            try:
                data, addr = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            self.handle(data, addr)

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

    def handle(self, data: bytes, addr: tuple) -> None:
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
            self.sink.on_targets(dev, hdr.t_us, targets, pts)
        elif hdr.type == protocol.VITALS:
            try:
                v = protocol.Vitals.decode(payload)
            except Exception:
                s.bad += 1
                return
            self.sink.on_vitals(dev, hdr.t_us, v)
        elif hdr.type == protocol.LOG:
            self.sink.on_log(dev, hdr.t_us, payload.decode(errors="replace"))
        elif hdr.type == protocol.AUDIO_IN:
            try:
                a = protocol.AudioIn.decode(payload)
            except protocol.ProtocolError:
                s.bad += 1
                return
            for fn in list(self._audio_listeners):
                fn(dev, hdr.t_us, a.index, a.pcm)
            self.sink.on_audio(dev, hdr.t_us, a.index, a.pcm)

    def _hello(self, hdr, payload, addr) -> None:
        try:
            hello = protocol.Hello.decode(payload)
        except Exception:
            return
        ack = protocol.pack(protocol.HOST_ACK, 0, 0, int((time.monotonic() - self.t0) * 1e6).to_bytes(8, "little"))
        self.sock.sendto(ack, addr)
        dev = self.devices.get(addr)
        if dev is None:
            dev = self.devices[addr] = Device(hello, addr)
            log.info("%s (%s, %s%s) at %s:%d", hello.device_name, protocol.BOARDS.get(hello.board, hello.board),
                     hello.firmware, ", simulated" if hello.simulated else "", *addr)
            self.sink.on_hello(dev)
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
                ang, dist, inten = (np.concatenate(v) for v in (rev.angles, rev.dist, rev.inten))
                keep = (dist >= frames.LIDAR.min_mm) & (dist <= frames.LIDAR.max_mm)
                self.sink.on_scan(dev, hdr.t_us, frames.lidar_to_device(ang, dist), inten[keep], rev.speed)
                rev.angles, rev.dist, rev.inten = [], [], []
            rev.angles.append(p.angles_deg)
            rev.dist.append(p.distances_mm)
            rev.inten.append(p.intensities)
            rev.last_start, rev.speed = start, p.speed_dps


@dataclass
class _Revolution:
    angles: list = field(default_factory=list)
    dist: list = field(default_factory=list)
    inten: list = field(default_factory=list)
    last_start: float | None = None
    speed: int = 0
