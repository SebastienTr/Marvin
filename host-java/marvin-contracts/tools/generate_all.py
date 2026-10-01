# SPDX-License-Identifier: MIT
"""Regenerate the golden files made from the Python host: protocol vectors, recordings and face
vectors. All deterministic: CI regenerates them and fails on any diff. Regenerate only on purpose,
and review the diff (docs/design.md 3.5).

    python3 host-java/marvin-contracts/tools/generate_all.py

golden/api and golden/conversation are no longer generated: the Python host's conversation was
retired, and they are the Java host's checked-in specifications now (README.md).
"""
from __future__ import annotations

import argparse
import subprocess
import sys

from _common import TOOLS

DETERMINISTIC = ["protocol_vectors.py", "recordings.py", "face_vectors.py"]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-api", action="store_true", help="accepted for older scripts: nothing to skip any more")
    ap.parse_args()
    for s in DETERMINISTIC:
        subprocess.run([sys.executable, str(TOOLS / s)], check=True, cwd=TOOLS)


if __name__ == "__main__":
    main()
