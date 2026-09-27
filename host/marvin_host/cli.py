"""marvin-host command line.

    marvin-host run        [--voice] [--no-ui] [--record F.mvrec]   listen for the robot: viewer, app, face link
    marvin-host demo       [--save FILE.rrd] [--seconds N]           simulator + receiver + viewer in one process
    marvin-host sim        [--host IP] [--model d500|d800]           pretend to be the robot
    marvin-host ui --demo  [--speed 10]                              the app in the browser, with a simulated robot
    marvin-host talk       [--wav FILE] [--no-wake]                  talk to Marvin with the computer's mic and speakers
    marvin-host replay     FILE.mvrec [--speed 2]                    play a recorded session back
    marvin-host calibrate  lidar [--manual] [--save]                 calibrate the lidar's yaw

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import logging
import threading
import time

from . import __version__, calibration, protocol, record, ui, voice
from .brain import Brain
from .link import FaceLink
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

    def on_audio(self, *a):
        for s in self.sinks:
            s.on_audio(*a)

    def on_log(self, *a):
        for s in self.sinks:
            s.on_log(*a)


def _sink(args, brain: Brain) -> Sink:
    """brain -> console -> viewer: the brain goes first so its state is current for the others."""
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
    run.add_argument("--save", help="record the viewer's output to a .rrd file instead of opening the viewer")
    run.add_argument("--no-viewer", action="store_true", help="console summary only")
    record.add_run_arguments(run)          # --record FILE.mvrec
    ui.add_run_arguments(run)              # --ui/--no-ui, --ui-port, --ui-host, --ui-token
    voice.add_run_arguments(run)           # --voice and its options

    sim = sub.add_parser("sim", help="simulated robot")
    sim.add_argument("--host", default="255.255.255.255", help="host address (default: broadcast)")
    sim.add_argument("--port", type=int, default=protocol.HOST_PORT)
    sim.add_argument("--model", choices=MODELS, default="d500")
    sim.add_argument("--seconds", type=float)
    sim.add_argument("--lidar-yaw", type=float, help="simulate a lidar mounted with this yaw (degrees), to try `calibrate`")

    demo = sub.add_parser("demo", help="simulator and viewer together")
    demo.add_argument("--model", choices=MODELS, default="d500")
    demo.add_argument("--port", type=int, default=protocol.HOST_PORT)
    demo.add_argument("--save", help="record to a .rrd file instead of opening the viewer")
    demo.add_argument("--no-viewer", action="store_true")
    demo.add_argument("--seconds", type=float)
    ui.add_run_arguments(demo)

    ui.add_cli(sub)                        # marvin-host ui [--demo]
    talk = voice.add_cli(sub)              # marvin-host talk
    record.add_cli(sub)                    # marvin-host replay
    calibration.add_cli(sub)               # marvin-host calibrate

    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(message)s")
    cal = calibration.load()               # ~/.config/marvin/calibration.json: lidar yaw, radar signs

    try:
        if args.cmd == "run":
            print(f"listening on UDP {args.port}, waiting for the robot's HELLO...")
            brain = Brain(cal.brain_config())
            rx = record.make_receiver(args, _sink(args, brain), port=args.port)
            link = FaceLink(rx, brain).start()      # drives the robot's face (robots with a screen)
            app = ui.attach(brain, args)            # the app in the browser, None with --no-ui
            assistant = voice.attach(brain, args)   # None without --voice
            try:
                rx.serve()
            finally:
                link.stop()
                record.close_receiver(rx)
                if app:
                    app.stop()
                if assistant:
                    assistant.close()
        elif args.cmd == "sim":
            SimDevice(host=args.host, port=args.port, model=MODELS[args.model],
                      yaw_offset_deg=args.lidar_yaw).run(args.seconds)
        elif args.cmd == "demo":
            brain = Brain(cal.brain_config())
            rx = Receiver(_sink(args, brain), port=args.port, bind="127.0.0.1")
            app = ui.attach(brain, args)
            stop = threading.Event()
            th = threading.Thread(target=rx.serve, kwargs={"stop": stop}, daemon=True)
            th.start()
            try:
                SimDevice(host="127.0.0.1", port=args.port, model=MODELS[args.model]).run(args.seconds)
            finally:
                stop.set()
                th.join()
                if app:
                    app.stop()
        elif args.cmd == "ui":
            args.ui_main(args)
        elif args.cmd == "talk":
            talk(args)
        elif args.cmd == "replay":
            record.run_replay(args, lambda: _sink(args, Brain(cal.brain_config())))
        elif args.cmd == "calibrate":
            calibration.run_cli(args)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
