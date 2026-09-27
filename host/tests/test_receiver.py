# SPDX-License-Identifier: MIT
"""Receiver: the socket thread never waits for the sinks (HOST_ACK in time, lidar shed first, order kept)."""
import logging
import socket
import threading
import time

import pytest

from marvin_host import ld2450, ldrobot, protocol
from marvin_host.receiver import Receiver, Sink

MAC = b"\x02\x00\x00\x00\x00\x01"


def revolution(t_ms: int) -> bytes:
    """A LIDAR payload holding one whole revolution: 4 packets starting at 0, 90, 180 and 270 degrees."""
    pkts = [ldrobot.build(3600, a, a + 80, [1000] * ldrobot.POINTS, [200] * ldrobot.POINTS, t_ms)
            for a in (0, 90, 180, 270)]
    return bytes([1]) + b"".join(pkts)


class Robot:
    """A UDP socket that talks like the firmware and times the host's HOST_ACKs."""

    def __init__(self, port: int):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.host = ("127.0.0.1", port)
        self.seq = 0
        self.lock = threading.Lock()

    def send(self, msg_type: int, payload: bytes, t_us: int = 0) -> None:
        with self.lock:
            self.sock.sendto(protocol.pack(msg_type, self.seq, t_us, payload), self.host)
            self.seq += 1

    def hello(self) -> float:
        """Sends a HELLO and returns the seconds until its HOST_ACK."""
        h = protocol.Hello(MAC, 1, protocol.FLAG_SIMULATED, -72, 0, "0.1.0").encode()
        self.sock.settimeout(2.0)
        t = time.perf_counter()
        self.send(protocol.HELLO, h)
        data, _ = self.sock.recvfrom(64)
        assert protocol.unpack(data)[0].type == protocol.HOST_ACK
        return time.perf_counter() - t

    def close(self) -> None:
        self.sock.close()


class Recorder(Sink):
    def __init__(self, scan_s: float = 0.0):
        self.scan_s = scan_s
        self.calls = []            # (callback, t_us)

    def on_hello(self, dev):
        self.calls.append(("hello", None))

    def on_scan(self, dev, t_us, points, intensities, speed_dps):
        self.calls.append(("scan", t_us))
        assert len(points) == 4 * ldrobot.POINTS
        time.sleep(self.scan_s)

    def on_targets(self, dev, t_us, targets, points):
        self.calls.append(("targets", t_us))

    def on_vitals(self, dev, t_us, vitals):
        self.calls.append(("vitals", t_us))

    def on_log(self, dev, t_us, text):
        self.calls.append(("log", t_us))

    def of(self, kind):
        return [t for k, t in self.calls if k == kind]


class Serving:
    """Runs receivers' serve() in threads; everything is stopped and closed at the end of the test."""

    def __init__(self):
        self.running = {}                             # receiver -> (stop event, thread, robot)

    def __call__(self, rx: Receiver) -> Robot:
        stop = threading.Event()
        th = threading.Thread(target=rx.serve, kwargs={"stop": stop}, daemon=True)
        th.start()
        robot = Robot(rx.sock.getsockname()[1])
        self.running[rx] = (stop, th, robot)
        return robot

    def stop(self, rx: Receiver) -> float:
        """Stops serving and returns how long serve() took to return."""
        stop, th, _ = self.running[rx]
        t = time.monotonic()
        stop.set()
        th.join(5)
        assert not th.is_alive()
        return time.monotonic() - t

    def close(self) -> None:
        for rx, (stop, th, robot) in self.running.items():
            stop.set()
            th.join(5)
            robot.close()
            rx.sock.close()


@pytest.fixture
def serving():
    s = Serving()
    yield s
    s.close()


def wait_until(cond, timeout=3.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if cond():
            return True
        time.sleep(0.005)
    return cond()


def test_slow_sink_does_not_delay_host_ack_nor_drop_radar(serving, caplog):
    caplog.set_level(logging.WARNING, logger="marvin.receiver")
    sink = Recorder(scan_s=0.5)
    rx = Receiver(sink, port=0, bind="127.0.0.1")
    robot = serving(rx)
    assert robot.hello() < 0.05
    stop = threading.Event()
    sent = {"lidar": 0, "targets": 0}

    def stream():                                     # 1.6 s of sensors: 40 revolutions, 16 radar frames
        for i in range(40):
            robot.send(protocol.LIDAR, revolution(i), t_us=i * 40_000)
            sent["lidar"] += 1
            if i % 2 == 0:
                robot.send(protocol.LD2450, ld2450.build([ld2450.Target(100, 1000, 0, 320)]), t_us=i * 40_000)
                robot.send(protocol.VITALS, protocol.Vitals(True, 14, 60, 0, 0, 900).encode(), t_us=i * 40_000)
                sent["targets"] += 1
            time.sleep(0.04)
        stop.set()

    th = threading.Thread(target=stream)
    th.start()
    acks = []
    while not stop.is_set():                          # a HELLO every 100 ms while the sink is stuck
        acks.append(robot.hello())
        time.sleep(0.1)
    th.join()
    assert len(acks) >= 10
    assert max(acks) < 0.05, acks

    serving.stop(rx)
    (dev,) = rx.devices.values()
    scans = sent["lidar"] - 1                         # a revolution is complete when the next one starts
    assert dev.stats.shed["scan"] > 0
    assert len(sink.of("scan")) + dev.stats.shed["scan"] == scans
    assert set(dev.stats.shed) == {"scan"}            # nothing but lidar was dropped
    assert len(sink.of("targets")) == len(sink.of("vitals")) == sent["targets"] == dev.stats.radar_frames
    assert dev.stats.lost == 0
    assert sink.calls[0] == ("hello", None)

    t = rx.timings()
    assert t["scan"]["max_ms"] >= 490 and t["scan"]["slow"] == len(sink.of("scan"))
    assert t["targets"]["max_ms"] < 50 and t["targets"]["calls"] == sent["targets"]
    text = caplog.text
    assert "Recorder.on_scan took" in text and "dropping scan calls" in text


def test_calls_stay_in_arrival_order(serving):
    sink = Recorder()
    rx = Receiver(sink, port=0, bind="127.0.0.1", max_scan_lag_s=60, max_pending_scans=1000)   # no shedding
    robot = serving(rx)
    robot.hello()
    expected = []
    for i in range(1, 201):
        kind = ("log", "targets", "vitals", "scan")[i % 4]
        if kind == "log":
            robot.send(protocol.LOG, b"hello %d" % i, t_us=i)
        elif kind == "targets":
            robot.send(protocol.LD2450, ld2450.build([]), t_us=i)
        elif kind == "vitals":
            robot.send(protocol.VITALS, protocol.Vitals(False, 0, 0, 0, 0, 0).encode(), t_us=i)
        else:
            robot.send(protocol.LIDAR, revolution(i), t_us=i)
        expected.append((kind, i))
    assert wait_until(lambda: [d.stats.datagrams for d in rx.devices.values()] == [201])   # HELLO + 200
    serving.stop(rx)
    got = [c for c in sink.calls if c[0] != "hello"]
    # the scan of a revolution is emitted when the next revolution arrives, with that datagram's clock
    scans = [t for k, t in expected if k == "scan"][1:]
    assert [t for k, t in got if k == "scan"] == scans
    assert [c for c in got if c[0] != "scan"] == [c for c in expected if c[0] != "scan"]
    assert got == sorted(got, key=lambda c: c[1])
    (dev,) = rx.devices.values()
    assert dev.stats.shed == {}


def test_serve_dispatches_what_is_queued_then_stops(serving):
    sink = Recorder()
    rx = Receiver(sink, port=0, bind="127.0.0.1")
    robot = serving(rx)
    robot.hello()
    for i in range(50):
        robot.send(protocol.LOG, b"x", t_us=i)
    assert wait_until(lambda: len(sink.of("log")) == 50)
    assert rx.serving
    assert serving.stop(rx) < 1.0
    assert not rx.serving
    assert not any(t.name == "receiver-dispatch" and t.is_alive() for t in threading.enumerate())
    rx.handle(protocol.pack(protocol.LOG, 999, 7, b"after"), robot.sock.getsockname())
    assert sink.calls[-1] == ("log", 7)               # not serving: handled synchronously


def test_stop_gives_up_on_a_stuck_sink(serving):
    release = threading.Event()

    class Stuck(Recorder):
        def on_log(self, dev, t_us, text):
            super().on_log(dev, t_us, text)
            release.wait(10)

    sink = Stuck()
    rx = Receiver(sink, port=0, bind="127.0.0.1")
    th = threading.Thread(target=rx.serve, kwargs={"stop": (stop := threading.Event()), "drain_timeout": 0.3},
                          daemon=True)
    th.start()
    robot = Robot(rx.sock.getsockname()[1])
    try:
        robot.hello()
        for i in range(5):
            robot.send(protocol.LOG, b"x", t_us=i)
        assert wait_until(lambda: len(sink.of("log")) == 1)
        time.sleep(0.1)                               # the other 4 are queued behind the stuck call
        t = time.monotonic()
        stop.set()
        th.join(3)
        assert not th.is_alive() and time.monotonic() - t < 1.0
        (dev,) = rx.devices.values()
        assert dev.stats.shed == {"log": 4}
        release.set()
        time.sleep(0.05)
        assert len(sink.of("log")) == 1               # the abandoned calls are not made later
    finally:
        release.set()
        robot.close()
        rx.sock.close()


def test_a_full_queue_drops_the_oldest_lidar_then_the_oldest_call():
    rx = Receiver(Recorder(), port=0, bind="127.0.0.1", queue_size=4)
    try:
        addr = ("127.0.0.1", 9)
        h = protocol.Hello(MAC, 1, 0, -50, 0, "t").encode()
        rx._worker = threading.current_thread()     # pretend to serve: calls are queued, nothing dispatches them
        rx.handle(protocol.pack(protocol.HELLO, 0, 0, h), addr)
        rx.handle(protocol.pack(protocol.LIDAR, 1, 1, revolution(1)), addr)
        rx.handle(protocol.pack(protocol.LIDAR, 2, 2, revolution(2)), addr)       # scan 1 queued
        rx.handle(protocol.pack(protocol.LOG, 3, 3, b"a"), addr)
        rx.handle(protocol.pack(protocol.LOG, 4, 4, b"b"), addr)                   # full: hello, scan, a, b
        rx.handle(protocol.pack(protocol.LOG, 5, 5, b"c"), addr)                   # the scan goes first
        assert [c[0] for c in rx._queue] == ["hello", "log", "log", "log"]
        rx.handle(protocol.pack(protocol.LOG, 6, 6, b"d"), addr)                   # then the oldest call
        assert [(c[0], c[2][0] if c[2] else None) for c in rx._queue] == [("log", 3), ("log", 4), ("log", 5), ("log", 6)]
        (dev,) = rx.devices.values()
        assert dev.stats.shed == {"scan": 1, "hello": 1}
    finally:
        rx._worker = None
        rx.sock.close()


def test_a_failing_sink_does_not_stop_the_receiver(serving, caplog):
    class Failing(Recorder):
        def on_log(self, dev, t_us, text):
            super().on_log(dev, t_us, text)
            raise RuntimeError("boom")

    sink = Failing()
    rx = Receiver(sink, port=0, bind="127.0.0.1")
    robot = serving(rx)
    robot.hello()
    robot.send(protocol.LOG, b"x", t_us=1)
    robot.send(protocol.LD2450, ld2450.build([]), t_us=2)
    assert wait_until(lambda: sink.of("targets") == [2])
    assert robot.hello() < 0.05
    assert "on_log failed" in caplog.text


def test_handle_without_serve_calls_the_sink_right_away():
    sink = Recorder()
    rx = Receiver(sink, port=0, bind="127.0.0.1")
    try:
        addr = ("127.0.0.1", 9)
        rx.handle(protocol.pack(protocol.HELLO, 0, 0, protocol.Hello(MAC, 1, 0, -50, 0, "t").encode()), addr)
        rx.handle(protocol.pack(protocol.LOG, 1, 5, b"x"), addr)
        assert sink.calls == [("hello", None), ("log", 5)]
        assert not rx.serving and rx.pending() == 0
        assert rx.timings()["log"]["calls"] == 1
    finally:
        rx.sock.close()
