#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""A second simulated device: an ESP32-S3 DevKitC (board 2, has the face screen) that says HELLO and
counts what the host sends back (HOST_ACK, FACE_STATE, FACE_EVENT)."""
import collections, pathlib, socket, sys, time
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "host"))
from marvin_host import protocol

secs = float(sys.argv[1]) if len(sys.argv) > 1 else 20
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("127.0.0.1", 0)); s.settimeout(0.1)
mac = bytes([0x02, 0x4D, 0x56, 0x53, 0x33, 0x02])
seq, t0, counts, nxt = 0, time.monotonic(), collections.Counter(), 0
names = {v: k for k, v in vars(protocol).items() if k.isupper() and isinstance(v, int) and k in
         ("HOST_ACK", "FACE_STATE", "FACE_EVENT", "SOUND", "AUDIO_OUT", "AUDIO_CTRL")}
while time.monotonic() - t0 < secs:
    now = time.monotonic() - t0
    if now >= nxt:
        h = protocol.Hello(mac, 2, protocol.FLAG_SIMULATED, -50, int(now * 1000), "sim-s3-0.1.0")
        s.sendto(protocol.pack(protocol.HELLO, seq, int(now * 1e6), h.encode()), ("127.0.0.1", 47100)); seq += 1
        nxt = now + 1.0
    try:
        data, _ = s.recvfrom(2048)
        hdr, _ = protocol.unpack(data)
        counts[names.get(hdr.type, hdr.type)] += 1
    except socket.timeout:
        pass
print(dict(counts), "face_state_hz=%.1f" % (counts["FACE_STATE"] / secs))
