"""marvin-host command line.

    marvin-host run        [--voice] [--no-ui] [--record F.mvrec]   listen for the robot: viewer, app, face link
                           [-v] [--stats]                            (quiet terminal: the app shows the rest)
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
from .ui.sink import UISink

MODELS = {"d500": 1, "d800": 2}


class ConsoleSink(Sink):
    """Prints a one-line summary every `period_s` seconds (rates per second), without Rerun."""

    def __init__(self, period_s: float = 1.0, logs: bool = True):
        self.period_s = period_s
        self.logs = logs                    # print the devices' LOG messages
        self.scans = self.frames = 0
        self.points = 0
        self.nearest = None
        self.vitals = None
        self.next = time.monotonic() + period_s

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
        if self.logs:
            print(f"[{dev.hello.device_name}] {text}")

    def _tick(self, dev):
        if time.monotonic() < self.next:
            return
        s = dev.stats
        near = f"{self.nearest / 1000:.2f} m" if self.nearest is not None else "-"
        v = self.vitals
        vit = f", breath {v.breath_rate:.0f}/min, heart {v.heart_rate:.0f}/min" if v and v.valid else ""
        k = self.period_s
        print(f"{dev.hello.device_name}: {self.scans / k:.0f} scans/s ({self.points} pts), {self.frames / k:.0f} radar/s, "
              f"nearest {near}{vit}, lost {s.lost}, crc {s.crc_errors}", flush=True)
        self.scans = self.frames = 0
        self.next = time.monotonic() + self.period_s


class Tee(Sink):
    """Hands every frame to several sinks, in order."""

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


def _print_event(e) -> None:
    print(f"  * {e.kind.value}" + (f": {e.detail}" if e.detail else ""), flush=True)


def _sink(args, brain: Brain, *extra: Sink) -> Sink:
    """brain -> extra -> console -> viewer: the brain goes first so its state is current for the others."""
    brain.add_listener(_print_event)
    console = ConsoleSink()
    if args.no_viewer and not args.save:
        return Tee(brain, *extra, console)
    from . import viewer
    return Tee(brain, *extra, console, viewer.start(save=args.save, spawn=not args.no_viewer, brain=brain))


class Terminal:
    """What `marvin-host run` prints. Quiet by default, the app shows the rest: the app's
    address, devices connecting and disconnecting, the voice turning on and off, and warnings.
    `verbose` (-v) or `console` (--no-ui) add the brain's events, the devices' logs and the
    conversation."""

    def __init__(self, verbose: bool = False, console: bool = False, out=print):
        self.chatty = verbose or console
        self.verbose = verbose
        self.out = out
        self._voice_state = "off"

    def device(self, kind: str, info: dict) -> None:
        """UISink listener."""
        if kind == "log":
            if self.chatty:
                self.out(f"[{info['device']}] {info['text']}")
            return
        name = info["name"]
        if kind == "connected":
            extra = [info["board"], f"firmware {info['firmware']}"]
            if info["simulated"]:
                extra.append("simulated sensors")
            self.out(f"+ {name} connected ({', '.join(x for x in extra if x)}) at {info['ip']}")
        elif kind == "reconnected":
            self.out(f"+ {name} is back")
        elif kind == "disconnected":
            self.out(f"- {name} disconnected (nothing received for {info['age_s']:.0f} s)")

    def event(self, e) -> None:
        """Brain listener (only added when chatty)."""
        _print_event(e)

    def voice(self, kind: str, p: dict) -> None:
        """VoiceController listener."""
        if kind == "voice":
            state = p["state"]
            if state == self._voice_state:
                return
            self._voice_state = state
            if state == "starting":
                self.out(f"voice: starting ({p['model']})...")
            elif state == "on":
                self.out("voice: on, say “Marvin, …”" if p.get("wake", True) else "voice: on, listening")
            elif state == "off":
                self.out("voice: off")
            elif state == "error":
                self.out(f"voice: {p['error']}" + (f". Fix: {p['fix']}" if p.get("fix") else ""))
        elif kind == "transcript" and self.chatty:
            k = p["kind"]
            if k == "heard":
                self.out(f"  you{' (typed)' if p.get('source') == 'typed' else ''}: {p['text']}")
            elif k == "reply":
                lat = f"  ({p['first_word_s']:.1f} s)" if p.get("first_word_s") is not None and self.verbose else ""
                self.out(f"  marvin: {p['text']}{lat}")
            elif k == "ignored" and self.verbose:
                self.out(f"  (ignored, {p['reason']}: {p['text'] or '…'})")


class _Formatter(logging.Formatter):
    """Messages as they are; warnings and errors say so."""

    def format(self, record: logging.LogRecord) -> str:
        msg = super().format(record)
        if record.levelno >= logging.ERROR:
            return f"error: {msg}"
        if record.levelno >= logging.WARNING:
            return f"warning: {msg}"
        return msg


def _setup_logging(args) -> None:
    """`run` and `ui` are quiet (warnings only) unless -v (info) or -vv (debug); the other
    commands log info, and debug with -v."""
    quiet = args.cmd in ("run", "ui")
    v = args.verbose
    if quiet:
        level = logging.DEBUG if v >= 2 else logging.INFO if v == 1 else logging.WARNING
    else:
        level = logging.DEBUG if v else logging.INFO
    handler = logging.StreamHandler()
    handler.setFormatter(_Formatter("%(message)s"))
    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)
    root.addHandler(handler)
    root.setLevel(level)
    if quiet and v < 2:
        logging.getLogger("marvin.receiver").setLevel(logging.WARNING)   # the terminal says it more briefly


def run_robot(args, cal) -> None:
    """`marvin-host run`: receiver, brain, face link, app, voice."""
    console = not args.ui
    term = Terminal(verbose=args.verbose > 0, console=console)
    brain = Brain(cal.brain_config())
    uisink = UISink()
    uisink.add_listener(term.device)
    sinks: list[Sink] = [brain, uisink]
    if args.verbose or args.stats or console:
        sinks.append(ConsoleSink(period_s=1.0 if (args.verbose or args.stats) else 5.0, logs=False))
    if args.save or not args.no_viewer:
        from . import viewer
        sinks.append(viewer.start(save=args.save, spawn=not args.no_viewer, brain=brain))
    if term.chatty:
        brain.add_listener(term.event)
    rx = record.make_receiver(args, Tee(*sinks), port=args.port)
    uisink.start()
    link = FaceLink(rx, brain).start()          # drives the robot's face (robots with a screen)
    speech = voice.make_controller(brain, args)  # turned on with --voice or from the app
    speech.add_listener(term.voice)
    app = ui.attach(brain, args, sink=uisink, voice=speech)      # None with --no-ui
    print(f"listening for the robot on UDP {args.port}", flush=True)
    if args.voice or (app is not None and app.settings.get("voice")):
        speech.start()
    try:
        rx.serve(duration=args.seconds)
    finally:
        link.stop()
        record.close_receiver(rx)
        uisink.stop()
        if app:
            app.stop()
        speech.close()


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(prog="marvin-host", description="Desktop side of the Marvin robot")
    ap.add_argument("--version", action="version", version=__version__)
    ap.add_argument("-v", "--verbose", action="count", default=0,
                    help="run: also print events, the conversation and a summary per second (-vv: debug)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    run = sub.add_parser("run", help="listen for the robot")
    run.add_argument("--port", type=int, default=protocol.HOST_PORT)
    run.add_argument("--save", help="record the viewer's output to a .rrd file instead of opening the viewer")
    run.add_argument("--no-viewer", action="store_true", help="no Rerun viewer")
    run.add_argument("--stats", action="store_true", help="print a sensor summary every second")
    run.add_argument("--seconds", type=float, help="stop after this many seconds")
    record.add_run_arguments(run)          # --record FILE.mvrec
    ui.add_run_arguments(run)              # --ui/--no-ui, --ui-port, --ui-host, --ui-token
    voice.add_run_arguments(run)           # --voice and its options (the app turns it on and off too)

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
    from . import parent
    parent.watch()                         # started by the Java host: exit with it
    _setup_logging(args)
    cal = calibration.load()               # ~/.config/marvin/calibration.json: lidar yaw, radar signs

    try:
        if args.cmd == "run":
            run_robot(args, cal)
        elif args.cmd == "sim":
            SimDevice(host=args.host, port=args.port, model=MODELS[args.model],
                      yaw_offset_deg=args.lidar_yaw).run(args.seconds)
        elif args.cmd == "demo":
            brain = Brain(cal.brain_config())
            uisink = UISink().start()
            rx = Receiver(_sink(args, brain, uisink), port=args.port, bind="127.0.0.1")
            app = ui.attach(brain, args, sink=uisink)
            stop = threading.Event()
            th = threading.Thread(target=rx.serve, kwargs={"stop": stop}, daemon=True)
            th.start()
            try:
                SimDevice(host="127.0.0.1", port=args.port, model=MODELS[args.model]).run(args.seconds)
            finally:
                stop.set()
                th.join()
                uisink.stop()
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
