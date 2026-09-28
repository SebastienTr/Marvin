# SPDX-License-Identifier: MIT
"""Protocol v1 test vectors from the Python host (docs/protocol.md): golden/protocol/vectors.json.

Every vector is a whole datagram as hex, its header, and what the Python host decodes from it (or
the error it raises). Host -> robot vectors are what the Python host encodes for the given input.
The Java robot adapter must decode and encode the same bytes.

    python3 protocol_vectors.py
"""
from __future__ import annotations

import numpy as np

from _common import GOLDEN, rel, write_json

from marvin_host import frames, ld2450, ldrobot, protocol  # noqa: E402
from marvin_host.events import EventKind, PresenceState  # noqa: E402

# A real LD19/STL-19P packet from the LDROBOT development manual (also in host/tests/test_protocol.py).
LD19_SAMPLE = bytes.fromhex(
    "542c6808ab7ee000e4dc00e2d900e5d500e3d300e4d000e9cd00e4ca00e2c700e9c500e5c200e5c000e5be823a1a50")
# The HLK-LD2450 datasheet example target: x -782 mm, y 1713 mm, speed -16 cm/s, resolution 320 mm.
LD2450_DATASHEET = ld2450.HEAD + bytes.fromhex("0e03b18610004001") + bytes(16) + ld2450.TAIL


def header(datagram: bytes) -> dict:
    h, _ = protocol.unpack(datagram)
    return {"type": h.type, "seq": h.seq, "t_us": h.t_us}


def vec(name: str, typ: int, seq: int, t_us: int, payload: bytes, decoded, note: str = "") -> dict:
    d = protocol.pack(typ, seq, t_us, payload)
    v = {"name": name, "type": typ, "datagram": d.hex(), "header": header(d), "payload": payload.hex(),
         "decoded": decoded}
    if note:
        v["note"] = note
    return v


def hello_decoded(h: protocol.Hello) -> dict:
    return {"device_id": h.device_id.hex(), "board": h.board, "board_name": protocol.BOARDS.get(h.board),
            "flags": h.flags, "simulated": h.simulated, "has_camera": h.has_camera, "has_audio": h.has_audio,
            "rssi": h.rssi, "uptime_ms": h.uptime_ms, "firmware": h.firmware, "device_name": h.device_name}


def lidar_packet_decoded(raw: bytes) -> dict:
    p = ldrobot.parse(raw)
    return {"speed_dps": p.speed_dps, "angles_deg": [float(a) for a in p.angles_deg],
            "distances_mm": [int(d) for d in p.distances_mm], "intensities": [int(i) for i in p.intensities],
            "timestamp_ms": p.timestamp_ms, "crc": raw[-1]}


def targets_decoded(frame: bytes) -> list[dict]:
    out = []
    for t in ld2450.parse(frame):
        x, y, z = frames.ld2450_to_device(t.x_mm, t.y_mm)
        out.append({"x_mm": t.x_mm, "y_mm": t.y_mm, "speed_cms": t.speed_cms, "resolution_mm": t.resolution_mm,
                    "device_mm": [x, y, z]})
    return out


def vitals_decoded(payload: bytes) -> dict:
    v = protocol.Vitals.decode(payload)
    return {"valid": v.valid, "breath_rate": v.breath_rate, "heart_rate": v.heart_rate,
            "breath_wave": v.breath_wave, "heart_wave": v.heart_wave, "distance_mm": v.distance_mm}


def face_state_decoded(payload: bytes) -> dict:
    f = protocol.FaceState.decode(payload)
    return {"present": f.present, "seated": f.seated, "head": f.head, "position": f.position,
            "distance_m": f.distance_m, "heart_rate": f.heart_rate}


def error(fn, data) -> str | None:
    try:
        fn(data)
    except (ValueError, protocol.ProtocolError) as e:
        return f"{type(e).__name__}: {e}"
    return None


def robot_to_host() -> list[dict]:
    out = []
    # HELLO: a simulator, a camera + audio robot, the vitals bridge, an empty firmware string
    for name, h in [
        ("hello_simulator", protocol.Hello(bytes.fromhex("024d5653494d"), 255, protocol.FLAG_SIMULATED, -40, 2000, "sim-0.1.0")),
        ("hello_xiao_camera_audio", protocol.Hello(bytes.fromhex("a4cf12345678"), 3,
                                                   protocol.FLAG_CAMERA | protocol.FLAG_AUDIO, -61, 123456, "0.6.0")),
        ("hello_mr60_bridge", protocol.Hello(bytes.fromhex("58cf79aabbcc"), 4, 0, -72, 4294967295, "mr60-0.2.1")),
        ("hello_d1_mini_no_firmware", protocol.Hello(bytes.fromhex("5ccf7f010203"), 1, protocol.FLAG_SIMULATED, -128, 0, "")),
    ]:
        out.append(vec(name, protocol.HELLO, 7, 99_000, h.encode(), hello_decoded(h)))
    # LIDAR: the real LD19 packet, a built one crossing 0 degrees, a datagram with a bad CRC in the middle
    built = ldrobot.build(3600, 359.0, 1.2, list(range(100, 1300, 100)), [200] * 12, 12345)
    no_return = ldrobot.build(3600, 90.0, 97.7, [0] * 6 + [1500] * 6, [0] * 6 + [120] * 6, 29999)
    bad = bytearray(built)
    bad[10] ^= 1
    for name, model, pkts, note in [
        ("lidar_ld19_manual_packet", 1, [LD19_SAMPLE], "the LDROBOT manual's sample packet"),
        ("lidar_wraps_past_zero", 2, [built, no_return], "angles interpolate across 360 -> 0; distance 0 = no return"),
        ("lidar_bad_crc_in_the_middle", 1, [LD19_SAMPLE, bytes(bad), no_return],
         "the second packet fails its CRC: it is counted in crc_errors and skipped, the others are kept"),
    ]:
        dec = []
        for raw in pkts:
            err = error(ldrobot.parse, raw)
            dec.append({"error": err, "crc_expected": ldrobot.crc8(raw[:-1])} if err else lidar_packet_decoded(raw))
        out.append(vec(name, protocol.LIDAR, 1000, 5_000_000, bytes([model]) + b"".join(pkts),
                       {"model": model, "model_name": protocol.LIDAR_MODELS.get(model), "packets": dec}, note))
    # LD2450
    two = ld2450.build([ld2450.Target(-250, 1200, -15, 320), ld2450.Target(400, 800, 5, 320)])
    for name, frame, note in [
        ("ld2450_datasheet_example", LD2450_DATASHEET, "x -782 mm, y 1713 mm, speed -16 cm/s"),
        ("ld2450_two_targets", two, "sign bit: bit 15 set = positive"),
        ("ld2450_empty", ld2450.build([]), "no target: all slots zero"),
    ]:
        out.append(vec(name, protocol.LD2450, 1001, 5_100_000, frame, {"targets": targets_decoded(frame)}, note))
    bad_frame = bytearray(two)
    bad_frame[-1] = 0
    out.append(vec("ld2450_bad_tail", protocol.LD2450, 1002, 5_200_000, bytes(bad_frame),
                   {"error": error(ld2450.parse, bytes(bad_frame))}, "counted as a bad frame, dropped"))
    # VITALS
    for name, v in [
        ("vitals_valid", protocol.Vitals(True, 14.5, 67.25, 0.5, -0.25, 820)),
        ("vitals_invalid", protocol.Vitals(False, 0.0, 0.0, 0.0, 0.0, 0)),
        ("vitals_clamped_waves", protocol.Vitals(True, 12.0, 60.0, 3.0, -7.0, 70000)),
        # halves round to even, as Python's round() does (Java: Math.rint)
        ("vitals_rounds_half_to_even", protocol.Vitals(True, 12.345, 60.125, 0.5 / 32767, 1.5 / 32767, 5)),
    ]:
        p = v.encode()
        out.append(vec(name, protocol.VITALS, 1003, 5_300_000, p, vitals_decoded(p),
                       {"vitals_clamped_waves": "encode clamps waves to -1..1 and the distance to u16",
                        "vitals_rounds_half_to_even": "encode rounds halves to even (Python round)"}.get(name, "")))
    # LOG
    out.append(vec("log_utf8", protocol.LOG, 1004, 5_400_000, "firmware up, lidar D800 · ok".encode(),
                   {"text": "firmware up, lidar D800 · ok"}))
    # AUDIO_IN
    pcm = np.array([0, 1, -1, 32767, -32768] + [i * 97 - 15000 for i in range(315)], dtype=np.int16)
    a = protocol.AudioIn(4_294_967_000, pcm)
    p = a.encode()
    out.append(vec("audio_in_320_samples", protocol.AUDIO_IN, 1005, 5_500_000, p,
                   {"index": 4_294_967_000, "samples": len(pcm), "first": pcm[:8].tolist(), "last": pcm[-4:].tolist()}))
    out.append(vec("audio_in_odd_length", protocol.AUDIO_IN, 1006, 5_600_000, p[:-1],
                   {"error": error(protocol.AudioIn.decode, p[:-1])}, "counted as bad, dropped"))
    return out


def host_to_robot() -> list[dict]:
    out = []
    # HOST_ACK: the Python receiver sends sequence 0 and header clock 0, the host clock in the payload
    ack = protocol.pack(protocol.HOST_ACK, 0, 0, (123_456_789).to_bytes(8, "little"))
    out.append({"name": "host_ack", "type": protocol.HOST_ACK, "datagram": ack.hex(), "header": header(ack),
                "payload": ack[16:].hex(), "decoded": {"host_clock_us": 123_456_789},
                "note": "receiver.py sends seq 0 and t_us 0 in the header of every HOST_ACK"})
    # FACE_STATE from presence states
    states = [
        ("face_state_nobody", PresenceState()),
        ("face_state_seated", PresenceState(present=True, seated=True, position=(130.4, -850.6, 160.0),
                                            head=(130.4, -850.6, 550.0), distance_m=0.8606, heart_rate=67.254)),
        ("face_state_standing_no_vitals", PresenceState(present=True, position=(-1200.0, -2500.2, 300.0),
                                                        head=(-1200.0, -2500.2, 1000.0), distance_m=2.773)),
        ("face_state_clamped", PresenceState(present=True, position=(40000.0, -40000.0, 0.0), head=None,
                                             distance_m=70.0, heart_rate=700.0)),
        # Python's round() rounds halves to even: 0.5 -> 0, 1.5 -> 2, 2.5 -> 2, -0.5 -> 0 (Java: Math.rint)
        ("face_state_rounds_half_to_even", PresenceState(present=True, position=(0.5, 1.5, 2.5), head=(-0.5, -1.5, 3.5),
                                                         distance_m=0.0005, heart_rate=60.125)),
    ]
    for name, s in states:
        f = protocol.FaceState.from_presence(s)
        p = f.encode()
        out.append(vec(name, protocol.FACE_STATE, 3, 1_000_000, p,
                       {"input": {"present": s.present, "seated": s.seated, "head": s.head, "position": s.position,
                                  "distance_m": s.distance_m, "heart_rate": s.heart_rate},
                        "decoded": face_state_decoded(p)},
                       "coordinates rounded to the mm and clamped to +-32767; distance and heart rate to u16"))
    for kind in EventKind:
        out.append(vec(f"face_event_{kind.value}", protocol.FACE_EVENT, 4, 1_100_000, protocol.face_event(kind),
                       {"event": kind.value, "code": protocol.FACE_EVENT_CODES[kind]}))
    # AUDIO_OUT
    pcm = np.array([0, 100, -100, 32767, -32768, 7], dtype=np.int16)
    for name, stream, index, samples in [("audio_out_6_samples", 3, 0, pcm),
                                         ("audio_out_320_samples_wrapping", 65535, 4_294_967_200,
                                          np.arange(-160, 160, dtype=np.int16) * 37)]:
        p = protocol.AudioOut(stream, index, samples).encode()
        out.append(vec(name, protocol.AUDIO_OUT, 5, 1_200_000, p,
                       {"stream": stream, "index": index, "samples": len(samples), "first": samples[:6].tolist()}))
    too_many = protocol.AudioOut(1, 0, np.zeros(481, dtype=np.int16))
    out.append({"name": "audio_out_too_many_samples", "type": protocol.AUDIO_OUT,
                "decoded": {"error": error(lambda _: too_many.encode(), None), "samples": 481},
                "note": "encode refuses more than 480 samples (30 ms)"})
    for name, cmd, arg in [("audio_ctrl_mic_start", protocol.AUDIO_MIC_START, 0),
                           ("audio_ctrl_mic_stop", protocol.AUDIO_MIC_STOP, 0),
                           ("audio_ctrl_play_stop", protocol.AUDIO_PLAY_STOP, 0),
                           ("audio_ctrl_volume_60", protocol.AUDIO_VOLUME, 60),
                           ("audio_ctrl_mic_gain_12", protocol.AUDIO_MIC_GAIN, 12),
                           ("audio_ctrl_argument_clamped", protocol.AUDIO_VOLUME, 300)]:
        out.append(vec(name, protocol.AUDIO_CTRL, 6, 1_300_000, protocol.audio_ctrl(cmd, arg),
                       {"command": cmd, "argument_in": arg, "argument": max(0, min(255, arg))}))
    for sname, (sid, ms) in protocol.SOUNDS.items():
        out.append(vec(f"sound_{sname}", protocol.SOUND, 7, 1_400_000, protocol.sound(sname),
                       {"sound": sname, "id": sid, "duration_ms": ms}))
    return out


def invalid() -> list[dict]:
    out = []
    for name, data in [("too_short", b"MV\x01\x01" + bytes(10)),
                       ("bad_magic", b"XX" + bytes(14)),
                       ("unsupported_version", b"MV\x02\x01" + bytes(12))]:
        out.append({"name": name, "datagram": data.hex(), "error": error(protocol.unpack, data)})
    return out


def main() -> None:
    doc = {
        "about": "Protocol v1 test vectors, generated from the Python host (host/marvin_host) by "
                 "host-java/marvin-contracts/tools/protocol_vectors.py. Hex strings are lowercase; "
                 "floats are the Python host's values (compare with a tolerance of 1e-9 unless stated).",
        "constants": {"magic": protocol.MAGIC.hex(), "version": protocol.VERSION, "header_size": protocol.HEADER.size,
                      "host_port": protocol.HOST_PORT, "device_port": protocol.DEVICE_PORT,
                      "boards": protocol.BOARDS, "lidar_models": protocol.LIDAR_MODELS,
                      "screen_boards": sorted(protocol.SCREEN_BOARDS),
                      "flags": {"simulated": protocol.FLAG_SIMULATED, "camera": protocol.FLAG_CAMERA,
                                "audio": protocol.FLAG_AUDIO},
                      "face_event_codes": {k.value: v for k, v in protocol.FACE_EVENT_CODES.items()},
                      "sounds": {k: {"id": v[0], "duration_ms": v[1]} for k, v in protocol.SOUNDS.items()},
                      "audio": {"rate": protocol.AUDIO_RATE, "in_samples": protocol.AUDIO_IN_SAMPLES,
                                "out_max_samples": protocol.AUDIO_OUT_MAX_SAMPLES},
                      "ldrobot": {"size": ldrobot.SIZE, "points": ldrobot.POINTS, "crc_poly": "0x4d",
                                  "crc_table": ldrobot.CRC_TABLE},
                      "ld2450": {"size": ld2450.SIZE},
                      "extrinsics": {"lidar": vars(frames.LIDAR), "ld2450": vars(frames.LD2450)}},
        "robot_to_host": robot_to_host(),
        "host_to_robot": host_to_robot(),
        "invalid": invalid(),
    }
    print("wrote", rel(write_json(GOLDEN / "protocol" / "vectors.json", doc)))


if __name__ == "__main__":
    main()
