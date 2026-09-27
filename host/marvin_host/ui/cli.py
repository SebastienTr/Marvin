"""Command-line glue for the app: options for ``marvin-host run`` and the ``marvin-host ui`` command.

    marvin-host run [--no-ui] [--ui-port 8765] [--ui-host 0.0.0.0] [--ui-token auto|off|KEY]
    marvin-host ui                   the app and the brain, listening for the robot (no Rerun)
    marvin-host ui --demo [--speed 10] [--open]
                                     the app with a simulated robot and a simulated past week

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import shutil
import tempfile
import threading
import webbrowser
from pathlib import Path

from .server import UIServer
from .store import EventStore

DEFAULT_PORT = 8765


def _add_server_arguments(p: argparse.ArgumentParser) -> None:
    p.add_argument("--ui-port", type=int, default=DEFAULT_PORT, help=f"app port (default {DEFAULT_PORT})")
    p.add_argument("--ui-host", default="0.0.0.0",
                   help="address to listen on (default 0.0.0.0: this computer and the local network; "
                        "127.0.0.1: this computer only)")
    p.add_argument("--ui-token", default="auto", metavar="auto|off|KEY",
                   help="access key for other devices: auto (default, a random key kept next to the "
                        "database), off (none: anyone on the network can open the app), or your own")


def add_run_arguments(run_parser: argparse.ArgumentParser) -> None:
    """Adds --ui/--no-ui, --ui-port, --ui-host and --ui-token to a command."""
    run_parser.add_argument("--ui", action=argparse.BooleanOptionalAction, default=True,
                            help="serve Marvin's app in the browser (default: on)")
    _add_server_arguments(run_parser)


def _token(value: str | None) -> str | None:
    return None if value in (None, "", "off", "none") else value


def attach(brain, args) -> UIServer | None:
    """Starts the app for ``brain`` as the options ask; None with --no-ui or if the port is taken."""
    if not getattr(args, "ui", True):
        return None
    try:
        return UIServer(brain, host=args.ui_host, port=args.ui_port, token=_token(args.ui_token)).start()
    except OSError as e:
        print(f"Marvin's app not started (port {args.ui_port}: {e.strerror or e}); try --ui-port")
        return None


def add_cli(subparsers) -> argparse.ArgumentParser:
    """Adds the ``ui`` command. Dispatch with ``args.ui_main(args)``."""
    p = subparsers.add_parser("ui", help="Marvin's app in the browser (--demo: with a simulated robot)")
    p.add_argument("--demo", action="store_true", help="simulated robot and a simulated past week")
    p.add_argument("--speed", type=float, default=10.0, help="demo only: time runs this many times faster (default 10)")
    p.add_argument("--seconds", type=float, help="stop after this many real seconds")
    p.add_argument("--open", action="store_true", help="open the app in the browser")
    p.add_argument("--port", type=int, default=None, help="UDP port to listen for the robot (not with --demo)")
    _add_server_arguments(p)
    p.set_defaults(ui_main=main)
    return p


def main(args) -> None:
    stop = threading.Event()
    cleanup = []
    try:
        if args.demo:
            from ..brain import Brain
            from .demo import DemoRobot, seed_history
            tmp = Path(tempfile.mkdtemp(prefix="marvin-demo-"))
            cleanup.append(lambda: shutil.rmtree(tmp, ignore_errors=True))
            brain = Brain()
            robot = DemoRobot(brain, speed=args.speed)
            store = EventStore(tmp / "marvin.db")
            cleanup.insert(0, store.close)
            seed_history(store, robot.clock())
            server = UIServer(brain, host=args.ui_host, port=args.ui_port, store=store,
                              token=_token(args.ui_token), clock=robot.clock)
            print(f"Demo: a simulated robot, time {args.speed:g}x faster, a simulated past week. "
                  "Nothing is kept.")
            server.start()
            cleanup.insert(0, server.stop)
            robot.start()
            cleanup.insert(0, robot.stop)
            if args.open:
                webbrowser.open(server.urls()["local"])
            stop.wait(args.seconds)
        else:
            from .. import protocol
            from ..brain import Brain
            from ..link import FaceLink
            from ..receiver import Receiver
            brain = Brain()
            server = UIServer(brain, host=args.ui_host, port=args.ui_port, token=_token(args.ui_token)).start()
            cleanup.insert(0, server.stop)
            port = args.port or protocol.HOST_PORT
            rx = Receiver(brain, port=port)
            link = FaceLink(rx, brain).start()
            cleanup.insert(0, link.stop)
            print(f"listening for the robot on UDP {port}...")
            if args.open:
                webbrowser.open(server.urls()["local"])
            rx.serve(duration=args.seconds)
    except KeyboardInterrupt:
        pass
    finally:
        for fn in cleanup:
            try:
                fn()
            except Exception:
                pass
