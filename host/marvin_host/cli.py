"""marvin-host command line.

    marvin-host run   [--save FILE.rrd] [--no-viewer]   listen for the robot, show it in Rerun
    marvin-host sim   [--host IP] [--model d500|d800]   pretend to be the robot
    marvin-host demo  [--save FILE.rrd] [--seconds N]   simulator + receiver + viewer in one process

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import logging
import threading
import time

from . import __version__, protocol
from .brain import Brain
from .receiver import Receiver, Sink
from .sim import SimDevice

MODELS = {"d500": 1, "d800": 2}


class ConsoleSink(Sink):
    """Prints a one-line summary per second, without Rerun."""

    def __init__(self):
        self.scans = self.frames = 0
        self.points = 0
        self.nearest = None
        self.vitals = None
        self.next = time.monotonic() + 1

    def on_scan(self, dev, t_us, points, intensities, speed_dps):
        self.scans += 1
        self.points = len(points)
        self._tick(dev)

    def on_targets(self, dev, t_us, targets, points):
        self.frames += 1
        self.nearest = min((t.y_mm for t in targets), default=None)
        self._tick(dev)

    def on_vitals(self, dev, t_us, vitals):
        self.vitals = vitals

    def on_log(self, dev, t_us, text):
        print(f"[{dev.hello.device_name}] {text}")

    def _tick(self, dev):
        if time.monotonic() < self.next:
            return
        s = dev.stats
        near = f"{self.nearest / 1000:.2f} m" if self.nearest is not None else "-"
        v = self.vitals
        vit = f", breath {v.breath_rate:.0f}/min, heart {v.heart_rate:.0f}/min" if v and v.valid else ""
        print(f"{dev.hello.device_name}: {self.scans} scans/s ({self.points} pts), {self.frames} radar/s, nearest {near}{vit}, "
              f"lost {s.lost}, crc {s.crc_errors}")
        self.scans = self.frames = 0
        self.next = time.monotonic() + 1


class _Tee(Sink):
    def __init__(self, *sinks: Sink):
        self.sinks = sinks

    def on_hello(self, *a):
        for s in self.sinks:
            s.on_hello(*a)

    def on_scan(self, *a):
        for s in self.sinks:
            s.on_scan(*a)

    def on_targets(self, *a):
        for s in self.sinks:
            s.on_targets(*a)

    def on_vitals(self, *a):
        for s in self.sinks:
            s.on_vitals(*a)

    def on_log(self, *a):
        for s in self.sinks:
            s.on_log(*a)


def _sink(args) -> Sink:
    """brain -> console -> viewer: the brain goes first so its state is current for the others."""
    brain = Brain()
    brain.add_listener(lambda e: print(f"  * {e.kind.value}" + (f": {e.detail}" if e.detail else "")))
    console = ConsoleSink()
    if args.no_viewer and not args.save:
        return _Tee(brain, console)
    from . import viewer
    return _Tee(brain, console, viewer.start(save=args.save, spawn=not args.no_viewer, brain=brain))


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(prog="marvin-host", description="Desktop side of the Marvin robot")
    ap.add_argument("--version", action="version", version=__version__)
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)

    run = sub.add_parser("run", help="listen for the robot")
    run.add_argument("--port", type=int, default=protocol.HOST_PORT)
    run.add_argument("--save", help="record to a .rrd file instead of opening the viewer")
    run.add_argument("--no-viewer", action="store_true", help="console summary only")

    sim = sub.add_parser("sim", help="simulated robot")
    sim.add_argument("--host", default="255.255.255.255", help="host address (default: broadcast)")
    sim.add_argument("--port", type=int, default=protocol.HOST_PORT)
    sim.add_argument("--model", choices=MODELS, default="d500")
    sim.add_argument("--seconds", type=float)

    demo = sub.add_parser("demo", help="simulator and viewer together")
    demo.add_argument("--model", choices=MODELS, default="d500")
    demo.add_argument("--port", type=int, default=protocol.HOST_PORT)
    demo.add_argument("--save", help="record to a .rrd file instead of opening the viewer")
    demo.add_argument("--no-viewer", action="store_true")
    demo.add_argument("--seconds", type=float)

    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(message)s")

    try:
        if args.cmd == "run":
            print(f"listening on UDP {args.port}, waiting for the robot's HELLO...")
            Receiver(_sink(args), port=args.port).serve()
        elif args.cmd == "sim":
            SimDevice(host=args.host, port=args.port, model=MODELS[args.model]).run(args.seconds)
        elif args.cmd == "demo":
            rx = Receiver(_sink(args), port=args.port, bind="127.0.0.1")
            stop = threading.Event()
            th = threading.Thread(target=rx.serve, kwargs={"stop": stop}, daemon=True)
            th.start()
            try:
                SimDevice(host="127.0.0.1", port=args.port, model=MODELS[args.model]).run(args.seconds)
            finally:
                stop.set()
                th.join()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
