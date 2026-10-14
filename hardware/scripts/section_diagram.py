"""Draw docs/images/robot_section.png: the side section of the robot, cut from the CAD, and the 60 GHz beam at the desk.

The left panel is the real section of hardware/cad/marvin.scad at x = 0 (every part exported by
OpenSCAD and sliced here), so it follows the CAD: head shell, window, screen, XIAO, face frame,
body, both radars. The knob is off the plane (on the left side) and drawn dashed.

Usage: python3 hardware/scripts/section_diagram.py [out.png]   (needs OpenSCAD and matplotlib)
       C8_AMEND=true python3 hardware/scripts/section_diagram.py   draws the amended head

SPDX-License-Identifier: MIT
"""
import ast
import math
import os
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import matplotlib
import numpy as np

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import Circle, PathPatch, Rectangle  # noqa: E402
from matplotlib.path import Path as MPath  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SCAD = REPO / "hardware" / "cad" / "marvin.scad"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "images" / "robot_section.png"
AMEND = os.environ.get("C8_AMEND", "false")
INK, MUTE, RED, BLUE, ORG, GREY = "#1d2630", "#6b7480", "#c23b2e", "#2f6bb0", "#d9541a", "#cfcac0"

# part -> (fill, edge, z-order)
PARTS = {
    "plinth": ("#8f8c86", INK, 2), "body": ("#e9e6e0", INK, 2), "sled": ("#d9a27f", INK, 3),
    "neck": ("#222222", INK, 2), "ghost_mr60": ("#b9b3a6", INK, 4), "ghost_ld2450": ("#8fb0d6", INK, 4),
    "head_shell": ("#e9e6e0", INK, 2), "head_cap": ("#e9e6e0", INK, 2), "face_frame": ("#55575c", INK, 3),
    "window": ("#111111", INK, 4), "ghost_lcd": ("#3a4a5a", INK, 4), "ghost_xiao": ("#9aa0a6", INK, 5),
    "ghost_usb": ("#444444", INK, 5),
}


def scad(part, out):
    r = subprocess.run(["openscad", "-o", str(out), "-D", f'PART="{part}"', "-D", f"C8_AMEND={AMEND}", str(SCAD)],
                       capture_output=True, text=True)
    return r.stdout + r.stderr


def read_stl(path):
    data = Path(path).read_bytes()
    v = np.array(re.findall(rb"vertex\s+(\S+)\s+(\S+)\s+(\S+)", data), dtype=float)
    return v.reshape(-1, 3, 3)


def section_loops(tri, x0=0.0):
    """Slice a closed mesh with the plane x = x0 -> closed loops of (y, z), outer loops and holes
    oriented oppositely (the triangle normals set the direction), ready for a nonzero fill."""
    d = tri[:, :, 0] - x0
    d = np.where(np.abs(d) < 1e-7, 1e-7, d)
    segs = []
    for t, s in zip(tri, d):
        pts = []
        for i, j in ((0, 1), (1, 2), (2, 0)):
            if (s[i] > 0) != (s[j] > 0):
                u = s[i] / (s[i] - s[j])
                p = t[i] + u * (t[j] - t[i])
                pts.append((p[1], p[2]))
        if len(pts) == 2:
            n = np.cross(t[1] - t[0], t[2] - t[0])
            a, b = np.array(pts[0]), np.array(pts[1])
            # direction along the cut: plane normal (1, 0, 0) x face normal, projected on (y, z)
            want = np.array([-n[2], n[1]])
            if np.dot(b - a, want) < 0:
                a, b = b, a
            segs.append((a, b))
    key = lambda p: (round(p[0], 4), round(p[1], 4))   # noqa: E731
    nxt = {}
    for a, b in segs:
        nxt.setdefault(key(a), []).append(b)
    loops, used = [], set()
    for a, b in segs:
        if (key(a), key(b)) in used:
            continue
        loop, cur = [a], a
        while True:
            cands = [c for c in nxt.get(key(cur), []) if (key(cur), key(c)) not in used]
            if not cands:
                break
            c = cands[0]
            used.add((key(cur), key(c)))
            cur = c
            if key(cur) == key(loop[0]):
                break
            loop.append(cur)
        if len(loop) > 2:
            loops.append(np.array(loop))
    return loops


def draw_section(ax, loops, fc, ec, z):
    if not loops:
        return
    verts, codes = [], []
    for lp in loops:
        pts = np.column_stack([-lp[:, 0], lp[:, 1]])           # toward the user = right
        verts += [*pts, pts[0]]
        codes += [MPath.MOVETO] + [MPath.LINETO] * (len(pts) - 1) + [MPath.CLOSEPOLY]
    ax.add_patch(PathPatch(MPath(verts, codes), fc=fc, ec=ec, lw=0.6, zorder=z))


def main():
    work = Path(tempfile.mkdtemp(prefix="marvin-section-"))
    echo = scad("check_values", work / "values.echo")
    echo += (work / "values.echo").read_text() if (work / "values.echo").exists() else ""
    vals = {k: ast.literal_eval(v) for k, v in re.findall(r'ECHO: "CHECK (\w+)=(.*)"', echo)}
    radar = re.search(r"MR60BHA2 face centre: y=(\S+) z=(\S+) \| LD2450 face centre: y=(\S+) z=(\S+)\"", echo)
    kit = (float(radar[1]), float(radar[2]))
    ld = (float(radar[3]), float(radar[4]))
    with ThreadPoolExecutor(max_workers=os.cpu_count() or 2) as ex:
        list(ex.map(lambda p: scad(p, work / f"{p}.stl"), PARTS))

    fig, (a, b) = plt.subplots(1, 2, figsize=(14, 6.4), gridspec_kw={"width_ratios": [1, 1.7]})

    # ---- section, cut at x = 0
    for p, (fc, ec, z) in PARTS.items():
        f = work / f"{p}.stl"
        if f.exists() and f.stat().st_size:
            draw_section(a, section_loops(read_stl(f)), fc, ec, z)
    for f in work.iterdir():
        f.unlink()
    work.rmdir()
    # the knob is on the left side, off the cutting plane
    a.add_patch(Circle((-4, 96), 9, fc="none", ec=ORG, lw=1.2, ls="--", zorder=6))
    a.text(-4, 96, "knob\n(left side)", fontsize=6.5, color=ORG, ha="center", va="center", zorder=6)

    cam = vals["camLens"]
    tilt = vals["camTilt"]
    labels = ((kit, 20, RED, "60 GHz radar\ntilted 20°"), (ld, 10, BLUE, "24 GHz radar\ntilted 10°"),
              ((cam[1], cam[2]), tilt, ORG, f"camera\ntilted {tilt}°"))
    for (y, z), ang, col, text in labels:
        x = -y
        L = 70
        a.annotate("", (x + L * math.cos(math.radians(ang)), z + L * math.sin(math.radians(ang))), (x, z),
                   arrowprops=dict(arrowstyle="->", color=col, lw=1.6), zorder=7)
        a.text(-62, z, text, fontsize=8, color=col, va="center", ha="right")
    sc = vals["screenCentre"]
    a.text(-62, sc[2], "screen\n(vertical, behind\nthe smoked window)", fontsize=8, color=MUTE, va="center", ha="right")
    a.axhline(0, color=INK, lw=1)
    a.text(-10, -14, "back", fontsize=8, color=MUTE, ha="center")
    a.text(60, -14, "toward you →", fontsize=8, color=MUTE, ha="center")
    a.set_xlim(-150, 115)
    a.set_ylim(-25, vals["hZ1"] + 30)
    a.set_aspect("equal")
    a.axis("off")
    title = "Section at the middle, from the CAD"
    if vals.get("C8_AMEND"):
        title += " (C8 amendment)"
    a.set_title(title, fontsize=11, loc="left", color=INK)

    # ---- desk scene
    top = vals["hZ1"]
    CHEST = (280, 450)
    b.axhline(0, color=INK, lw=1.2)
    b.add_patch(Rectangle((0, 6), 74, top - 6, fc="#e9e6e0", ec=INK, lw=1))
    hx, hz = 74, kit[1]
    for d in (500, 700, 1000, 1500):
        b.axvline(hx + d, color=GREY, lw=0.8, ls=":")
        b.text(hx + d, -45, f"{d / 1000:g} m", ha="center", fontsize=8, color=MUTE)
    b.add_patch(Rectangle((450, CHEST[0]), 1100, CHEST[1] - CHEST[0], fc=RED, alpha=0.08, ec="none"))
    b.text(1560, sum(CHEST) / 2, f"seated chest\n{CHEST[0]}–{CHEST[1]} mm\nabove desk", fontsize=8, color=RED, va="center")
    t = math.radians(20)
    b.plot([hx, hx + 1500], [hz, hz + 1500 * math.tan(t)], color=RED, lw=2)
    for d in (500, 700, 1000):
        b.plot(hx + d, hz + d * math.tan(t), "o", color=RED, ms=4)
    px = hx + 750
    b.add_patch(Rectangle((px - 20, 20), 150, 380, fc="#d9d3c7", ec="none"))
    b.add_patch(plt.Circle((px + 55, 520), 80, fc="#d9d3c7", ec="none"))
    b.text(px + 55, 630, "you, 0.7 m", ha="center", fontsize=8, color=MUTE)
    b.text(120, 190, "60 GHz boresight, 20° up", color=RED, fontsize=9)
    b.set_xlim(-60, 1900)
    b.set_ylim(-70, 700)
    b.set_aspect("equal")
    b.axis("off")
    b.set_title("At the desk: the beam lands on your chest, the body stays upright", fontsize=11, loc="left", color=INK)

    plt.tight_layout()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(OUT, dpi=150, bbox_inches="tight", pad_inches=0.2)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
