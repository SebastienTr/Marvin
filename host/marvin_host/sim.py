"""A simulated robot: the same UDP frames as the firmware, from the apartment in scene.py.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
import socket
import time
from dataclasses import dataclass

import numpy as np

from . import frames, ld2450, ldrobot, protocol, scene

LIDAR_RATE = {1: 4500, 2: 21600}   # points per second, D500 / D800
SCAN_HZ = 10.0
LD2450_FOV_DEG = 60.0              # half-angle
LD2450_RANGE_MM = 6000.0


def scan_distances(angles_deg: np.ndarray, t: float) -> np.ndarray:
    """Lidar distances (mm, inf = no return) for LDROBOT angles at time t."""
    th = np.radians(np.asarray(angles_deg, dtype=np.float64) - frames.LIDAR.yaw_deg)
    return scene.ray_distances(-np.sin(th), -np.cos(th), scene.person_at(t))


def ld2450_targets(t: float) -> list[ld2450.Target]:
    """What the 24 GHz radar reports: the person, if in its field of view and not behind a wall."""
    p = scene.person_at(t)
    dy = p.y - frames.LD2450.y_mm
    rng = math.hypot(p.x, dy)
    if rng > LD2450_RANGE_MM or abs(math.degrees(math.atan2(p.x, -dy))) > LD2450_FOV_DEG:
        return []
    d = math.hypot(p.x, p.y)
    wall = scene.ray_distances(np.array([p.x / d]), np.array([p.y / d]), None)[0]
    if wall < d - scene.PERSON_RADIUS:
        return []
    rx, ry = frames.device_to_ld2450(p.x, p.y)
    v = (p.vx * p.x + p.vy * dy) / (rng or 1.0)       # mm/s, positive = moving away
    return [ld2450.Target(int(rx), int(ry), int(-v / 10), 320)]


def vitals(t: float) -> protocol.Vitals:
    ok, br, hr, bw, hw, dist = scene.vitals_at(t)
    return protocol.Vitals(ok, br, hr, bw, hw, int(dist))


@dataclass
class SimDevice:
    host: str = "255.255.255.255"
    port: int = protocol.HOST_PORT
    model: int = 1                  # 1 = D500, 2 = D800
    batch: int = 10                 # lidar packets per datagram
    noise_mm: float = 8.0
    seed: int = 0

    def __post_init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.sock.bind(("0.0.0.0", 0))
        self.sock.setblocking(False)
        self.target = (self.host, self.port)
        self.linked = False
        self.seq = 0
        self.t0 = time.monotonic()
        self.rng = np.random.default_rng(self.seed)
        self.mac = bytes([0x02, 0x4D, 0x56, 0x53, 0x49, 0x4D])  # locally administered "MVSIM"

    def now_us(self) -> int:
        return int((time.monotonic() - self.t0) * 1e6)

    def _send(self, msg_type: int, payload: bytes) -> None:
        self.sock.sendto(protocol.pack(msg_type, self.seq, self.now_us(), payload), self.target)
        self.seq += 1

    def hello(self) -> None:
        h = protocol.Hello(self.mac, 255, protocol.FLAG_SIMULATED, -40, self.now_us() // 1000, "sim-0.1.0")
        self._send(protocol.HELLO, h.encode())

    def poll_ack(self) -> None:
        while True:
            try:
                data, addr = self.sock.recvfrom(64)
            except (BlockingIOError, OSError):
                return
            try:
                hdr, _ = protocol.unpack(data)
            except protocol.ProtocolError:
                continue
            if hdr.type == protocol.HOST_ACK:
                self.target = (addr[0], self.port)
                self.linked = True

    def lidar_packets(self, t: float, start_deg: float, n: int) -> tuple[list[bytes], float]:
        """n packets starting at start_deg; returns them and the next start angle."""
        step = 360.0 * SCAN_HZ / LIDAR_RATE[self.model]      # degrees between two points
        angles = (start_deg + step * np.arange(n * ldrobot.POINTS)) % 360.0
        d = scan_distances(angles, t)
        valid = np.isfinite(d) & (d <= 12000)
        dist = np.where(valid, np.maximum(0, np.nan_to_num(d, posinf=0) + self.rng.normal(0, self.noise_mm, d.shape)), 0)
        inten = np.where(valid, np.where(d < 3000, 200, 120), 0)
        ts = int(t * 1000) % 30000
        out = []
        for i in range(n):
            k = slice(i * ldrobot.POINTS, (i + 1) * ldrobot.POINTS)
            a = angles[k]
            out.append(ldrobot.build(int(SCAN_HZ * 360), a[0], a[-1], dist[k], inten[k], ts))
        return out, float((start_deg + n * ldrobot.POINTS * step) % 360.0)

    def run(self, duration: float | None = None) -> None:
        pkt_rate = LIDAR_RATE[self.model] / ldrobot.POINTS
        angle = 0.0
        next_hello = next_radar = 0.0
        sent_pkts = 0
        start = time.monotonic()
        while duration is None or time.monotonic() - start < duration:
            t = time.monotonic() - self.t0
            self.poll_ack()
            if t >= next_hello:
                self.hello()
                next_hello = t + (2.0 if self.linked else 0.5)
            if not self.linked:
                sent_pkts = int(t * pkt_rate)
                time.sleep(0.05)
                continue
            due = int(t * pkt_rate) - sent_pkts
            while due >= self.batch:
                pkts, angle = self.lidar_packets(t, angle, self.batch)
                self._send(protocol.LIDAR, bytes([self.model]) + b"".join(pkts))
                sent_pkts += self.batch
                due -= self.batch
            if t >= next_radar:
                self._send(protocol.LD2450, ld2450.build(ld2450_targets(t)))
                self._send(protocol.VITALS, vitals(t).encode())
                next_radar = t + 0.1
            time.sleep(0.005)
