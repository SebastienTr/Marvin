# SPDX-License-Identifier: MIT
"""Shared helpers for the golden-file generators: find the Python host, write files the same way every time."""
from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
CONTRACTS = TOOLS.parent
GOLDEN = CONTRACTS / "golden"
REPO = CONTRACTS.parent.parent
HOST = REPO / "host"

# the generators import the Python host from this checkout, never from an installed copy
if str(HOST) not in sys.path:
    sys.path.insert(0, str(HOST))
# days and times in the snapshots are computed in UTC, whatever the machine's time zone
os.environ["TZ"] = "UTC"
try:
    import time as _time
    _time.tzset()
except AttributeError:          # not on Windows
    pass


def clean(v):
    """JSON-safe: tuples to lists, numpy scalars and arrays to Python, NaN and infinities to None."""
    try:
        import numpy as np
    except ImportError:          # pragma: no cover
        np = None
    if isinstance(v, dict):
        return {str(k): clean(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [clean(x) for x in v]
    if np is not None:
        if isinstance(v, np.ndarray):
            return clean(v.tolist())
        if isinstance(v, np.generic):
            return clean(v.item())
    if isinstance(v, float) and not math.isfinite(v):
        return None
    if isinstance(v, bytes):
        return v.hex()
    return v


def write_json(path: Path, obj, compact: bool = False) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(clean(obj), ensure_ascii=False, sort_keys=False,
                      indent=None if compact else 2, separators=(",", ":") if compact else None)
    path.write_text(text + "\n", encoding="utf-8")
    return path


def write_jsonl(path: Path, rows) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(clean(r), ensure_ascii=False, separators=(",", ":")) + "\n")
    return path


def rel(path: Path) -> str:
    return str(path.relative_to(REPO))
