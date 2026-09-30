# SPDX-License-Identifier: MIT
"""Room sensors: the ENVIRONMENT message and the receiver."""
from marvin_host import protocol
from marvin_host.receiver import Receiver, Sink

MAC = bytes.fromhex("08d1f9112233")
ROOM_HELLO = protocol.Hello(MAC, 5, protocol.FLAG_SIMULATED | protocol.FLAG_ENVIRONMENT, -55, 61000, "0.1.0")


def test_environment_round_trip():
    e = protocol.Environment(pir=True, climate=True, light_sensor=True, motion=False, temperature_c=-5.2,
                             humidity=61.5, light=0.734, light_raw=1090, motion_age_s=125)
    b = e.encode()
    assert len(b) == protocol.Environment.SIZE == 12
    assert b.hex() == "071cccff6702de0242047d00"
    assert protocol.Environment.decode(b) == e
    assert protocol.Environment.decode(b + b"\xaa") == e          # a longer payload keeps its prefix


def test_environment_invalid_readings_are_none_and_zero():
    e = protocol.Environment(pir=True, climate=True, warming_up=True)
    b = e.encode()
    assert b.hex() == "03020000000000000000ffff"
    d = protocol.Environment.decode(b)
    assert (d.temperature_c, d.humidity, d.light, d.light_raw, d.motion_age_s) == (None,) * 5
    assert d.warming_up and d.pir and d.climate and not d.light_sensor
    # values without their valid flag mean nothing
    assert protocol.Environment.decode(bytes.fromhex("0100eb00c20100000000ffff")).temperature_c is None


def test_environment_too_short():
    try:
        protocol.Environment.decode(bytes(11))
    except protocol.ProtocolError as e:
        assert str(e) == "ENVIRONMENT too short"
    else:
        raise AssertionError("no error")


def test_hello_flag():
    assert ROOM_HELLO.has_environment and not ROOM_HELLO.has_camera
    assert not protocol.Hello(MAC, 5, protocol.FLAG_SIMULATED, -55, 0, "").has_environment


def test_receiver_hands_environment_to_the_sink():
    got = []

    class S(Sink):
        def on_environment(self, dev, t_us, env):
            got.append((dev.hello.device_name, t_us, env))

    rx = Receiver(S(), port=0, bind="127.0.0.1")
    try:
        addr = ("127.0.0.1", 47101)
        rx.handle(protocol.pack(protocol.HELLO, 0, 0, ROOM_HELLO.encode()), addr)
        env = protocol.Environment(pir=True, climate=True, motion=True, temperature_c=23.4, humidity=48.0,
                                   motion_age_s=0)
        rx.handle(protocol.pack(protocol.ENVIRONMENT, 1, 9000, env.encode()), addr)
        rx.handle(protocol.pack(protocol.ENVIRONMENT, 2, 9500, bytes(5)), addr)       # too short: bad
        assert got == [("marvin-112233", 9000, env)]
        assert rx.devices[addr].stats.bad == 1
    finally:
        rx.sock.close()
