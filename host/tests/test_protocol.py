# SPDX-License-Identifier: MIT
import math
import pathlib
import threading
import time

import numpy as np
import pytest

from marvin_host import frames, ld2450, ldrobot, protocol, scene, sim
from marvin_host.camera import SimCamera
from marvin_host.receiver import Receiver, Sink

# A real LD19/STL-19P packet from the LDROBOT development manual.
LD19_SAMPLE = bytes.fromhex(
    "542c6808ab7ee000e4dc00e2d900e5d500e3d300e4d000e9cd00e4ca00e2c700e9c500e5c200e5c000e5be823a1a50")


def test_crc_on_a_real_packet():
    assert len(LD19_SAMPLE) == ldrobot.SIZE
    assert ldrobot.crc8(LD19_SAMPLE[:-1]) == LD19_SAMPLE[-1]
    p = ldrobot.parse(LD19_SAMPLE)
    assert p.speed_dps == 2152
    assert p.angles_deg[0] == pytest.approx(324.27)
    assert p.angles_deg[-1] == pytest.approx(334.70)
    assert p.distances_mm[0] == 224


def test_lidar_roundtrip_and_wrap():
    raw = ldrobot.build(3600, 359.0, 1.2, list(range(100, 1300, 100)), [200] * 12, 12345)
    p = ldrobot.parse(raw)
    assert p.angles_deg[0] == pytest.approx(359.0)
    assert p.angles_deg[-1] == pytest.approx(1.2)
    assert all(a < 360 for a in p.angles_deg)
    assert list(p.distances_mm) == list(range(100, 1300, 100))
    bad = bytearray(raw)
    bad[10] ^= 1
    with pytest.raises(ValueError):
        ldrobot.parse(bytes(bad))


def test_ld2450_sign_bit():
    frame = ld2450.build([ld2450.Target(-250, 1200, -15, 320), ld2450.Target(400, 800, 5, 320)])
    assert len(frame) == ld2450.SIZE
    t = ld2450.parse(frame)
    assert [(a.x_mm, a.y_mm, a.speed_cms) for a in t] == [(-250, 1200, -15), (400, 800, 5)]
    # the datasheet example: 0E 03 B1 86 10 00 40 01 -> x -782 mm, y 1713 mm, speed -16 cm/s
    raw = ld2450.HEAD + bytes.fromhex("0e03b18610004001") + bytes(16) + ld2450.TAIL
    (d,) = ld2450.parse(raw)
    assert (d.x_mm, d.y_mm, d.speed_cms, d.resolution_mm) == (-782, 1713, -16, 320)


def test_header_and_hello():
    h = protocol.Hello(bytes.fromhex("a4cf12345678"), 1, 0, -61, 1234, "0.1.0")
    hdr, payload = protocol.unpack(protocol.pack(protocol.HELLO, 7, 99, h.encode()))
    assert (hdr.type, hdr.seq, hdr.t_us) == (protocol.HELLO, 7, 99)
    assert protocol.Hello.decode(payload) == h
    assert h.device_name == "marvin-345678"
    with pytest.raises(protocol.ProtocolError):
        protocol.unpack(b"XX" + bytes(14))


def test_frames():
    # 0 deg = front (-Y), 90 deg = the robot's right (-X)
    pts = frames.lidar_to_device(np.array([0.0, 90.0]), np.array([1000.0, 1000.0]))
    assert pts[0] == pytest.approx([0, -1000, frames.LIDAR.z_mm], abs=1e-3)
    assert pts[1] == pytest.approx([-1000, 0, frames.LIDAR.z_mm], abs=1e-3)
    a, d = frames.device_to_lidar(-1000, 0)
    assert (a, d) == pytest.approx((90, 1000))
    x, y, z = frames.ld2450_to_device(0, 1000)
    assert x == 0 and y < -1000 and z > frames.LD2450.z_mm
    assert frames.device_to_ld2450(x, y) == pytest.approx((0, 1000))


def test_simulated_apartment():
    # straight ahead at t = 40 s: the seated person, 850 mm away and 130 mm to the side
    assert sim.scan_distances(np.array([0.0]), 40.0)[0] == pytest.approx(850 - math.sqrt(scene.PERSON_RADIUS ** 2 - 130 ** 2), abs=2)
    # behind the robot the wall, 250 mm away; to its right the 3D printer
    assert sim.scan_distances(np.array([180.0, 90.0]), 0.0) == pytest.approx([250, 250], abs=5)
    # low furniture stays below the lidar plane: the sofa is not in the lidar segments
    assert all(b.name != "sofa" for b in scene.BOXES if b.lo[2] <= scene.LIDAR_Z <= b.hi[2])
    # the person never walks through furniture
    for t in np.arange(0, scene.LOOP, 0.1):
        p = scene.person_at(t)
        for b in scene.BOXES:
            if b.name.startswith("chair") or b.name == "rug" or b.lo[2] >= p.top:
                continue
            cx, cy = min(max(p.x, b.lo[0]), b.hi[0]), min(max(p.y, b.lo[1]), b.hi[1])
            assert math.hypot(cx - p.x, cy - p.y) >= scene.PERSON_RADIUS, (t, b.name)


def test_radar_fov_and_walls():
    assert sim.ld2450_targets(0.0) == []            # in the hallway, behind the wall
    (t,) = sim.ld2450_targets(40.0)                  # seated in front of the robot
    assert abs(t.x_mm) < 200 and 750 < t.y_mm < 900
    v = sim.vitals(40.0)
    assert v.valid and 10 < v.breath_rate < 20 and 55 < v.heart_rate < 80
    assert not sim.vitals(12.0).valid                # walking: no vital signs
    back = protocol.Vitals.decode(v.encode())
    assert back.heart_rate == pytest.approx(v.heart_rate, abs=0.01) and back.valid


def test_camera_renders_the_person():
    cam = SimCamera()
    img = cam.render(40.0)
    assert img.shape == (240, 320, 3)
    centre = img[90:130, 170:210].reshape(-1, 3).mean(axis=0)
    assert centre[0] > centre[2]                     # skin tone in the middle of the frame


def test_firmware_scene_header_is_current():
    path = pathlib.Path(__file__).parents[2] / "firmware" / "src" / "scene_data.h"
    assert path.read_text() == scene.c_header(), "run: python -m marvin_host.scene > ../firmware/src/scene_data.h"


def test_end_to_end_over_udp():
    got = {"hello": 0, "scans": [], "targets": []}

    class S(Sink):
        def on_hello(self, dev):
            got["hello"] += 1

        def on_scan(self, dev, t_us, points, inten, speed):
            got["scans"].append(points)

        def on_targets(self, dev, t_us, targets, points):
            got["targets"].append(points)

    rx = Receiver(S(), port=0, bind="127.0.0.1")
    port = rx.sock.getsockname()[1]
    stop = threading.Event()
    th = threading.Thread(target=rx.serve, kwargs={"stop": stop}, daemon=True)
    th.start()
    dev = sim.SimDevice(host="127.0.0.1", port=port)
    dev.run(duration=1.5)
    time.sleep(0.2)
    stop.set()
    th.join()

    assert got["hello"] == 1
    assert len(got["scans"]) >= 5                      # ~10 revolutions per second
    scan = got["scans"][-1]
    assert 300 < len(scan) <= 460                      # D500: 450 points per revolution
    assert scan[:, 1].min() < -3000                              # the far wall of the room
    assert len(got["targets"]) >= 5
    (d,) = rx.devices.values()
    assert d.stats.crc_errors == 0 and d.stats.lost == 0
