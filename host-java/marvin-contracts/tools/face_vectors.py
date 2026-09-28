# SPDX-License-Identifier: MIT
"""Face renderer vectors from the Python reference face (host/marvin_host/face.py).

The app shows the face as /face.png (drawn on the host) and /icon.png. A host that serves them
must draw the same pixels. Written to golden/face/:

    face_vectors.json    the expression table, the scripted scenario of host/scripts/face_golden.py
                         (presence states, events) and, per step, the expression and the CRC-32 of
                         the RGB888 frame (row-major, 280 x 240 x 3 bytes)
    <expression>.png     each expression drawn at rest (no gaze, no blink), as png.encode writes it
    icon.png             the app icon (the "content" face, rows 26 to 265)

Compare pixels, not PNG bytes: two zlib implementations may compress the same pixels differently.

    python3 face_vectors.py
"""
from __future__ import annotations

import dataclasses
import sys
import zlib

from _common import GOLDEN, HOST, rel, write_json

sys.path.insert(0, str(HOST / "scripts"))

import face_golden  # noqa: E402

from marvin_host import face  # noqa: E402
from marvin_host.events import Event  # noqa: E402
from marvin_host.protocol import FACE_EVENT_CODES  # noqa: E402
from marvin_host.ui import png  # noqa: E402

OUT = GOLDEN / "face"


def crc(frame) -> str:
    return f"{zlib.crc32(frame.astype('uint8').tobytes()):08x}"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    expressions = {}
    for name, p in face.EXPRESSIONS.items():
        frame = face.render(p)
        (OUT / f"{name}.png").write_bytes(png.encode(frame))
        expressions[name] = {"params": dataclasses.asdict(p), "crc32_rgb888": crc(frame)}
    icon = face.render(face.EXPRESSIONS["content"])[26:266]
    (OUT / "icon.png").write_bytes(png.encode(icon))

    f = face.Face(face_golden.SEED)
    steps = []
    for t_ms, state, evs in face_golden.steps():
        for ev in evs:
            f.on_event(Event(ev, t_ms * 1000))
        frame = f.update(face_golden.STATES[state], t_ms / 1000.0)
        steps.append({"t_ms": t_ms, "state": state, "events": [e.value for e in evs],
                      "expression": f.expression, "crc32_rgb888": crc(frame)})

    doc = {"about": "Face vectors from host/marvin_host/face.py (host-java/marvin-contracts/tools/face_vectors.py).",
           "screen": {"width": face.SCREEN_W, "height": face.SCREEN_H},
           "seed": face_golden.SEED,
           "face_event_codes": {k.value: v for k, v in FACE_EVENT_CODES.items()},
           "expressions": expressions,
           "transients": {k.value: {"expression": v[0], "seconds": v[1]} for k, v in face.TRANSIENTS.items()},
           "icon": {"expression": "content", "rows": [26, 266], "crc32_rgb888": crc(icon)},
           "states": {n: dataclasses.asdict(s) for n, s in face_golden.STATES.items()},
           "steps": steps}
    write_json(OUT / "face_vectors.json", doc, compact=False)
    print(f"wrote {rel(OUT)}: {len(expressions)} expressions, {len(steps)} steps")


if __name__ == "__main__":
    main()
