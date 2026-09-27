"""Render a contact sheet of the face's expressions and an animated demo, for the docs.

Every picture comes from ``marvin_host.face.Face`` driven by scripted events and presence
states, exactly as the brain drives it, so what you see is what the robot will show.

    python scripts/face_preview.py            # writes docs/images/face_expressions.png
    python scripts/face_preview.py --gif      # also writes docs/images/face_demo.gif

Needs Pillow (a dev dependency only; the face itself needs numpy alone).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from marvin_host.events import Event, EventKind, PresenceState
from marvin_host.face import EXPRESSIONS, SCREEN_H, SCREEN_W, Face, render

DOCS = pathlib.Path(__file__).resolve().parents[2] / "docs" / "images"
SHEET_BG = (44, 42, 40)          # anthracite, like the face frame
LABEL = (205, 200, 192)
SUBLABEL = (140, 135, 128)
FPS = 20

SEATED_HEAD = (0.0, -800.0, 400.0)          # a seated person's head, device frame, mm
STANDING_HEAD = (0.0, -1300.0, 950.0)


def person(head, seated=False, **kw) -> PresenceState:
    dist = math.hypot(head[0], head[1]) / 1000.0
    return PresenceState(present=True, seated=seated, head=head, position=(head[0], head[1], 16.0),
                         distance_m=dist, **kw)


NOBODY = PresenceState()


def run(script, until: float, seed: int = 3) -> np.ndarray:
    """Play ``script`` (a list of (t, state, event-kind or None)) and return the frame at ``until``."""
    face = Face(seed)
    frame = None
    steps = sorted(script, key=lambda s: s[0])
    state = NOBODY
    t, i = 0.0, 0
    while t <= until + 1e-9:
        while i < len(steps) and steps[i][0] <= t + 1e-9:
            _, state, kind = steps[i]
            if kind is not None:
                face.on_event(Event(kind, int(t * 1e6)))
            i += 1
        frame = face.update(state, t)
        t += 1.0 / FPS
    return frame


def cells() -> list[tuple[str, str, np.ndarray]]:
    left = (-700.0, -1000.0, 700.0)
    right = (700.0, -1000.0, 700.0)
    centre = (0.0, -1100.0, 700.0)
    arrive = lambda head: [(0.0, person(head), EventKind.ARRIVED)]  # noqa: E731
    seated = [(0.0, person(SEATED_HEAD), EventKind.ARRIVED),
              (2.0, person(SEATED_HEAD, seated=True), EventKind.SAT_DOWN)]
    gone = seated + [(4.0, NOBODY, EventKind.LEFT)]
    return [
        ("looking left", "person at -X", run(arrive(left), 3.0)),
        ("looking ahead", "neutral", run(arrive(centre), 3.0)),
        ("looking right", "person at +X", run(arrive(right), 3.0)),
        ("awake", "ARRIVED", run(arrive(STANDING_HEAD), 0.6)),
        ("surprised", "APPROACHED", run(arrive(centre) + [(3.0, person((0, -450, 500)),
                                                                  EventKind.APPROACHED)], 3.5)),
        ("content", "SAT_DOWN", run(seated, 3.0)),
        ("calm", "seated, working", run(seated, 12.0)),
        ("concerned", "STILL_LONG: take a break",
         run(seated + [(14.0, person(SEATED_HEAD, seated=True), EventKind.STILL_LONG)], 18.0)),
        ("blink", "every 2-6 s", render(EXPRESSIONS["neutral"], (0.0, -6.0), blink=0.8)),
        ("sleepy", "nobody for 4 s", run(gone, 11.0)),
        ("asleep", "nobody for 20 s", run(gone, 26.0)),
        ("vitals", "VITALS_ACQUIRED",
         run(seated + [(12.0, person(SEATED_HEAD, seated=True, heart_rate=64.0),
                        EventKind.VITALS_ACQUIRED)], 13.9)),
    ]


def font(size: int):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:                                   # Pillow < 10.1
        return ImageFont.load_default()


def contact_sheet(path: pathlib.Path, cols: int = 4, scale: float = 0.75) -> None:
    items = cells()
    cw, ch = int(SCREEN_W * scale), int(SCREEN_H * scale)
    pad, label_h = 14, 38
    rows = math.ceil(len(items) / cols)
    sheet = Image.new("RGB", (pad + cols * (cw + pad), pad + rows * (ch + label_h + pad)), SHEET_BG)
    draw = ImageDraw.Draw(sheet)
    big, small = font(15), font(12)
    for k, (title, note, frame) in enumerate(items):
        x = pad + (k % cols) * (cw + pad)
        y = pad + (k // cols) * (ch + label_h + pad)
        sheet.paste(Image.fromarray(frame).resize((cw, ch), Image.LANCZOS), (x, y))
        draw.text((x, y + ch + 6), title, fill=LABEL, font=big)
        draw.text((x, y + ch + 23), note, fill=SUBLABEL, font=small)
    sheet = sheet.quantize(colors=64, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    sheet.save(path, optimize=True)
    print(f"wrote {path} ({path.stat().st_size // 1024} kB)")


def demo_gif(path: pathlib.Path, scale: float = 0.75) -> None:
    """Arrive, look around, sit, blink, a break reminder, leave, fall asleep (sleep sped up)."""
    face = Face(seed=7)
    frames: list[Image.Image] = []
    size = (int(SCREEN_W * scale), int(SCREEN_H * scale))

    def head_at(t: float) -> tuple[float, float, float]:
        if t < 5.0:                                     # walks in from the right, towards the desk
            u = (t - 1.5) / 3.5
            return (900.0 * (1 - u) - 150.0 * u, -1800.0 + 1000.0 * u, 950.0 - 550.0 * u)
        return (120.0 * math.sin(0.7 * (t - 5.0)), -800.0, 400.0)   # seated, swaying a little

    events = {1.5: EventKind.ARRIVED, 4.2: EventKind.APPROACHED, 5.0: EventKind.SAT_DOWN,
              12.0: EventKind.STILL_LONG, 19.0: EventKind.LEFT}
    pending = sorted(events.items())
    t, step = 0.0, 1.0 / FPS
    while t < 43.0:
        state = person(head_at(t), seated=t >= 5.0) if 1.5 <= t < 19.0 else NOBODY
        while pending and pending[0][0] <= t + 1e-6:
            face.on_event(Event(pending.pop(0)[1], int(t * 1e6)))
        frame = face.update(state, t)
        frames.append(Image.fromarray(frame).resize(size, Image.LANCZOS))
        t += step * (4 if t >= 25.0 else 1)             # time-lapse the fall into sleep (x4)
    # One shared palette, built from frames spread over the whole demo (awake and asleep colours).
    picks = frames[:: max(1, len(frames) // 16)]
    mosaic = Image.new("RGB", (size[0] * len(picks), size[1]))
    for k, f in enumerate(picks):
        mosaic.paste(f, (k * size[0], 0))
    palette = mosaic.quantize(colors=48, method=Image.Quantize.MEDIANCUT)
    frames = [f.quantize(palette=palette, dither=Image.Dither.NONE) for f in frames]
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=int(1000 / FPS),
                   loop=0, optimize=True, disposal=1)
    print(f"wrote {path} ({path.stat().st_size // 1024} kB, {len(frames)} frames)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--gif", action="store_true", help="also render the animated demo")
    ap.add_argument("--out", type=pathlib.Path, default=DOCS, help="output directory")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    contact_sheet(args.out / "face_expressions.png")
    if args.gif:
        demo_gif(args.out / "face_demo.gif")


if __name__ == "__main__":
    main()
