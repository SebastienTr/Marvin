#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Regenerates the Python gRPC code of the voice contract (marvin_host/sidecar/voice/contract/)
from host-java/marvin-contracts/src/main/proto/marvin/voice/v1/voice.proto, the single source of
the contract. Needs grpcio-tools (pip install -e "host[dev]").

    python host/scripts/gen_voice_contract.py            # rewrite the generated files
    python host/scripts/gen_voice_contract.py --check    # exit 1 if they are out of date

The generated modules import each other relatively, so they live inside marvin_host.
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PROTO_ROOT = REPO / "host-java" / "marvin-contracts" / "src" / "main" / "proto"
PROTO = "marvin/voice/v1/voice.proto"
OUT = REPO / "host" / "marvin_host" / "sidecar" / "voice" / "contract"
FILES = ("voice_pb2.py", "voice_pb2.pyi", "voice_pb2_grpc.py")
HEADER = "# SPDX-License-Identifier: MIT\n# Generated from {proto} by host/scripts/gen_voice_contract.py: do not edit.\n"


def generate() -> dict[str, str]:
    """{file name: content} of the generated modules."""
    from grpc_tools import protoc
    import grpc_tools
    include = Path(grpc_tools.__file__).parent / "_proto"
    with tempfile.TemporaryDirectory() as d:
        rc = protoc.main(["protoc", f"-I{PROTO_ROOT}", f"-I{include}", f"--python_out={d}", f"--pyi_out={d}",
                          f"--grpc_python_out={d}", str(PROTO_ROOT / PROTO)])
        if rc != 0:
            raise SystemExit(f"protoc failed ({rc})")
        gen = Path(d) / Path(PROTO).parent
        out = {}
        for name in FILES:
            text = (gen / name).read_text(encoding="utf-8")
            text = text.replace("from marvin.voice.v1 import voice_pb2 as", "from . import voice_pb2 as")
            out[name] = HEADER.format(proto=PROTO) + text
        return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="only check that the generated files are up to date")
    args = ap.parse_args(argv)
    files = generate()
    stale = [n for n, text in files.items()
             if not (OUT / n).exists() or (OUT / n).read_text(encoding="utf-8") != text]
    if args.check:
        if stale:
            print(f"out of date: {', '.join(stale)} (run host/scripts/gen_voice_contract.py)", file=sys.stderr)
            return 1
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for n, text in files.items():
        (OUT / n).write_text(text, encoding="utf-8")
    print(f"wrote {', '.join(files)} to {OUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
