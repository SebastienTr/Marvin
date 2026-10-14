#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Fails when the integration tests did not really run.

The Testcontainers ITs are marked `@Testcontainers(disabledWithoutDocker = true)`, so a machine without Docker
skips them and `mvn verify` stays green. CI has Docker: there, an IT class whose tests were all skipped (or no IT
at all) is a failure. Usage, after `mvn verify`:

    python tools/ci/check_its.py host-java [--allow EmbeddedDatabaseIT ...]

Prints one line per IT class (tests, failures, errors, skipped) and the reason of every skipped test.
"""

from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def suites(root: Path):
    for report in sorted(root.glob("**/target/failsafe-reports/TEST-*.xml")):
        try:
            tree = ET.parse(report)
        except ET.ParseError as e:
            yield report.stem, None, str(e)
            continue
        for suite in ([tree.getroot()] if tree.getroot().tag == "testsuite" else tree.getroot().iter("testsuite")):
            yield suite.get("name", report.stem), suite, ""


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("root", nargs="?", default="host-java")
    ap.add_argument("--allow", action="append", default=[],
                    help="an IT class (simple name) that may be skipped entirely here")
    ap.add_argument("--min", type=int, default=1, help="the fewest IT classes that must have run")
    args = ap.parse_args(argv)
    problems, ran = [], 0
    for name, suite, error in suites(Path(args.root)):
        simple = name.rsplit(".", 1)[-1]
        if suite is None:
            problems.append(f"{simple}: unreadable report ({error})")
            continue
        tests, skipped = int(suite.get("tests", 0)), int(suite.get("skipped", 0))
        failures, errors = int(suite.get("failures", 0)), int(suite.get("errors", 0))
        print(f"{simple:40} tests {tests:3}  failures {failures}  errors {errors}  skipped {skipped}")
        for case in suite.iter("testcase"):
            sk = case.find("skipped")
            if sk is not None:
                reason = (sk.get("message") or sk.text or "").strip().splitlines()
                print(f"    skipped {case.get('name')}: {reason[0] if reason else '(no reason)'}")
        if tests and skipped == tests:
            if simple in args.allow:
                print("    (allowed to be skipped here)")
            else:
                problems.append(f"{simple}: all {tests} tests skipped (Docker or the Python host missing?)")
        elif tests:
            ran += 1
    if ran < args.min:
        problems.append(f"only {ran} IT classes ran (expected at least {args.min}): were the failsafe reports made?")
    for p in problems:
        print(f"::error::{p}" if "GITHUB_ACTIONS" in os.environ else f"ERROR {p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
