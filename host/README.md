# Host software

The desktop side of Marvin: the robot's big brain. Today it receives the robot's sensor stream, places every point in the robot's frame and shows it live in [Rerun](https://rerun.io). A simulator produces the same stream, so all of this runs before any hardware arrives.

## Install

Python 3.10 or newer.

```bash
cd host
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
```

## Use

```bash
marvin-host demo                   # simulated robot + live 3D view, nothing else needed
marvin-host ui --demo              # the app in the browser, with a simulated robot and week
marvin-host run                    # wait for the real robot: viewer, app (http://localhost:8765) and face link
marvin-host run --voice            # ... and talk to Marvin ("Marvin, ...") with the computer's mic
marvin-host -v run                 # the same, printing events, the conversation and a summary per second
marvin-host run --record day1.mvrec   # keep the raw sensor data; `marvin-host replay day1.mvrec` plays it back
marvin-host talk                   # voice only, no robot needed
marvin-host calibrate lidar --save # find the lidar's yaw (person moving in front of the robot)
marvin-host sim --model d800       # pretend to be the robot, for another machine running `run`
```

Voice needs the `voice` extra and Ollama: see [docs/voice.md](../docs/voice.md). The app: [docs/ui.md](../docs/ui.md). Recording, replay, calibration and wireless firmware updates: [docs/tools.md](../docs/tools.md).

The robot finds the host on its own: see [docs/protocol.md](../docs/protocol.md). On macOS, allow incoming connections for Python the first time the firewall asks.

## What the viewer shows

| View | Content |
|---|---|
| 3D | The robot, the lidar scan and the radar targets in the device frame; for a simulated robot, the room as faint wireframes. |
| Face | The robot's eyes, exactly as the 240 × 280 screen will show them, driven by the brain. |
| Camera | The camera image, with the lidar points and radar targets projected onto it. Simulated robots get a rendered image of the simulated room, at the robot's clock. |
| Lidar, top view | One full revolution seen from above, the robot's front up, coloured by distance, with range rings every metre and the LD2450 target. |
| mmWave radars | Radar-style view: the LD2450 sector (±60°, 6 m) with targets, speed and a 4-second trail; the MR60BHA2 vital-signs range and the person it measures. |
| Time series | Presence (targets, distance, speed, seated), vital signs (breath and heart rates, breathing and heartbeat waves), events, link statistics, log. |

## The brain and the face

[`brain.py`](marvin_host/brain.py) follows the nearest person seen by the LD2450 and turns the frames into events ([`events.py`](marvin_host/events.py)): `arrived`, `approached`, `sat_down`, `stood_up`, `still_long` (seated for 50 minutes: time for a break), `vitals_acquired`, `vitals_lost`, `left`. Every decision is debounced, and time comes only from the robot's clock, so a recording replays to the same events. `marvin-host run` prints them and shows them in the viewer.

[`face.py`](marvin_host/face.py) is the reference implementation of the robot's eyes: it follows your head, blinks, reacts to the events and falls asleep when you leave. It only draws rounded rectangles, ellipses and triangles, so it ports 1:1 to the ESP32-S3; see [docs/face.md](../docs/face.md).

<p align="center"><img src="../docs/images/face_expressions.png" alt="The face's expressions" width="480"></p>

## The simulated room

[`scene.py`](marvin_host/scene.py) describes a small home office (desk, workbench, sofa, wardrobe, plants) and a person who walks in, moves around, sits at the desk for half a minute, then leaves (70 s loop). The lidar only sees what crosses its plane, 133 mm above the desk, as the real one would. The same scene drives the host simulator, the simulated camera and the firmware simulator: after editing it, regenerate the firmware copy with `python -m marvin_host.scene > ../firmware/src/scene_data.h` (a test checks it).

## Layout

| Module | Role |
|---|---|
| `protocol.py` | UDP envelope, message types, `HELLO` |
| `ldrobot.py` | LDROBOT 47-byte lidar packets, CRC-8 |
| `ld2450.py` | HLK-LD2450 30-byte target frames |
| `frames.py` | Sensor extrinsics, conversions to the device frame |
| `receiver.py` | UDP server, handshake, loss counting, lidar revolution assembly; sink calls run on their own thread, so a slow viewer or model never delays the robot's `HOST_ACK` (stale lidar scans are dropped first, see `Device.stats.shed` and `Receiver.timings()`) |
| `viewer.py` | Rerun logging |
| `scene.py` | The simulated room and person (single source of truth) |
| `sim.py` | Simulated robot: lidar, LD2450 and MR60BHA2 frames from the scene |
| `camera.py` | Camera model and the simulated camera (numpy ray casting) |
| `events.py` | Events and presence state: the contract between the brain and what reacts to it |
| `brain.py` | Presence, seating, stillness and vital-sign reliability, as debounced events |
| `face.py`, `raster.py` | The robot's eyes (reference renderer and behaviour) and the tiny rasterizer they use |
| `link.py` | Host → robot face link: brain events (`FACE_EVENT`) and presence state (`FACE_STATE`, 10 Hz) to every robot with a screen |
| `audio.py` | Audio contract (sources and sinks, 16 kHz mono) shared by the voice and the robot's audio |
| `voice/` | Wake word, speech-to-text, local LLM and text-to-speech: talking to Marvin |
| `robot_audio.py`, `camera_stream.py` | The robot's microphone and speaker over UDP, its MJPEG camera |
| `ui/` | The app: a local web page with the face, today's timeline, breaks, history, the conversation and voice controls, the robot's devices and sensors, the log, settings |
| `record.py`, `calibration.py` | Recording and replaying raw sessions, sensor calibration |
| `cli.py` | The `marvin-host` command |

Tests: `pytest` (includes a real LD19 packet and the LD2450 datasheet example, and a full simulator → UDP → receiver run).

## Roadmap

1. Everything checked on the real hardware: sensors, screen, audio, camera, MR60BHA2 bridge.
2. The voice through the robot's microphone and speaker instead of the computer's.
3. Camera/lidar extrinsic calibration, and using the camera in the brain.
4. A dedicated "Marvin" wake-word model (Google Speech Commands).
