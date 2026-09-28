<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# marvin-contracts

What the Java host must reproduce, taken from the Python host (`host/marvin_host`), and the
contract between the Java core and the voice sidecar. See [docs/design.md](../../docs/design.md),
sections 3.5 and 4.3.

Everything under `golden/` is **generated** by the scripts in `tools/`, from this checkout's Python
host. Do not edit it by hand. Regenerate only on purpose, and review the diff: these files are the
contract.

```bash
python3 host-java/marvin-contracts/tools/generate_all.py            # everything (about 40 s)
python3 host-java/marvin-contracts/tools/generate_all.py --no-api   # the deterministic part only
```

The scripts need the Python host's dependencies (`pip install -e "host[dev]"`), nothing else.
The Maven module puts `golden/` on the classpath under `marvin/contracts/golden/`, and
`src/main/proto/` under `marvin/contracts/proto/`, for the other modules' tests.

## What is here

| Path | Made by | Content |
|---|---|---|
| `golden/protocol/vectors.json` | `protocol_vectors.py` | Protocol v1 test vectors: every message type, whole datagrams as hex, their header and decoded values (`HELLO`, `LIDAR` with the LDROBOT manual's packet and CRC failures, `LD2450` with the datasheet frame, `VITALS`, `LOG`, `AUDIO_IN`; `HOST_ACK`, `FACE_STATE`, `FACE_EVENT`, `AUDIO_OUT`, `AUDIO_CTRL`, `SOUND`), invalid datagrams, and the protocol constants (CRC table, board ids, extrinsics) |
| `golden/recordings/*.mvrec.gz` | `recordings.py` | Recordings of the simulated robot (`.mvrec` v1, gzip): `room_loop` (walks in, sits, vital signs acquired and lost at each fidget, stands up, leaves), `lidar_yaw` (lidar mounted 37° off), `robot_reboot` (the device clock jumps back), `damaged_link` (lost datagrams, CRC errors, bad frames, junk) |
| `golden/recordings/*.events.jsonl` | `recordings.py` | The Python brain's events for each recording, in order, with their device times |
| `golden/recordings/*.states.jsonl` | `recordings.py` | The brain's presence state at the end of each device tick |
| `golden/recordings/*.scans.jsonl` | `recordings.py` | The lidar revolutions the receiver assembled (device time, points, speed) |
| `golden/recordings/index.json` | `recordings.py` | The scenarios, the receiver's counters per device, the brain's configuration, the lidar yaw estimate |
| `golden/api/get/*.json`, `post/*.json`, `access/*.json` | `api_snapshots.py` | Every app endpoint in demo mode: request, status, headers, body and its shape (see below); the access key rules |
| `golden/api/sse.json` | `api_snapshots.py` | The two event streams: event names, counts, a sample payload and the shapes of each |
| `golden/face/` | `face_vectors.py` | The face: each expression as PNG, the icon, and the scripted scenario of `host/scripts/face_golden.py` with the CRC-32 of every frame |
| `src/main/proto/marvin/voice/v1/voice.proto` | by hand | The gRPC contract between the Java core and the voice sidecar (design section 4) |

## How the Java host uses them

- **Protocol vectors**: the robot adapter decodes every `robot_to_host` datagram to the same
  values, encodes every `host_to_robot` input to the same bytes, and rejects the `invalid` ones.
  Python's `round()` rounds halves to even; the vectors pin it (Java: `Math.rint`).
- **Recordings**: replayed through the Java receiver and brain, each recording gives the same
  events at the same device times, the same states (floats within 1e-6), the same revolutions and
  the same receiver counters.
- **API snapshots**: the demo runs on a clock ten times faster than real time, so values change
  from run to run. The Java web adapter must answer each request with the same status, content
  type and **shape** (every value replaced by its JSON type; a list becomes the distinct shapes of
  its items), and with the same values where `index.json` does not list them as volatile (error
  messages, settings, the access rules). `get/voice_options.json` depends on the machine that
  recorded it.
- **Face**: pixels, compared through the CRC-32 of the RGB888 frames or the decoded PNGs.
- **voice.proto**: compiled by this module (Java classes in `marvin.host.contracts.voice.v1`),
  and by the Python sidecar with `grpcio-tools` in phase 2.
