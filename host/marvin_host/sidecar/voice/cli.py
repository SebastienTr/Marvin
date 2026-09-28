"""Command line of the voice sidecar.

    python -m marvin_host.sidecar.voice [--host 127.0.0.1] [--port 47130] [--token SECRET]
    python -m marvin_host.sidecar.voice --fake --say "1:Marvin, quelle heure est-il ?"   # test mode

When it listens it prints one line on stdout, `READY port=<port>`, which the core's supervisor
waits for (with --port 0 the port is chosen by the system); logs go to stderr. SIGTERM or Ctrl+C
stops it gracefully (the session gets a STOPPED status, open streams end).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import importlib.util
import logging
import os
import signal
import threading

DEFAULT_PORT = 47130


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="python -m marvin_host.sidecar.voice",
                                description="Marvin's voice sidecar: the real-time audio loop behind gRPC "
                                            "(marvin.voice.v1), for Marvin's core.")
    p.add_argument("--host", default="127.0.0.1", help="address to listen on (default 127.0.0.1)")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"gRPC port (default {DEFAULT_PORT}; 0: any)")
    p.add_argument("--token", default=os.environ.get("MARVIN_SIDECAR_TOKEN", ""),
                   help="require 'authorization: Bearer TOKEN' on every call (default: $MARVIN_SIDECAR_TOKEN)")
    p.add_argument("--reply-timeout", type=float, default=30.0, metavar="S",
                   help="give up on an answer when the core sends nothing for this long (default 30)")
    t = p.add_argument_group("test mode (no microphone, speakers or models)")
    t.add_argument("--fake", action="store_true",
                   help="scripted computer microphone, fake Whisper and fake voice (see fake.py)")
    t.add_argument("--say", action="append", default=[], metavar="SECONDS:TEXT",
                   help='with --fake: a sentence the scripted microphone says, e.g. "2:Marvin, bonjour" '
                        "(repeat it; at most 15)")
    t.add_argument("--fake-speed", type=float, default=1.0, metavar="X",
                   help="with --fake: play the script X times faster than real time (default 1)")
    p.add_argument("-v", "--verbose", action="store_true", help="debug logs")
    return p


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    from ... import parent
    parent.watch()
    if importlib.util.find_spec("grpc") is None or importlib.util.find_spec("grpc_health") is None:
        print('the voice sidecar needs gRPC: in the host folder, pip install -e ".[sidecar,voice]"', flush=True)
        return 2
    from .fake import Script
    from .server import serve
    from .session import EngineFactory

    if args.say and not args.fake:
        parser().error("--say needs --fake")
    try:
        script = Script.parse(args.say) if args.fake else None
    except ValueError as e:
        parser().error(str(e))
    factory = EngineFactory(script, speed=args.fake_speed, reply_timeout_s=args.reply_timeout)
    server, port, service = serve(factory, args.host, args.port, args.token)
    log = logging.getLogger("marvin.sidecar.voice")
    log.info("voice sidecar listening on %s:%d%s", args.host, port, " (test mode)" if args.fake else "")
    print(f"READY port={port}", flush=True)

    stop = threading.Event()

    def on_signal(signum, frame):
        stop.set()

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)
    stop.wait()
    log.info("stopping")
    session = service.session
    if session is not None:
        session.close()
    server.stop(grace=2).wait(3)
    return 0
