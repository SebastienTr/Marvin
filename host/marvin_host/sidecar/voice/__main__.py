"""python -m marvin_host.sidecar.voice: the voice sidecar (see __init__.py).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import sys

from .cli import main

if __name__ == "__main__":
    sys.exit(main())
