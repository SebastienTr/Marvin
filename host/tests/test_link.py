# SPDX-License-Identifier: MIT
"""FaceLink and Receiver.send over real local UDP sockets."""
import socket
import time

import pytest

from marvin_host import protocol
from marvin_host.brain import Brain
from marvin_host.events import Event, EventKind, PresenceState
from marvin_host.link import FaceLink
from marvin_host.receiver import Receiver, Sink


class FakeRobot:
    """A UDP socket that says HELLO like the firmware and collects what the host sends back."""

    def __init__(self, rx: Receiver, board: int, mac: bytes):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(1.0)
        self.addr = self.sock.getsockname()
        hello = protocol.Hello(mac, board, 0, -50, 1000, "test").encode()
        rx.handle(protocol.pack(protocol.HELLO, 0, 0, hello), self.addr)
        hdr, _ = self.recv()
        assert hdr.type == protocol.HOST_ACK

    def recv(self):
        data, _ = self.sock.recvfrom(4096)
        return protocol.unpack(data)

    def drain(self, timeout=0.05):
        out = []
        self.sock.settimeout(timeout)
        try:
            while True:
                out.append(self.recv())
        except socket.timeout:
            pass
        self.sock.settimeout(1.0)
        return out

    def close(self):
        self.sock.close()


@pytest.fixture
def setup():
    rx = Receiver(Sink(), port=0, bind="127.0.0.1")
    brain = Brain()
    robot = FakeRobot(rx, board=3, mac=b"\x01\x02\x03\x04\x05\x06")       # XIAO: has the screen
    other = FakeRobot(rx, board=1, mac=b"\x01\x02\x03\x04\x05\x07")       # D1 mini: no screen
    yield rx, brain, robot, other
    robot.close()
    other.close()
    rx.sock.close()


def test_event_codes_cover_every_event_kind():
    assert set(protocol.FACE_EVENT_CODES) == set(EventKind)
    codes = list(protocol.FACE_EVENT_CODES.values())
    assert sorted(codes) == list(range(1, len(EventKind) + 1))
    assert protocol.FACE_EVENT_CODES[EventKind.ARRIVED] == 1          # firmware face::Event::ARRIVED
    assert protocol.FACE_EVENT_CODES[EventKind.VITALS_LOST] == 8      # firmware face::Event::VITALS_LOST


def test_face_state_round_trip():
    s = PresenceState(present=True, seated=True, position=(-150.4, -800.0, 16.0), head=(-150.4, -800.0, 550.0),
                      distance_m=0.8123, heart_rate=72.5)
    payload = protocol.FaceState.from_presence(s).encode()
    assert len(payload) == protocol.FaceState.SIZE == 17
    d = protocol.FaceState.decode(payload)
    assert d.present and d.seated
    assert d.head == (-150.0, -800.0, 550.0) and d.position == (-150.0, -800.0, 16.0)
    assert d.distance_m == pytest.approx(0.812) and d.heart_rate == 72.5
    # the exact bytes the firmware test decodes (test_face/test_main.cpp, test_decode_state)
    assert protocol.FaceState(True, True, (-150, -800, 550), (-150.4, -800, 16), 0.81, 72.5).encode().hex() == \
        "3f6affe0fc26026affe0fc10002a03521c"
    empty = protocol.FaceState.decode(protocol.FaceState(present=False).encode())
    assert empty == protocol.FaceState()
    far = protocol.FaceState(True, head=(0, -99999, 0)).encode()      # clamped, not an overflow
    assert protocol.FaceState.decode(far).head == (0.0, -32767.0, 0.0)


def test_send_keeps_a_sequence_per_device(setup):
    rx, _, robot, other = setup
    dev = rx.devices[robot.addr]
    for i in range(3):
        rx.send(dev, protocol.FACE_EVENT, b"\x01")
    rx.send(other.addr, protocol.FACE_EVENT, b"\x02")
    seqs = [hdr.seq for hdr, _ in robot.drain()]
    assert seqs == [0, 1, 2]
    (hdr, payload), = other.drain()
    assert hdr.seq == 0 and hdr.type == protocol.FACE_EVENT and payload == b"\x02"


def test_events_go_to_screens_only(setup):
    rx, brain, robot, other = setup
    link = FaceLink(rx, brain)
    assert [d.addr for d in link.screens()] == [robot.addr]
    brain._emit(EventKind.SAT_DOWN, 1_000_000)                     # as the brain does, via its listeners
    link.on_event(Event(EventKind.VITALS_ACQUIRED, 2_000_000))
    got = robot.drain()
    assert [(h.type, p) for h, p in got] == [(protocol.FACE_EVENT, bytes([4])), (protocol.FACE_EVENT, bytes([7]))]
    assert other.drain() == []
    assert link.events_sent == 2
    link.stop()
    brain._emit(EventKind.STOOD_UP, 3_000_000)                     # no longer listening
    assert robot.drain() == []


def test_state_stream_at_10_hz(setup):
    rx, brain, robot, other = setup
    brain.state = PresenceState(present=True, seated=False, position=(300.0, -1200.0, 16.0),
                                head=(300.0, -1200.0, 1000.0), distance_m=1.24)
    with FaceLink(rx, brain, rate_hz=10.0):
        time.sleep(0.55)
    got = robot.drain()
    assert all(h.type == protocol.FACE_STATE for h, _ in got)
    assert 4 <= len(got) <= 8
    s = protocol.FaceState.decode(got[-1][1])
    assert s.present and not s.seated and s.head == (300.0, -1200.0, 1000.0) and s.distance_m == 1.24
    assert s.heart_rate is None
    assert other.drain() == []
