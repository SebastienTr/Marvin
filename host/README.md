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
marvin-host run                    # wait for the real robot on the network, show it live
marvin-host run --no-viewer        # console summary only (scans/s, radar frames/s, losses)
marvin-host run --save day1.rrd    # record instead of showing; open later with `rerun day1.rrd`
marvin-host sim --model d800       # pretend to be the robot, for another machine running `run`
```

The robot finds the host on its own: see [docs/protocol.md](../docs/protocol.md). On macOS, allow incoming connections for Python the first time the firewall asks.

## What the viewer shows

| View | Content |
|---|---|
| 3D | The robot, the lidar scan and the radar targets in the device frame; for a simulated robot, the room as faint wireframes. |
| Camera | The camera image, with the lidar points and radar targets projected onto it. Simulated robots get a rendered image of the simulated room, at the robot's clock. |
| Lidar, top view | One full revolution seen from above, the robot's front up, coloured by distance, with range rings every metre and the LD2450 target. |
| mmWave radars | Radar-style view: the LD2450 sector (±60°, 6 m) with targets, speed and a 4-second trail; the MR60BHA2 vital-signs range and the person it measures. |
| Time series | Presence (targets, distance, speed), vital signs (breath and heart rates, breathing and heartbeat waves), link statistics, log. |

## The simulated room

[`scene.py`](marvin_host/scene.py) describes a small home office (desk, workbench, sofa, wardrobe, plants) and a person who walks in, moves around, sits at the desk for half a minute, then leaves (70 s loop). The lidar only sees what crosses its plane, 133 mm above the desk, as the real one would. The same scene drives the host simulator, the simulated camera and the firmware simulator: after editing it, regenerate the firmware copy with `python -m marvin_host.scene > ../firmware/src/scene_data.h` (a test checks it).

## Layout

| Module | Role |
|---|---|
| `protocol.py` | UDP envelope, message types, `HELLO` |
| `ldrobot.py` | LDROBOT 47-byte lidar packets, CRC-8 |
| `ld2450.py` | HLK-LD2450 30-byte target frames |
| `frames.py` | Sensor extrinsics, conversions to the device frame |
| `receiver.py` | UDP server, handshake, loss counting, lidar revolution assembly |
| `viewer.py` | Rerun logging |
| `scene.py` | The simulated room and person (single source of truth) |
| `sim.py` | Simulated robot: lidar, LD2450 and MR60BHA2 frames from the scene |
| `camera.py` | Camera model and the simulated camera (numpy ray casting) |
| `cli.py` | The `marvin-host` command |

Tests: `pytest` (includes a real LD19 packet and the LD2450 datasheet example, and a full simulator → UDP → receiver run).

## Roadmap

1. Camera stream and camera/lidar extrinsic calibration.
2. Presence events: someone arrived, sat down, has been still for an hour, left.
3. MR60BHA2 vital signs alongside the rest.
4. The companion layer: face expressions, voice, a local LLM.
