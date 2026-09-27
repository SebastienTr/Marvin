"""A simulated robot: the same UDP frames as the firmware, from a synthetic room.

The room is a 4 x 3.5 m rectangle with the desk against the back wall, and one person who walks
around, then sits down in front of the robot. The lidar sees the walls, a cupboard and the person;
the LD2450 sees the person.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
import socket
import time
from dataclasses import dataclass

import numpy as np

from . import frames, ld2450, ldrobot, protocol

# room walls as segments in the device frame (mm); the robot stands on the desk near the back wall
ROOM = [
    ((-2000, 400), (2000, 400)),       # wall behind the robot
    ((2000, 400), (2000, -3100)),      # right wall (as seen facing the robot)
    ((2000, -3100), (-2000, -3100)),   # wall in front of the robot
    ((-2000, -3100), (-2000, 400)),    # left wall
    ((-2000, -1200), (-1400, -1200)),  # cupboard
    ((-1400, -1200), (-1400, -1800)),
    ((-1400, -1800), (-2000, -1800)),
]
PERSON_RADIUS = 180.0

LIDAR_RATE = {1: 4500, 2: 21600}   # points per second, D500 / D800
SCAN_HZ = 10.0


def person_at(t: float) -> tuple[float, float, float, float]:
    """Person position (x, y) in mm and velocity (vx, vy) in mm/s at time t (s). 40 s loop."""
    t = t % 40.0
    if t < 20.0:   # walks an ellipse around the room
        w = 2 * math.pi / 20.0
        a, b, cy = 1200.0, 900.0, -1700.0
        return a * math.cos(w * t), cy + b * math.sin(w * t), -a * w * math.sin(w * t), b * w * math.cos(w * t)
    if t < 24.0:   # comes to the desk
        k = (t - 20.0) / 4.0
        x0, y0, x1, y1 = 1200.0, -1700.0, 0.0, -700.0
        return x0 + (x1 - x0) * k, y0 + (y1 - y0) * k, (x1 - x0) / 4.0, (y1 - y0) / 4.0
    # sits and sways a little
    s = 30.0 * math.sin(2 * math.pi * 0.25 * t)
    return s, -700.0, 30.0 * 2 * math.pi * 0.25 * math.cos(2 * math.pi * 0.25 * t), 0.0


def _ray_segment(dx: float, dy: float, a, b) -> float:
    (x1, y1), (x2, y2) = a, b
    ex, ey = x2 - x1, y2 - y1
    den = dx * ey - dy * ex
    if abs(den) < 1e-9:
        return math.inf
    t = (x1 * ey - y1 * ex) / den
    u = (x1 * dy - y1 * dx) / den
    return t if t > 0 and 0 <= u <= 1 else math.inf


def _ray_circle(dx: float, dy: float, cx: float, cy: float, r: float) -> float:
    b = dx * cx + dy * cy
    c = cx * cx + cy * cy - r * r
    disc = b * b - c
    if disc < 0:
        return math.inf
    t = b - math.sqrt(disc)
    return t if t > 0 else math.inf


def scan_distance(angle_deg: float, px: float, py: float) -> float:
    th = math.radians(angle_deg - frames.LIDAR.yaw_deg)
    dx, dy = -math.sin(th), -math.cos(th)
    d = min(_ray_segment(dx, dy, a, b) for a, b in ROOM)
    return min(d, _ray_circle(dx, dy, px, py, PERSON_RADIUS))


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
        rate = LIDAR_RATE[self.model]
        step = 360.0 * SCAN_HZ / rate            # degrees between two points
        px, py, _, _ = person_at(t)
        out = []
        for _ in range(n):
            angles = [(start_deg + i * step) % 360.0 for i in range(ldrobot.POINTS)]
            dist, inten = [], []
            for a in angles:
                d = scan_distance(a, px, py)
                if math.isinf(d) or d > 12000:
                    dist.append(0), inten.append(0)
                else:
                    dist.append(max(0, d + self.rng.normal(0, self.noise_mm)))
                    inten.append(200 if d < 3000 else 120)
            ts = int(t * 1000) % 30000
            out.append(ldrobot.build(int(SCAN_HZ * 360), angles[0], angles[-1], dist, inten, ts))
            start_deg = (start_deg + ldrobot.POINTS * step) % 360.0
        return out, start_deg

    def ld2450_frame(self, t: float) -> bytes:
        px, py, vx, vy = person_at(t)
        rx, ry = frames.device_to_ld2450(px, py)
        # radial speed, positive when moving away from the radar (LD2450 convention)
        rng = math.hypot(px, py - frames.LD2450.y_mm) or 1.0
        v = (vx * px + vy * (py - frames.LD2450.y_mm)) / rng
        return ld2450.build([ld2450.Target(int(rx), int(ry), int(-v / 10), 320)])

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
                self._send(protocol.LD2450, self.ld2450_frame(t))
                next_radar = t + 0.1
            time.sleep(0.005)
