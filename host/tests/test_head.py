# SPDX-License-Identifier: MIT
"""The robot's head: the HEAD message, HEAD_CTRL, and the receiver."""
from marvin_host import protocol
from marvin_host.receiver import Receiver, Sink

MAC = bytes.fromhex("08d1f9112233")
LOOKS_HELLO = protocol.Hello(MAC, 5, protocol.FLAG_BUTTONS | protocol.FLAG_BUZZER | protocol.FLAG_FACE, -55, 61000,
                             "0.1.0")


def test_head_round_trip():
    h = protocol.Head(mode=protocol.HEAD_AUTO, yaw_deg=-12.3, goal_deg=-12.5, target_yaw_deg=-12.6,
                      target_distance_mm=1234, tracking=True, servo_on=True)
    b = h.encode()
    assert len(b) == protocol.Head.SIZE == 10
    assert b.hex() == "010b85ff83ff82ffd204"
    assert protocol.Head.decode(b) == h
    assert protocol.Head.decode(b + b"\xaa") == h          # a longer payload keeps its prefix
    assert h.mode_name == "auto"


def test_head_without_target_and_unknown_mode():
    h = protocol.Head.decode(bytes.fromhex("0904c8000000e803e803"))  # glancing; target fields without their flag
    assert (h.target_yaw_deg, h.target_distance_mm) == (None, None)
    assert h.glancing and not h.tracking and not h.servo_on
    assert h.yaw_deg == 20.0 and h.mode_name is None


def test_head_too_short():
    try:
        protocol.Head.decode(bytes(9))
    except protocol.ProtocolError as e:
        assert str(e) == "HEAD too short"
    else:
        raise AssertionError("no error")


def test_head_ctrl():
    assert protocol.head_ctrl(protocol.HEAD_MANUAL, -45).hex() == "023efe"
    assert protocol.head_ctrl(protocol.HEAD_CENTER).hex() == "030000"
    assert protocol.head_ctrl(protocol.HEAD_AUTO, 1e6).hex() == "01ff7f"   # clamped


def test_receiver_hands_head_to_the_sink():
    got = []

    class S(Sink):
        def on_head(self, dev, t_us, head):
            got.append((dev.hello.device_name, t_us, head))

    rx = Receiver(S(), port=0, bind="127.0.0.1")
    try:
        addr = ("127.0.0.1", 47101)
        rx.handle(protocol.pack(protocol.HELLO, 0, 0, LOOKS_HELLO.encode()), addr)
        h = protocol.Head(yaw_deg=30.0, goal_deg=30.0, servo_on=True)
        rx.handle(protocol.pack(protocol.HEAD, 1, 9000, h.encode()), addr)
        rx.handle(protocol.pack(protocol.HEAD, 2, 9500, bytes(5)), addr)       # too short: bad
        assert got == [("marvin-112233", 9000, h)]
        assert rx.devices[addr].stats.bad == 1
    finally:
        rx.sock.close()
