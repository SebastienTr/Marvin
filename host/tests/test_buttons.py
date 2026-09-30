# SPDX-License-Identifier: MIT
"""The bench board's BUTTON and HELLO flags, the button actions on the voice, and the buzzer chimes."""
import socket
import time

import pytest

from marvin_host import protocol
from marvin_host.brain import Brain
from marvin_host.buttons import ButtonActions, VoiceChimes, voice_chime
from marvin_host.link import FaceLink
from marvin_host.receiver import Receiver, Sink
from marvin_host.voice.control import VoiceOff

BENCH = protocol.FLAG_SIMULATED | protocol.FLAG_BUTTONS | protocol.FLAG_BUZZER | protocol.FLAG_FACE


class Robot:
    """A UDP socket that says HELLO with the given board and flags and collects what the host sends."""

    def __init__(self, rx: Receiver, board: int, flags: int, mac: bytes):
        self.rx = rx
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(1.0)
        self.addr = self.sock.getsockname()
        rx.handle(protocol.pack(protocol.HELLO, 0, 0, protocol.Hello(mac, board, flags, -50, 1000, "t").encode()),
                  self.addr)
        assert self.recv()[0].type == protocol.HOST_ACK
        self.seq = 1

    def send(self, msg_type: int, payload: bytes) -> None:
        self.rx.handle(protocol.pack(msg_type, self.seq, 5_000, payload), self.addr)
        self.seq += 1

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
        return out

    def close(self):
        self.sock.close()


class Recorder(Sink):
    def __init__(self):
        self.buttons = []

    def on_button(self, dev, t_us, button, action):
        self.buttons.append((dev.hello.device_name, t_us, button, action))


class FakeVoice:
    def __init__(self, state="on", status="idle"):
        self.state, self.status, self.muted = state, status, False
        self.calls = []

    def snapshot(self):
        return {"state": self.state, "status": self.status, "muted": self.muted, "chime": True}

    def listen_now(self, on=True):
        if self.state != "on":
            raise VoiceOff("Marvin's voice is off")
        self.calls.append(("listen", on))

    def mute(self, muted):
        self.muted = muted
        self.calls.append(("mute", muted))


@pytest.fixture
def rx():
    r = Receiver(Recorder(), port=0, bind="127.0.0.1")
    yield r
    r.sock.close()


def test_button_payload_and_hello_flags():
    assert protocol.BUTTON == 0x07
    assert protocol.Button(protocol.BUTTON_TALK).encode() == b"\x01\x01"
    b = protocol.Button.decode(b"\x02\x01\xff")          # a longer payload keeps its prefix
    assert (b.button, b.action, b.name) == (2, 1, "mute")
    assert protocol.Button(9).name is None
    with pytest.raises(protocol.ProtocolError):
        protocol.Button.decode(b"\x01")
    h = protocol.Hello(bytes(6), 5, BENCH, -40, 0, "0.1.0")
    assert h.has_buttons and h.has_buzzer and h.has_face and not h.has_audio
    assert protocol.Hello(bytes(6), 3, 0, -40, 0, "").has_face          # the XIAO's screen
    assert not protocol.Hello(bytes(6), 5, protocol.FLAG_SIMULATED, -40, 0, "").has_face


def test_receiver_hands_buttons_to_the_sink(rx):
    robot = Robot(rx, 5, BENCH, b"\x01\x02\x03\x04\x05\x06")
    try:
        robot.send(protocol.BUTTON, protocol.Button(protocol.BUTTON_TALK).encode())
        robot.send(protocol.BUTTON, b"\x01")                              # too short: bad, dropped
        assert rx.sink.buttons == [("marvin-040506", 5_000, 1, 1)]
        assert next(iter(rx.devices.values())).stats.bad == 1
    finally:
        robot.close()


def test_face_link_includes_devices_with_the_face_flag(rx):
    bench = Robot(rx, 5, BENCH, b"\x01\x02\x03\x04\x05\x06")
    plain = Robot(rx, 5, protocol.FLAG_SIMULATED, b"\x01\x02\x03\x04\x05\x07")
    try:
        link = FaceLink(rx, Brain())
        assert [d.addr for d in link.screens()] == [bench.addr]
        link.send_state()
        assert [h.type for h, _ in bench.drain()] == [protocol.FACE_STATE]
        assert plain.drain() == []
    finally:
        bench.close()
        plain.close()


@pytest.mark.parametrize("before, after, sound", [
    ({"state": "on", "status": "idle"}, {"state": "on", "status": "listening"}, "wake"),
    ({"state": "on", "status": "speaking"}, {"state": "on", "status": "listening"}, None),   # follow-up window
    ({"state": "on", "status": "listening"}, {"state": "on", "status": "thinking"}, "done"),
    ({"state": "on", "status": "listening"}, {"state": "on", "status": "idle"}, "done"),
    ({"state": "on", "status": "thinking"}, {"state": "on", "status": "speaking"}, None),
    ({"state": "on", "status": "idle"}, {"state": "error", "status": "off"}, "error"),
    ({"state": "error", "status": "off"}, {"state": "error", "status": "off"}, None),
    ({"state": "on", "status": "idle", "muted": False}, {"state": "on", "status": "idle", "muted": True}, "done"),
    ({"state": "on", "status": "idle", "muted": True}, {"state": "on", "status": "idle", "muted": False}, "wake"),
    (None, {"state": "on", "status": "idle", "muted": True}, None),
    ({"state": "on", "status": "idle"}, {"state": "on", "status": "listening", "chime": False}, None),
])
def test_voice_chime(before, after, sound):
    assert voice_chime(before, after) == sound


def test_voice_chimes_go_to_buzzers_without_a_speaker(rx):
    bench = Robot(rx, 5, BENCH, b"\x01\x02\x03\x04\x05\x06")
    xiao = Robot(rx, 3, protocol.FLAG_AUDIO | protocol.FLAG_BUZZER, b"\x01\x02\x03\x04\x05\x07")
    try:
        chimes = VoiceChimes(rx)
        chimes("voice", {"state": "on", "status": "idle"})
        chimes("transcript", {"kind": "heard"})
        chimes("voice", {"state": "on", "status": "listening"})
        got = bench.drain()
        assert [(h.type, p) for h, p in got] == [(protocol.SOUND, protocol.sound("wake"))]
        assert xiao.drain() == []                     # the voice plays its own chime there
        assert chimes.sent == 1
    finally:
        bench.close()
        xiao.close()


def wait_for(cond, timeout=2.0):
    end = time.monotonic() + timeout
    while not cond() and time.monotonic() < end:
        time.sleep(0.01)
    return cond()


def test_buttons_act_on_the_voice(rx):
    robot = Robot(rx, 5, BENCH, b"\x01\x02\x03\x04\x05\x06")
    try:
        voice = FakeVoice()
        actions = ButtonActions(voice, rx)
        dev = next(iter(rx.devices.values()))
        actions.on_button(dev, 0, protocol.BUTTON_TALK, protocol.BUTTON_PRESS)
        assert wait_for(lambda: voice.calls == [("listen", True)])
        voice.status = "listening"
        actions.on_button(dev, 0, protocol.BUTTON_TALK, protocol.BUTTON_PRESS)   # talk again: close the window
        assert wait_for(lambda: voice.calls[-1] == ("listen", False))
        actions.on_button(dev, 0, protocol.BUTTON_MUTE, protocol.BUTTON_PRESS)
        assert wait_for(lambda: voice.calls[-1] == ("mute", True))
        actions.on_button(dev, 0, 7, protocol.BUTTON_PRESS)                        # unknown: ignored
        actions.on_button(dev, 0, protocol.BUTTON_TALK, 9)
        time.sleep(0.05)
        assert len(voice.calls) == 3 and actions.pressed == 3
        voice.state = "off"                                                         # voice off: error sound
        actions.on_button(dev, 0, protocol.BUTTON_TALK, protocol.BUTTON_PRESS)
        h, p = robot.recv()
        assert (h.type, p) == (protocol.SOUND, protocol.sound("error"))
    finally:
        robot.close()
