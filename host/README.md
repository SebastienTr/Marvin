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

- `world/robot`: the body, head, lidar and the two radar boresights.
- `world/lidar/scan`: one lidar revolution at a time, in millimetres, in the device frame.
- `world/ld2450/targets`: people seen by the 24 GHz radar, with distance and speed.
- `presence/*` and `stats/*`: time series (targets, nearest distance, lidar rate, lost datagrams, CRC errors).

## Layout

| Module | Role |
|---|---|
| `protocol.py` | UDP envelope, message types, `HELLO` |
| `ldrobot.py` | LDROBOT 47-byte lidar packets, CRC-8 |
| `ld2450.py` | HLK-LD2450 30-byte target frames |
| `frames.py` | Sensor extrinsics, conversions to the device frame |
| `receiver.py` | UDP server, handshake, loss counting, lidar revolution assembly |
| `viewer.py` | Rerun logging |
| `sim.py` | Simulated room, walking person, and robot |
| `cli.py` | The `marvin-host` command |

Tests: `pytest` (includes a real LD19 packet and the LD2450 datasheet example, and a full simulator → UDP → receiver run).

## Roadmap

1. Camera stream and camera/lidar extrinsic calibration.
2. Presence events: someone arrived, sat down, has been still for an hour, left.
3. MR60BHA2 vital signs alongside the rest.
4. The companion layer: face expressions, voice, a local LLM.
