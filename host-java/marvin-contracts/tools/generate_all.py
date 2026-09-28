# SPDX-License-Identifier: MIT
"""Regenerate every golden file from the Python host: protocol vectors, recordings, face vectors,
and (unless --no-api) the app API snapshots.

    python3 host-java/marvin-contracts/tools/generate_all.py [--no-api]

Everything but the API snapshots is deterministic: CI regenerates those and fails on any diff.
Regenerate only on purpose, and review the diff (docs/design.md 3.5).
"""
from __future__ import annotations

import argparse
import subprocess
import sys

from _common import TOOLS

DETERMINISTIC = ["protocol_vectors.py", "recordings.py", "face_vectors.py"]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-api", action="store_true", help="skip the API snapshots (they take about 30 s)")
    args = ap.parse_args()
    scripts = DETERMINISTIC + ([] if args.no_api else ["api_snapshots.py"])
    for s in scripts:
        subprocess.run([sys.executable, str(TOOLS / s)], check=True, cwd=TOOLS)


if __name__ == "__main__":
    main()
