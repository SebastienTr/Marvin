# SPDX-License-Identifier: MIT
"""The USB sensor bridge (usb_sensors.py): probing, the robot link as the firmware does it, batching, and the
whole bridge on a pseudo-terminal standing in for the USB adapter, to the Python receiver standing in for the
host."""
from __future__ import annotations

import os
import socket
import threading
import time

import pytest

from marvin_host import ld2450, protocol, usb_sensors
from marvin_host.receiver import Receiver, Sink
from marvin_host.usb_sensors import Bridge, Detection, SensorLink, UsbDevice, candidate_ports, probe, pseudo_mac

from test_sensor_framing import LD2450_EXAMPLE, stl27l_packets

STL27L = b"".join(stl27l_packets(200))


class FakeClock:
    def __init__(self, t: float = 100.0):
        self.t = t

    def __call__(self) -> float:
        return self.t


class FakeSerial:
    """A serial port that gives `data` in chunks, then nothing; each read moves `clock` on by `dt`."""

    def __init__(self, data: bytes = b"", chunk: int = 512, clock: FakeClock | None = None, dt: float = 0.01):
        self.data = bytearray(data)
        self.chunk = chunk
        self.clock = clock
        self.dt = dt
        self.written = bytearray()
        self.closed = False
        self.fail = False

    def read(self, n: int) -> bytes:
        if self.fail:
            raise OSError("device not configured")
        if self.clock is not None:
            self.clock.t += self.dt
        out = bytes(self.data[:min(n, self.chunk)])
        del self.data[:len(out)]
        return out

    def write(self, data: bytes) -> int:
        self.written += data
        return len(data)

    def close(self) -> None:
        self.closed = True


def opener_for(by_baud: dict[int, bytes], clock: FakeClock, opened: list | None = None):
    def opener(path: str, baud: int) -> FakeSerial:
        if opened is not None:
            opened.append(baud)
        return FakeSerial(by_baud.get(baud, b"\x00\xff" * 64), clock=clock)
    return opener


# ---------------------------------------------------------------------------- finding the sensors


def test_pseudo_mac_is_stable_unicast_and_locally_administered():
    a, b = pseudo_mac("10c4:ea60:0001:cu.usbserial-0001"), pseudo_mac("10c4:ea60:0001:cu.usbserial-0001")
    assert a == b and len(a) == 6
    assert a[0] & 0x02 and not a[0] & 0x01
    assert pseudo_mac("other") != a


def test_candidate_ports_skip_bluetooth(tmp_path):
    for name in ("cu.usbserial-0001", "cu.Bluetooth-Incoming-Port", "cu.usbserial-BLTH", "ttyUSB0", "cu.debug"):
        (tmp_path / name).touch()
    env = {"MARVIN_USB_PORTS": f"{tmp_path}/cu.*, {tmp_path}/ttyUSB*"}
    assert candidate_ports(env=env) == [f"{tmp_path}/cu.debug", f"{tmp_path}/cu.usbserial-0001", f"{tmp_path}/ttyUSB0"]
    pats = (f"{tmp_path}/cu.usbserial-*", f"{tmp_path}/cu.Bluetooth*")
    assert candidate_ports(pats, env={}) == [f"{tmp_path}/cu.usbserial-0001"]


def test_probe_finds_the_stl27l_at_921600():
    clock = FakeClock()
    opened = []
    d = probe("/dev/cu.usbserial-0001", opener_for({921600: STL27L}, clock, opened), clock=clock)
    assert d == Detection("/dev/cu.usbserial-0001", 921600, "lidar", 2, d.frames)
    assert d.frames >= 3 and opened == [921600]
    assert d.describe() == "lidar STL-27L on /dev/cu.usbserial-0001 @921600"


def test_probe_finds_a_d500_at_230400_and_a_radar_at_256000():
    clock = FakeClock()
    d = probe("p", opener_for({230400: STL27L}, clock), clock=clock)
    assert (d.kind, d.baud, d.model, d.name) == ("lidar", 230400, 1, "lidar STL-19P")
    opened = []
    r = probe("p", opener_for({256000: LD2450_EXAMPLE * 5}, clock, opened), clock=clock)
    assert (r.kind, r.baud, r.name) == ("radar", 256000, "radar LD2450")
    assert opened == [921600, 230400, 256000]
    assert probe("p", opener_for({256000: LD2450_EXAMPLE * 5}, clock), radar=False, clock=clock) is None


def test_probe_finds_nothing_in_noise_and_survives_a_busy_port():
    clock = FakeClock()
    assert probe("p", opener_for({}, clock), clock=clock) is None

    def busy(path, baud):
        raise OSError("Resource busy")
    assert probe("p", busy, clock=clock) is None


# ---------------------------------------------------------------------------- the link, as the firmware's


class FakeHost:
    """A UDP socket on localhost standing in for the host: records datagrams, answers HELLO when asked."""

    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(0.5)
        self.port = self.sock.getsockname()[1]

    def recv(self) -> tuple[protocol.Header, bytes, tuple]:
        data, addr = self.sock.recvfrom(4096)
        h, p = protocol.unpack(data)
        return h, p, addr

    def ack(self, addr) -> None:
        self.sock.sendto(protocol.pack(protocol.HOST_ACK, 0, 1234, (1234).to_bytes(8, "little")), addr)

    def close(self):
        self.sock.close()


@pytest.fixture
def host():
    h = FakeHost()
    yield h
    h.close()


def wait_linked(dev: UsbDevice) -> None:
    end = time.monotonic() + 2
    while not dev.linked and time.monotonic() < end:
        dev.poll_host()
        time.sleep(0.005)
    assert dev.linked


def test_hello_ack_batches_and_timeout(host):
    clock = FakeClock()
    det = Detection("/dev/cu.usbserial-0001", 921600, "lidar", 2)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    sock.setblocking(False)
    dev = UsbDevice(det, "127.0.0.1", host.port, identity="id-1", sock=sock, clock=clock)
    pkts = stl27l_packets(25)

    dev.on_lidar_packet(pkts[0])            # not linked: nothing is sent
    dev.link_step()
    h, p, addr = host.recv()
    assert h.type == protocol.HELLO
    hello = protocol.Hello.decode(p)
    assert (hello.board, hello.flags, hello.rssi, hello.firmware) == (6, 0, 0, usb_sensors.FIRMWARE)
    assert hello.device_id == pseudo_mac("id-1") and not hello.simulated
    assert protocol.BOARDS[hello.board] == "USB sensor bridge (computer)"
    clock.t += 0.3
    dev.link_step()                         # searching: the next HELLO waits 0.5 s
    clock.t += 0.3
    dev.link_step()
    assert host.recv()[0].type == protocol.HELLO

    host.ack(addr)
    wait_linked(dev)
    h, p, _ = host.recv()
    assert h.type == protocol.LOG and b"real lidar D800 (STL-27L) on /dev/cu.usbserial-0001 at 921600" in p

    t_first = dev.now_us()
    for pk in pkts[:10]:
        dev.on_lidar_packet(pk)
        clock.t += 0.0005
    h, p, _ = host.recv()                   # a full batch goes at once, stamped with its first packet
    assert h.type == protocol.LIDAR and h.t_us == t_first
    assert p[0] == 2 and p[1:] == b"".join(pkts[:10])

    for pk in pkts[10:13]:
        dev.on_lidar_packet(pk)
    dev.flush_if_due()                      # 3 packets, younger than 20 ms: they wait
    clock.t += 0.021
    dev.flush_if_due()
    h, p, _ = host.recv()
    assert h.type == protocol.LIDAR and p[1:] == b"".join(pkts[10:13])
    assert dev.packets == 13 and dev.points == 156

    clock.t += usb_sensors.LINK_TIMEOUT_S + 0.1
    dev.on_lidar_packet(pkts[13])
    dev.link_step()                         # no ACK for 6 s: searching again, the batch is dropped
    assert not dev.linked and not dev.batch
    assert host.recv()[0].type == protocol.HELLO
    sock.close()


def test_radar_frames_go_one_per_datagram(host):
    clock = FakeClock()
    det = Detection("/dev/ttyUSB0", 256000, "radar")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    sock.setblocking(False)
    dev = UsbDevice(det, "127.0.0.1", host.port, identity="r", sock=sock, clock=clock)
    ser = FakeSerial(b"\x13" + LD2450_EXAMPLE * 3, chunk=40, clock=clock)
    link = SensorLink(det, ser, dev)
    dev.link_step()
    _, _, addr = host.recv()
    host.ack(addr)
    wait_linked(dev)
    assert host.recv()[0].type == protocol.LOG
    for _ in range(4):
        assert link.step()
    got = []
    while True:
        try:
            h, p, _ = host.recv()
        except socket.timeout:
            break
        if h.type == protocol.LD2450:
            got.append(p)
    assert got == [LD2450_EXAMPLE] * 3
    (t,) = ld2450.parse(got[0])
    assert (t.x_mm, t.y_mm) == (-782, 1713)
    sock.close()


def test_sensor_link_ends_when_unplugged_and_configures_the_radar(host):
    clock = FakeClock()
    det = Detection("p", 256000, "radar")
    dev = UsbDevice(det, "127.0.0.1", host.port, identity="r2", clock=clock)
    ser = FakeSerial(LD2450_EXAMPLE, clock=clock)
    link = SensorLink(det, ser, dev)
    ser.fail = True
    assert link.run() == "unplugged" and ser.closed
    assert bytes(ser.written).startswith(bytes.fromhex("fdfcfbfa0400ff000100"))
    dev.close()


def test_a_silent_sensor_is_let_go(host):
    clock = FakeClock()
    det = Detection("p", 921600, "lidar", 2)
    dev = UsbDevice(det, "127.0.0.1", host.port, identity="s", clock=clock)
    link = SensorLink(det, FakeSerial(b"", clock=clock, dt=1.0), dev, silent_s=5.0)
    assert link.run() == "silent"
    dev.close()


# ---------------------------------------------------------------------------- end to end, over a pty


class Collect(Sink):
    def __init__(self):
        self.hellos, self.scans, self.logs = [], [], []

    def on_hello(self, dev):
        self.hellos.append(dev.hello)

    def on_scan(self, dev, t_us, points, intensities, speed_dps):
        self.scans.append(len(points))

    def on_log(self, dev, t_us, text):
        self.logs.append(text)


def _pty_pair():
    pty = pytest.importorskip("pty")
    master, slave = pty.openpty()
    return master, os.ttyname(slave), slave


def _write_forever(master: int, stop: threading.Event, data: bytes) -> None:
    """Plays the STL-27L's stream at about its real rate (one revolution, 180 packets, every 0.1 s)."""
    i = 0
    while not stop.is_set():
        chunk = data[i:i + 47 * 18]
        if not chunk:
            i = 0
            continue
        try:
            os.write(master, chunk)
        except OSError:
            return
        i += len(chunk)
        time.sleep(0.01)


def test_bridge_end_to_end_over_a_pty_with_a_replug(tmp_path):
    pytest.importorskip("serial")
    stream = b"".join(stl27l_packets(1800))
    sink = Collect()
    rx = Receiver(sink, port=0, bind="127.0.0.1")
    port = rx.sock.getsockname()[1]
    stop_rx = threading.Event()
    th = threading.Thread(target=rx.serve, kwargs={"stop": stop_rx}, daemon=True)
    th.start()

    link_path = str(tmp_path / "cu.usbserial-TEST")     # the adapter's name, the same after a replug
    master, slave_path, slave_fd = _pty_pair()
    os.symlink(slave_path, link_path)
    stop_w = threading.Event()
    w = threading.Thread(target=_write_forever, args=(master, stop_w, stream), daemon=True)
    w.start()
    status = str(tmp_path / "status.json")
    bridge = Bridge(host="127.0.0.1", port=port, radar=False, ports=lambda: [link_path] if os.path.exists(link_path) else [],
                    status_file=status)
    bt = threading.Thread(target=bridge.run, daemon=True)
    try:
        bt.start()
        end = time.monotonic() + 10
        while len(sink.scans) < 5 and time.monotonic() < end:
            time.sleep(0.05)
        assert len(sink.scans) >= 5, sink.logs
        (hello,) = sink.hellos
        assert hello.board == protocol.BOARD_USB_BRIDGE and not hello.simulated
        assert 1500 < max(sink.scans) <= 2200                  # STL-27L: about 2160 points per revolution
        assert any("USB sensor bridge" in t and "STL-27L" in t for t in sink.logs)
        (dev,) = rx.devices.values()
        assert dev.stats.crc_errors == 0 and dev.stats.lost == 0
        first_addr = dev.addr
        import json
        st = json.load(open(status))
        assert st["sensors"][0]["name"] == "lidar STL-27L" and st["sensors"][0]["baud"] == 921600

        # unplug: the pty goes away; plug back in: a new pty behind the same name
        stop_w.set()
        w.join()
        os.close(master)
        os.close(slave_fd)
        os.remove(link_path)
        time.sleep(0.5)
        master, slave_path, slave_fd = _pty_pair()
        os.symlink(slave_path, link_path)
        stop_w = threading.Event()
        w = threading.Thread(target=_write_forever, args=(master, stop_w, stream), daemon=True)
        w.start()
        n = len(sink.scans)
        end = time.monotonic() + 15
        while len(sink.scans) < n + 5 and time.monotonic() < end:
            time.sleep(0.05)
        assert len(sink.scans) >= n + 5
        assert len(rx.devices) == 1 and next(iter(rx.devices.values())).addr == first_addr   # the same device
    finally:
        bridge.stop.set()
        bt.join(5)
        stop_w.set()
        w.join()
        stop_rx.set()
        th.join()
        for fd in (master, slave_fd):
            try:
                os.close(fd)
            except OSError:
                pass


def test_the_app_names_the_bridge():
    from marvin_host.ui.sink import device_role
    h = protocol.Hello(pseudo_mac("x"), protocol.BOARD_USB_BRIDGE, 0, 0, 0, usb_sensors.FIRMWARE)
    assert device_role(h) == ("usb-sensors", "USB sensor bridge")


def test_a_port_that_had_a_sensor_keeps_being_listened_to():
    clock = FakeClock()
    data = {"p": STL27L}

    def opener(path, baud):
        return FakeSerial(data["p"] if path == "p" and baud == 921600 else b"", clock=clock)

    bridge = Bridge(opener=opener, ports=lambda: ["p", "q"], identity=lambda p: p, clock=clock)
    started = bridge.scan()
    assert [d.port for d in started] == ["p"]
    bridge.stop.set()                                   # the sensor's link ends ...
    bridge.states["p"].thread.join(2)
    bridge.stop.clear()
    data["p"] = b""                                     # ... and it says nothing any more
    for _ in range(5):                                  # 10 s apart, for as long as the adapter is there
        clock.t += 10.1
        assert bridge.scan() == []
        assert bridge.states["p"].retry_at is not None
    assert bridge.states["q"].retry_at is None          # never had a sensor: not until it is plugged in again
    data["p"] = STL27L
    clock.t += 10.1
    assert [d.port for d in bridge.scan()] == ["p"]
    bridge.stop.set()
    bridge.states["p"].thread.join(2)
    for s in bridge.sockets.values():
        s.close()
