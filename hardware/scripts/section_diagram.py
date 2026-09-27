"""Draw docs/images/robot_section.png: side section of the robot and the 60 GHz beam at the desk.

Heights come from hardware/cad/marvin.scad (body) and the planned head layout.
Usage: python3 hardware/scripts/section_diagram.py   (needs matplotlib)
"""
import math
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Polygon, Rectangle

OUT = Path(__file__).resolve().parents[2] / "docs" / "images" / "robot_section.png"
INK, MUTE, RED, BLUE, ORG, GREY = "#1d2630", "#6b7480", "#c23b2e", "#2f6bb0", "#d9541a", "#cfcac0"

# --- geometry (mm). y = 0 is the outer front face of the body, positive toward the user ---
BODY = (6, 64, 74)          # z0, z1, depth
HEAD = (68, 124, 80)
KIT = dict(z=41.7, tilt=20, h=35, d=22)    # MR60BHA2 case, face centre height (marvin.scad echo)
LD = dict(z=16.4, tilt=10, h=15, d=2)      # HLK-LD2450
SCREEN_Z = (76, 107)                        # planned head layout
CAM_Z = 113
LIDAR_TOP = 141
CHEST = (280, 450)                          # chest band of a seated person, above the desk


def rbox(ax, x, y, w, h, fc, r=8, z=1):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle=f"round,pad=0,rounding_size={r}", fc=fc, ec=INK, lw=1.2, zorder=z))


def tilted(ax, front_y, cz, depth, height, ang, fc):
    """Module whose front face (centre at front_y, cz) looks up by ang; drawn with the user to the right."""
    t = math.radians(ang)
    up = (-math.sin(t), math.cos(t))       # along the face, leaning back (to the left)
    back = (-math.cos(t), -math.sin(t))    # into the robot
    c = (front_y, cz)
    corners = []
    for u, d in ((-height / 2, 0), (height / 2, 0), (height / 2, depth), (-height / 2, depth)):
        corners.append((c[0] + up[0] * u + back[0] * d, c[1] + up[1] * u + back[1] * d))
    ax.add_patch(Polygon(corners, closed=True, fc=fc, ec=INK, lw=0.8, zorder=3))
    return c


fig, (a, b) = plt.subplots(1, 2, figsize=(14, 6), gridspec_kw={"width_ratios": [1, 1.7]})

# ---- section ----
rbox(a, -BODY[2], BODY[0], BODY[2], BODY[1] - BODY[0], "#e9e6e0")
a.add_patch(Rectangle((-BODY[2] + 6, 0), BODY[2] - 12, 6, fc="#8f8c86", ec=INK, lw=0.8))            # plinth
a.add_patch(Rectangle((-BODY[2] + 5, BODY[1]), BODY[2] - 10, HEAD[0] - BODY[1], fc="#222", zorder=2))  # TPU neck
a.add_patch(Rectangle((-1.6, 20), 1.6, 30, fc="#3a3a3c", zorder=2))                               # band
rbox(a, -HEAD[2] + 3, HEAD[0], HEAD[2], HEAD[1] - HEAD[0], "#e9e6e0")
a.add_patch(Rectangle((1.4, 74), 1.6, 44, fc="#111", zorder=2))                                     # smoked window
a.add_patch(Rectangle((-5, SCREEN_Z[0]), 4.5, SCREEN_Z[1] - SCREEN_Z[0], fc="#3a4a5a", zorder=3))
hc = -HEAD[2] / 2 + 3                                                                               # head centre
a.add_patch(Rectangle((hc - 19.3, HEAD[1] - 18), 38.6, 18 + 4, fc="#222", zorder=2))                  # lidar base, recessed
a.add_patch(Rectangle((hc - 17.6, HEAD[1] + 4), 35.3, LIDAR_TOP - HEAD[1] - 4, fc="#222", zorder=2))    # turret
a.text(hc, LIDAR_TOP + 5, "lidar", ha="center", fontsize=8, color=MUTE)

f_kit = tilted(a, -2.6, KIT["z"], KIT["d"], KIT["h"], KIT["tilt"], "#b9b3a6")
f_ld = tilted(a, -2.6, LD["z"], LD["d"], LD["h"], LD["tilt"], "#8fb0d6")
f_cam = tilted(a, -1, CAM_Z, 9, 8, 20, "#555")
labels = ((f_kit, "60 GHz radar\ntilted 20°", RED), (f_ld, "24 GHz radar\ntilted 10°", BLUE),
          (f_cam, "camera\ntilted 20°", ORG), ((0, 92), "screen\n(vertical)", MUTE))
for (x, z), text, col in labels:
    a.text(-92, z, text, fontsize=8, color=col, va="center", ha="right")
for (x, z), ang, col in ((f_kit, 20, RED), (f_ld, 10, BLUE), (f_cam, 20, ORG)):
    L = 70
    a.annotate("", (x + L * math.cos(math.radians(ang)), z + L * math.sin(math.radians(ang))), (x, z),
               arrowprops=dict(arrowstyle="->", color=col, lw=1.6))
a.axhline(0, color=INK, lw=1)
a.text(-40, -14, "back", fontsize=8, color=MUTE, ha="center")
a.text(40, -14, "toward you →", fontsize=8, color=MUTE, ha="center")
a.set_xlim(-160, 90); a.set_ylim(-25, 165); a.set_aspect("equal"); a.axis("off")
a.set_title("Section: upright shell, sensors tilted inside", fontsize=11, loc="left", color=INK)

# ---- desk scene ----
b.axhline(0, color=INK, lw=1.2)
b.add_patch(Rectangle((0, 6), 74, HEAD[1] - 6, fc="#e9e6e0", ec=INK, lw=1))
hx, hz = 74, KIT["z"]
for d in (500, 700, 1000, 1500):
    b.axvline(hx + d, color=GREY, lw=0.8, ls=":")
    b.text(hx + d, -45, f"{d / 1000:g} m", ha="center", fontsize=8, color=MUTE)
b.add_patch(Rectangle((450, CHEST[0]), 1100, CHEST[1] - CHEST[0], fc=RED, alpha=0.08, ec="none"))
b.text(1560, sum(CHEST) / 2, f"seated chest\n{CHEST[0]}–{CHEST[1]} mm\nabove desk", fontsize=8, color=RED, va="center")
t = math.radians(KIT["tilt"])
b.plot([hx, hx + 1500], [hz, hz + 1500 * math.tan(t)], color=RED, lw=2)
for d in (500, 700, 1000):
    b.plot(hx + d, hz + d * math.tan(t), "o", color=RED, ms=4)
px = hx + 750
b.add_patch(Rectangle((px - 20, 20), 150, 380, fc="#d9d3c7", ec="none"))
b.add_patch(plt.Circle((px + 55, 520), 80, fc="#d9d3c7", ec="none"))
b.text(px + 55, 630, "you, 0.7 m", ha="center", fontsize=8, color=MUTE)
b.text(120, 190, "60 GHz boresight, 20° up", color=RED, fontsize=9)
b.set_xlim(-60, 1900); b.set_ylim(-70, 700); b.set_aspect("equal"); b.axis("off")
b.set_title("At the desk: the beam lands on your chest, the body stays upright", fontsize=11, loc="left", color=INK)

plt.tight_layout()
plt.savefig(OUT, dpi=150, bbox_inches="tight", pad_inches=0.2)
print(f"wrote {OUT}")
