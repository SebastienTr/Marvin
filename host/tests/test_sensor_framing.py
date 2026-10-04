# SPDX-License-Identifier: MIT
"""The Python sensor framers against the firmware's own tests (firmware/test/test_sensor_framing): same streams,
same frames, same counters."""
from __future__ import annotations

import random

from marvin_host import ld2450, ldrobot, sim
from marvin_host.sensor_framing import (ACK, END_CONFIG, ENABLE_CONFIG, MULTI_TARGET, TARGETS, Ld2450Framer,
                                        LidarFramer, ld2450_command, ld2450_multi_target_sequence, parse_ack)

# A real LD19 packet captured from a sensor (CRC valid), as in the firmware's tests and the golden vectors.
LD19 = bytes.fromhex("542c6808ab7ee000e4dc00e2d900e5d500e3d300e4d000e9cd00e4ca00e2c700e9c500e5c200e5c000e5be823a1a50")
# "HLK-LD2450 Serial Communication Protocol V1.03", section 2.3: one target at x -782 mm, y 1713 mm.
LD2450_EXAMPLE = bytes([0xAA, 0xFF, 0x03, 0x00, 0x0E, 0x03, 0xB1, 0x86, 0x10, 0x00, 0x40, 0x01] + [0] * 16 + [0x55, 0xCC])
ENABLE_ACK = bytes([0xFD, 0xFC, 0xFB, 0xFA, 0x08, 0x00, 0xFF, 0x01, 0x00, 0x00, 0x01, 0x00, 0x40, 0x00,
                    0x04, 0x03, 0x02, 0x01])
MULTI_ACK = bytes([0xFD, 0xFC, 0xFB, 0xFA, 0x04, 0x00, 0x90, 0x01, 0x00, 0x00, 0x04, 0x03, 0x02, 0x01])


def feed(framer, data: bytes, chunk: int = 0) -> list:
    chunk = chunk or len(data) or 1
    out = []
    for i in range(0, len(data), chunk):
        out += framer.feed(data[i:i + chunk])
    return out


# ---- lidar


def test_crc_of_real_packet():
    assert len(LD19) == 47 and ldrobot.crc8(LD19[:46]) == LD19[46]


def test_lidar_single_packet():
    f = LidarFramer()
    assert feed(f, LD19) == [LD19]
    assert (f.packets, f.crc_errors, f.dropped_bytes, f.pending) == (1, 0, 0, 0)


def test_lidar_garbage_between_and_partial():
    junk = bytes([0x00, 0x54, 0x13, 0x2C, 0x54, 0x54, 0xFF])
    stream = junk + LD19 + LD19[:20] + LD19 + junk + LD19
    f = LidarFramer()
    assert feed(f, stream) == [LD19] * 3
    # the partial packet swallows the header of the next one: its CRC fails, then resync finds it
    assert f.crc_errors == 1
    assert f.dropped_bytes == 2 * len(junk) + 20


def test_lidar_split_in_every_chunk_size():
    stream = (b"\xA5" + LD19) * 4
    for chunk in range(1, len(stream) + 1):
        f = LidarFramer()
        assert len(feed(f, stream, chunk)) == 4, chunk
        assert f.dropped_bytes == 4


def test_lidar_corrupted_crc_rejected():
    bad = bytearray(LD19)
    bad[10] ^= 0x01
    wrong_crc = bytearray(LD19)
    wrong_crc[46] ^= 0xFF
    f = LidarFramer()
    assert feed(f, bytes(bad) + LD19 + bytes(wrong_crc) + LD19) == [LD19, LD19]
    assert f.crc_errors == 2 and f.dropped_bytes == 2 * 47


def test_lidar_fake_header_inside_garbage():
    f = LidarFramer()
    assert feed(f, bytes([0x54, 0x2C, 0x01, 0x02, 0x03]) + LD19 + LD19) == [LD19, LD19]
    assert f.crc_errors == 1 and f.dropped_bytes == 5


def test_lidar_trailing_header_byte_is_held():
    f = LidarFramer()
    assert feed(f, LD19 + b"\x00\x54") == [LD19]
    assert f.pending == 1 and f.dropped_bytes == 1
    assert f.feed(LD19[1:]) == [LD19]
    assert f.pending == 0


def stl27l_packets(n: int, seed: int = 0) -> list[bytes]:
    """n packets of the simulated room as a D800 / STL-27L sends them (21600 points/s, 10 Hz)."""
    dev = sim.SimDevice(host="127.0.0.1", port=9, model=2, seed=seed)
    try:
        out, angle, t = [], 0.0, 0.0
        while len(out) < n:
            pkts, angle = dev.lidar_packets(t, angle, min(10, n - len(out)))
            out += pkts
            t += 10 / 1800
        return out
    finally:
        dev.sock.close()


def test_lidar_sim_round_trip():
    # the simulated room's packets with noise bursts, in odd-sized chunks
    rng = random.Random(1)
    sent = stl27l_packets(500)
    stream = bytearray()
    junk = 0
    for i, p in enumerate(sent):
        if i % 7 == 3:
            n = rng.randint(1, 9)
            stream += bytes(0x40 + rng.randrange(16) for _ in range(n))
            junk += n
        stream += p
    f = LidarFramer()
    got, i = [], 0
    while i < len(stream):
        n = rng.randint(1, 97)
        got += f.feed(bytes(stream[i:i + n]))
        i += n
    assert got == sent
    assert f.crc_errors == 0 and f.dropped_bytes == junk


# ---- LD2450


def test_ld2450_datasheet_frame():
    f = Ld2450Framer()
    (got,) = feed(f, LD2450_EXAMPLE)
    assert got == (TARGETS, LD2450_EXAMPLE)
    (t,) = ld2450.parse(got[1])
    assert (t.x_mm, t.y_mm, t.speed_cms) == (-782, 1713, -16)
    assert f.frames == 1


def test_ld2450_mixed_stream_with_acks_and_garbage():
    junk = bytes([0x55, 0xCC, 0xAA, 0xFF, 0x01, 0xFD, 0xFC, 0x00])
    frame = LD2450_EXAMPLE
    stream = frame[13:] + frame + ENABLE_ACK + junk + MULTI_ACK + frame + junk + frame
    for chunk in range(1, len(stream) + 1):
        f = Ld2450Framer()
        got = feed(f, stream, chunk)
        assert [g[1] for g in got if g[0] == TARGETS] == [frame] * 3
        assert [g[1] for g in got if g[0] == ACK] == [ENABLE_ACK, MULTI_ACK]
        assert (f.frames, f.acks) == (3, 2)


def test_ld2450_bad_footer_rejected():
    bad = bytearray(LD2450_EXAMPLE)
    bad[29] = 0
    bad_ack = bytearray(MULTI_ACK)
    bad_ack[4] = 0xF0                       # absurd in-frame length
    f = Ld2450Framer()
    got = feed(f, bytes(bad) + LD2450_EXAMPLE + bytes(bad_ack) + LD2450_EXAMPLE)
    assert got == [(TARGETS, LD2450_EXAMPLE)] * 2
    assert f.bad_frames == 2 and f.dropped_bytes == 30 + len(MULTI_ACK)


def test_ld2450_sim_round_trip():
    sent, stream = [], bytearray()
    for i in range(300):                    # 30 s of the simulated scene at 10 Hz
        p = ld2450.build(sim.ld2450_targets(i * 0.1))
        sent.append(p)
        stream += p
        if i % 5 == 0:
            stream.append(0xAA)             # stray header byte
    f = Ld2450Framer()
    assert [g[1] for g in feed(f, bytes(stream), 13)] == sent
    assert f.bad_frames == 0 and f.dropped_bytes == 60


# ---- LD2450 commands


def test_ld2450_command_bytes():
    assert ld2450_command(ENABLE_CONFIG, b"\x01\x00") == bytes.fromhex("fdfcfbfa0400ff00010004030201")
    assert ld2450_command(END_CONFIG) == bytes.fromhex("fdfcfbfa0200fe0004030201")
    assert ld2450_command(MULTI_TARGET) == bytes.fromhex("fdfcfbfa0200900004030201")
    assert ld2450_multi_target_sequence() == (bytes.fromhex("fdfcfbfa0400ff00010004030201")
                                              + bytes.fromhex("fdfcfbfa0200900004030201")
                                              + bytes.fromhex("fdfcfbfa0200fe0004030201"))


def test_ld2450_parse_ack():
    assert parse_ack(ENABLE_ACK) == (ENABLE_CONFIG, 0)
    assert parse_ack(MULTI_ACK) == (MULTI_TARGET, 0)
    failed = bytearray(MULTI_ACK)
    failed[8] = 0x01
    assert parse_ack(bytes(failed)) == (MULTI_TARGET, 1)
    assert parse_ack(ld2450_command(END_CONFIG)) is None     # a command we sent is not an ACK
    assert parse_ack(LD2450_EXAMPLE) is None
