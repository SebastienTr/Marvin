"""Exit when the process that started us dies.

The Java host starts its Python sidecars with a pipe on their standard input and sets
MARVIN_EXIT_WITH_PARENT=stdin. The operating system closes that pipe when the host exits for any
reason (including kill -9 or a crash), so reading it to the end tells the sidecar its host is gone,
and it exits instead of living on as an orphan (keeping models in memory, the audio device open,
or sending UDP to the robot port).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import os
import sys
import threading

log = logging.getLogger(__name__)
ENV = "MARVIN_EXIT_WITH_PARENT"


def watch(stream=None, exit=os._exit) -> threading.Thread | None:
    """With MARVIN_EXIT_WITH_PARENT=stdin, a daemon thread that exits the process at end of input."""
    if os.environ.get(ENV) != "stdin":
        return None
    stream = stream if stream is not None else sys.stdin.buffer

    def run() -> None:
        try:
            while stream.read(4096):
                pass                        # the host never writes; anything it did is ignored
        except (OSError, ValueError):
            pass
        log.warning("the host that started this process is gone: exiting")
        exit(0)

    t = threading.Thread(target=run, name="parent-watch", daemon=True)
    t.start()
    return t
