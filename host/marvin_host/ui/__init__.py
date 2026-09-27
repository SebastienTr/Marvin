"""Marvin's app: a small local web app showing the owner what the robot sees, today and this week.

    from marvin_host import ui
    server = ui.UIServer(brain).start()      # http://localhost:8765/, and the LAN address for a phone
    ...
    server.stop()

See docs/ui.md.

SPDX-License-Identifier: MIT
"""
from .cli import add_cli, add_run_arguments, attach, main
from .server import UIServer
from .sink import UISink
from .store import EventStore, data_dir

__all__ = ["UIServer", "UISink", "EventStore", "data_dir", "add_cli", "add_run_arguments", "attach", "main"]
