# SPDX-License-Identifier: MIT
"""Room sensors: the ENVIRONMENT message and the receiver."""
import pytest

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


# ---------------------------------------------------------------- the brain: a PIR as the presence

from marvin_host import frames, sim  # noqa: E402
from marvin_host.brain import Brain  # noqa: E402
from marvin_host.events import EventKind as K  # noqa: E402
from marvin_host.receiver import Device  # noqa: E402

PLAIN_SIM = protocol.Hello(MAC, 5, protocol.FLAG_SIMULATED, -55, 0, "0.1.0")


def dev(hello=ROOM_HELLO, port=47101):
    return Device(hello, ("192.168.1.43", port))


def radar(brain, d, t0, t1):
    """The simulated room's LD2450 and vitals frames from device `d`, device times [t0, t1) s."""
    for i in range(round(t0 * 10), round(t1 * 10)):
        t = i / 10
        targets = sim.ld2450_targets(t)
        brain.on_targets(d, round(t * 1e6), targets, [frames.ld2450_to_device(g.x_mm, g.y_mm) for g in targets])
        brain.on_vitals(d, round(t * 1e6), sim.vitals(t))


def pir(brain, d, t, motion, warming_up=False, simulated=False):
    brain.on_environment(d, round(t * 1e6), protocol.Environment(pir=True, climate=True, motion=motion,
                                                                 warming_up=warming_up, simulated=simulated))


def test_a_real_pir_is_the_presence_of_a_simulated_board():
    brain = Brain()
    events = []
    brain.add_listener(events.append)
    d = dev()
    pir(brain, d, 0.0, False, warming_up=True)
    radar(brain, d, 0.0, 40.0)                  # the simulated person walks in and sits: ignored
    assert events == [] and not brain.state.present
    assert brain.state.motion_presence and not brain.state.simulated
    assert brain.state.t_us == 0                # held back: its clock does not even move
    pir(brain, d, 45.0, False, warming_up=True)
    pir(brain, d, 61.0, True)                   # warm, and someone waves
    assert [e.kind for e in events] == [K.ARRIVED]
    assert events[0].detail == "movement on the motion sensor" and events[0].data == {}
    s = brain.state
    assert s.present and s.position is None and s.head is None and s.distance_m is None and not s.seated
    for t in range(66, 180, 5):                 # still for a while: still there
        pir(brain, d, t, False)
    assert brain.state.present and brain.state.still_s == pytest.approx(115.0)
    pir(brain, d, 181.0, False)                 # 120 s without any movement: left
    assert [e.kind for e in events] == [K.ARRIVED, K.LEFT]
    assert events[1].detail == "no movement for 120 s"
    assert not brain.state.present and brain.state.motion_presence
    pir(brain, d, 186.0, True)
    assert [e.kind for e in events] == [K.ARRIVED, K.LEFT, K.ARRIVED]


def test_a_simulated_board_without_a_pir_keeps_its_simulated_person():
    brain = Brain()
    d = dev()
    brain.on_environment(d, 0, protocol.Environment(climate=True, temperature_c=22.0, humidity=50.0))
    radar(brain, d, 0.0, 12.0)
    assert brain.state.present and brain.state.simulated and not brain.state.motion_presence
    other = dev(PLAIN_SIM, 47102)               # no room sensors at all: as before
    b2 = Brain()
    radar(b2, other, 0.0, 12.0)
    assert b2.state.present and b2.state.simulated


def test_a_live_radar_knows_better_than_the_pir():
    brain = Brain()
    real = dev(protocol.Hello(MAC, 3, protocol.FLAG_ENVIRONMENT, -50, 0, "0.1.0"))
    radar(brain, real, 0.0, 4.0)                # the person is not in view yet: nobody
    assert not brain.state.present
    pir(brain, real, 3.95, True)                # motion, but the radar sees nobody
    assert not brain.state.present and not brain.state.motion_presence
    radar(brain, real, 4.0, 12.0)
    assert brain.state.present and not brain.state.motion_presence
