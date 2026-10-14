#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Doc lint: the docs say what the code does (Marvin v1 contract C9, frozen 2026-10-14).

Rules (each one a check with an id; a failure prints file:line, the rule id and what to fix):

  no-lidar-claim   No robot-facing text claims Marvin has a lidar: README.md, README.fr.md, docs/*.md (except
                   the history and the parts about the bench, the USB sensor bridge and the room scanner), the
                   LLM persona (host-java/.../domain/conversation/Persona.java) and the app's strings. Rev F has
                   no lidar: two radars (HLK-LD2450, MR60BHA2), a camera, a microphone, a screen, a speaker, a
                   knob.
  pin-tables       Every pin table in the docs (docs/wiring.md, docs/xiao-bench.md, firmware/README.md) matches
                   the -DMARVIN_*_PIN / -DMARVIN_BUTTON_* build flags of its environment in
                   firmware/platformio.ini.
  env-table        The table of PlatformIO environments in the docs (firmware/README.md, docs/tools.md) lists
                   exactly the [env:*] sections of firmware/platformio.ini.
  protocol-table   The message types, HELLO flags, button ids and actions, and sound ids in docs/protocol.md
                   match the constants of ProtocolV1.java / Button.java (and therefore protocol.h and
                   protocol.py, which the protocol vectors keep in lockstep).
  relative-links   Every relative link and image in *.md resolves to a file (and an anchor, when it has one).
  readme-sync      README.md and README.fr.md have the same sections: same number of headings at each level,
                   same images, same links.

Usage (once implemented): python tools/doclint.py [--rule ID ...]; exit status 1 when a rule fails.

The implementation is workstream W8's (Marvin v1 plan): this file only freezes the rule list.
"""

import sys

RULES = ("no-lidar-claim", "pin-tables", "env-table", "protocol-table", "relative-links", "readme-sync")


def main() -> int:
    print("doclint: not implemented yet; rules:", ", ".join(RULES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
