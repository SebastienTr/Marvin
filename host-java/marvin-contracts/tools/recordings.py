# SPDX-License-Identifier: MIT
"""Golden recordings (.mvrec v1) of the simulated robot, and what the Python host makes of them.

For each scenario, in golden/recordings/:

    <name>.mvrec.gz          the datagrams, as `marvin-host run --record` writes them (gzip)
    <name>.events.jsonl      the brain's events, in order: device time, kind, detail, data
    <name>.states.jsonl      the brain's presence state at the end of every device tick
                             (after the LD2450 and VITALS frames sharing one device time)
    <name>.scans.jsonl       every lidar revolution the receiver assembled: device time, points, speed
    index.json               the scenarios, the receiver's counters per device, the lidar yaw estimate

The Java robot adapter replays the same file through its receiver and brain and must produce the
same events at the same device times, and the same states (floats within 1e-6).

    python3 recordings.py
"""
from __future__ import annotations

import dataclasses
import gzip
from pathlib import Path
from unittest import mock

from _common import GOLDEN, rel, write_json, write_jsonl

from marvin_host import calibration, frames, ld2450, protocol, record, sim  # noqa: E402
from marvin_host.brain import Brain  # noqa: E402
from marvin_host.receiver import Sink  # noqa: E402

OUT = GOLDEN / "recordings"
HOST_START = 1_790_000_000.0          # a fixed "recorded at" time, so the files do not change on each run


class Capture(Sink):
    """Brain + recorders: events, states at the end of each tick, lidar revolutions."""

    def __init__(self):
        self.brain = Brain()
        self.events: list[dict] = []
        self.states: list[dict] = []
        self.scans: list[dict] = []
        self.hellos: list[dict] = []
        self.logs: list[dict] = []
        self._tick: int | None = None
        self.brain.add_listener(lambda e: self.events.append(
            {"t_us": e.t_us, "kind": e.kind.value, "detail": e.detail, "data": e.data}))

    def _flush(self, t_us: int | None) -> None:
        if self._tick is not None and t_us != self._tick:
            s = dataclasses.asdict(self.brain.state)
            s["tick_t_us"] = self._tick
            self.states.append(s)
        self._tick = t_us

    def on_hello(self, dev) -> None:
        self.hellos.append(record.device_info(dev))

    def on_targets(self, dev, t_us, targets, points) -> None:
        self._flush(t_us)
        self.brain.on_targets(dev, t_us, targets, points)

    def on_vitals(self, dev, t_us, vitals) -> None:
        self._flush(t_us)
        self.brain.on_vitals(dev, t_us, vitals)

    def on_scan(self, dev, t_us, points, intensities, speed_dps) -> None:
        self.scans.append({"t_us": t_us, "points": int(len(points)), "speed_dps": int(speed_dps),
                           "intensity_sum": int(intensities.astype(int).sum())})

    def on_log(self, dev, t_us, text) -> None:
        self.logs.append({"t_us": t_us, "text": text})

    def finish(self) -> None:
        self._flush(None)


class fixed_clock:
    """RecordingWriter reads the wall clock (the header) and the monotonic clock (record times are
    relative to it, in floating point): pin both, so reruns write the same bytes."""

    def __enter__(self):
        self._p = [mock.patch.object(record.time, "time", return_value=HOST_START),
                   mock.patch.object(record.time, "monotonic", return_value=1000.0)]
        for p in self._p:
            p.start()
        return self

    def __exit__(self, *exc):
        for p in reversed(self._p):
            p.stop()


def write_datagrams(path: Path, datagrams, meta: dict) -> Path:
    """(host time s since start, datagram) pairs -> a .mvrec(.gz), all from the simulated robot's address."""
    with fixed_clock(), record.RecordingWriter(path, meta=meta) as w:
        t0 = w._t0
        for t, data in datagrams:
            w.write_datagram(data, record.SIM_ADDR, t0 + t)
    return path


def simulated(seconds: float, start: float = 0.0, **opts):
    dev = sim.SimDevice(host="127.0.0.1", **opts)
    dev.sock.close()
    return list(record.simulated_datagrams(seconds, dev, start))


# ------------------------------------------------------------------------------------------ scenarios

def room_loop(path: Path) -> dict:
    """One loop of the simulated room: walks in, sits (vital signs acquired, lost at each fidget,
    acquired again), stands up, leaves."""
    d = simulated(72.0)
    write_datagrams(path, [(t, x) for t, x in d], {"simulated": True, "scenario": "room_loop",
                                                   "sim_options": {"model": 1}})
    return {"description": room_loop.__doc__.strip()}


def lidar_yaw(path: Path) -> dict:
    """The lidar mounted 37 degrees off: someone walks in (26 s, before sitting). The yaw
    estimator (calibration.LidarYawEstimator, starting from yaw 0) must find about 37 degrees."""
    d = simulated(26.0, yaw_offset_deg=37.0)
    write_datagrams(path, d, {"simulated": True, "scenario": "lidar_yaw", "sim_options": {"yaw_offset_deg": 37.0}})
    return {"description": " ".join(lidar_yaw.__doc__.split())}


def robot_reboot(path: Path) -> dict:
    """Seated with vital signs (scene 26 to 42 s), then the robot reboots: its clock starts again
    from 0 while the host's goes on. The brain loses the vital signs, says the person left
    ("sensor restarted") and starts over."""
    first = simulated(16.0, start=26.0)
    second = simulated(8.0, start=0.0)
    shift = first[-1][0] - 26.0 + 0.5
    write_datagrams(path, [(t - 26.0, x) for t, x in first] + [(t + shift, x) for t, x in second],
                    {"simulated": True, "scenario": "robot_reboot"})
    return {"description": " ".join(robot_reboot.__doc__.split())}


def damaged_link(path: Path) -> dict:
    """A lossy, noisy link (scene 20 to 26 s): frames before the first HELLO (ignored), one datagram
    in 40 lost (sequence gaps), one lidar packet in 25 with a bad CRC, one LD2450 frame in 20 with a
    bad tail, and a few datagrams that are not Marvin's (bad magic, protocol v2, too short)."""
    d = simulated(6.0, start=20.0)
    first_hello = next(i for i, (_, x) in enumerate(d) if x[3] == protocol.HELLO)
    out = []
    # three datagrams sent before the robot said hello
    pre = [x for _, x in d[first_hello + 1:first_hello + 20] if x[3] == protocol.LD2450][:3]
    for k, x in enumerate(pre):
        out.append((0.001 * k, x))
    lidar_n = radar_n = 0
    for i, (t, x) in enumerate(d):
        if i % 40 == 39:
            continue                                  # lost on the way
        typ = x[3]
        if typ == protocol.LIDAR:
            lidar_n += 1
            if lidar_n % 25 == 0:                     # flip one bit in the 3rd packet of this datagram
                b = bytearray(x)
                b[16 + 1 + 2 * 47 + 10] ^= 0x01
                x = bytes(b)
        elif typ == protocol.LD2450:
            radar_n += 1
            if radar_n % 20 == 0:
                x = x[:-1] + b"\x00"
        out.append((t - 20.0 + 0.01, x))
    junk = [b"XX" + bytes(14), b"MV\x02\x01" + bytes(12), b"MV\x01"]
    for k, j in enumerate(junk):
        out.append((1.0 + k, j))
    out.sort(key=lambda p: p[0])
    write_datagrams(path, out, {"simulated": True, "scenario": "damaged_link"})
    return {"description": " ".join(damaged_link.__doc__.split())}


SCENARIOS = [room_loop, lidar_yaw, robot_reboot, damaged_link]


def replay(path: Path) -> tuple[Capture, dict]:
    cap = Capture()
    rx = record.replay(path, cap, speed=None)
    cap.finish()
    devices = []
    for d in rx.devices.values():
        s = d.stats
        devices.append({"address": f"{d.addr[0]}:{d.addr[1]}", "device_name": d.hello.device_name,
                        "stats": {k: getattr(s, k) for k in ("datagrams", "lidar_packets", "radar_frames", "lost",
                                                             "crc_errors", "bad")}})
    return cap, {"devices": devices}


def yaw_estimate(path: Path) -> dict | None:
    saved = frames.LIDAR.yaw_deg
    frames.LIDAR.yaw_deg = 0.0
    try:
        est = calibration.LidarYawEstimator()
        record.replay(path, est, speed=None)
        e = est.estimate()
    finally:
        frames.LIDAR.yaw_deg = saved
    return None if e is None else {"angle_deg": e.angle_deg, "spread_deg": e.spread_deg, "inliers": e.inliers,
                                   "samples": e.samples}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    index = {"about": "Golden recordings from the simulated robot and the Python host's view of them "
                      "(host-java/marvin-contracts/tools/recordings.py). Device times in microseconds.",
             "format": "mvrec v1, gzip (host/marvin_host/record.py)",
             "brain_config": dataclasses.asdict(Brain().config),
             "scenarios": []}
    for fn in SCENARIOS:
        name = fn.__name__
        path = OUT / f"{name}.mvrec.gz"
        info = fn(path)
        _normalise_gzip(path)
        cap, rx = replay(path)
        write_jsonl(OUT / f"{name}.events.jsonl", cap.events)
        write_jsonl(OUT / f"{name}.states.jsonl", cap.states)
        write_jsonl(OUT / f"{name}.scans.jsonl", cap.scans)
        s = record.summarize(path)
        entry = {"name": name, **info, "file": path.name, "duration_s": s["duration_s"], "datagrams": s["datagrams"],
                 "events": [e["kind"] for e in cap.events], "states": len(cap.states), "scans": len(cap.scans),
                 "hellos": cap.hellos, "receiver": rx, "meta": s["meta"]}
        if name == "lidar_yaw":
            entry["lidar_yaw_estimate"] = yaw_estimate(path)
        index["scenarios"].append(entry)
        print(f"wrote {rel(path)} ({path.stat().st_size // 1024} kB): {len(cap.events)} events, "
              f"{len(cap.states)} states, {len(cap.scans)} scans")
    write_json(OUT / "index.json", index)


def _normalise_gzip(path: Path) -> None:
    """gzip headers carry a time stamp and a file name: rewrite with neither, so reruns give the same bytes."""
    data = gzip.decompress(path.read_bytes())
    with open(path, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as f:
        f.write(data)


if __name__ == "__main__":
    main()
