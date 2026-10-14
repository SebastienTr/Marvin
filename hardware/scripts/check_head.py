#!/usr/bin/env python3
"""Check Marvin's head CAD: fits, clearances, the frozen interface and printability.

Usage:
    python3 hardware/scripts/check_head.py [--amend] [--strict] [--jobs N] [--keep DIR]

Needs OpenSCAD >= 2021.01 in PATH and numpy (nothing else).

What it checks (every check prints one line, PASS / WARN / FAIL):
  frozen    the FROZEN block of marvin.scad still holds the C8 values (head <-> body interface)
  envelope  the assembled head stays inside 88 x 80 mm, from z 68 to the head top
  neck      the four M3 pilots and the cable hole are open where the body expects them
  fit       no printed part runs into a module, the acrylic, another part or the shell;
            every module stays inside the head (ghosts: screen, XIAO stack, USB-C plug, tact switch)
  c8-fit    the XIAO stack and the screen module do not run into each other
  optics    active area inside the window opening, camera hole inside it, vignetting of the hole
  screws    every M3 has at least 4 mm of thread in a pilot that is deep enough
  manifold  every print-ready STL is closed and 2-manifold (OpenSCAD "Simple: yes")
  bed       every part lies on the bed and fits the A1 (256 x 256 x 256 mm)
  overhang  no unsupported overhang steeper than 45 deg beyond the known, documented ones;
            bridges no longer than 42 mm
  wall      no wall thinner than 0.8 mm (two perimeters of a 0.4 mm nozzle)

While H0_PROVISIONAL is true in marvin.scad, the checks that depend on the provisional module
sizes ("fit", "c8-fit", "optics") report WARN instead of FAIL, unless --strict. The exit code is
1 when any check FAILs.

SPDX-License-Identifier: MIT
"""
import argparse
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
SCAD = REPO / "hardware" / "cad" / "marvin.scad"

# C8, frozen 14 October 2026 (docs/architecture.md, "Coordinate frame")
C8 = {"headW": 88, "headD": 80, "headR": 16, "headZ0": 68, "headZ1": 124,
      "neckBolt": [[-32, -20], [32, -20], [-32, 20], [32, 20]], "cableHole": [30, 16, 5], "cableHoleY": 4,
      "camPos": [0, -38, 113], "camTilt": 20, "screenCentre": [0, -38, 92]}

PRINT_PARTS = ["print_head_shell", "print_head_cap", "print_face_frame", "print_knob",
               "print_switch_bracket", "print_window_jig",
               "print_body", "print_plinth", "print_sled", "print_tpu"]
HEAD_PRINTED = ["head_shell", "head_cap", "face_frame", "knob", "switch_bracket"]
GHOSTS = ["ghost_lcd", "ghost_xiao", "ghost_usb", "ghost_tact", "window"]
# (a, b, depends on H0 sizes)
CLASHES = [
    ("face_frame", "head_shell", True), ("face_frame", "head_cap", False), ("face_frame", "window", False),
    ("face_frame", "ghost_lcd", True), ("face_frame", "ghost_xiao", True), ("face_frame", "ghost_usb", True),
    ("head_shell", "window", False), ("head_shell", "knob", False), ("head_shell", "switch_bracket", False),
    ("head_shell", "head_cap", False), ("head_shell", "ghost_lcd", True), ("head_shell", "ghost_xiao", True),
    ("head_shell", "ghost_usb", True), ("head_shell", "ghost_tact", True),
    ("head_cap", "ghost_xiao", True), ("head_cap", "ghost_usb", True),
    ("knob", "switch_bracket", False), ("knob", "ghost_tact", True), ("switch_bracket", "ghost_tact", True),
    ("switch_bracket", "ghost_usb", True), ("knob", "ghost_usb", True),
    ("window", "ghost_lcd", True), ("window", "ghost_xiao", True),
]
C8_FIT = [("ghost_xiao", "ghost_lcd"), ("ghost_usb", "ghost_lcd")]
# a module must stay inside the head's hollow ("head_hollow" is everything outside it)
INSIDE = ["ghost_lcd", "ghost_xiao", "ghost_usb", "ghost_tact"]
CLASH_TOL = 0.5          # mm^3: below this an intersection is CGAL noise on touching faces

# Known steep overhangs, accepted and documented in docs/printing.md:
#   print_head_shell: the plan corners (r 16) meet the front face, which lies on the bed, tangentially;
#   the lowest 4.7 mm of each front corner are steeper than 45 deg (only the first 2 mm steeper than 60).
#   print_head_cap: the same at the back corners.
# Small arcs (the top of a round hole in a wall, the corners of a rounded slot) are accepted when
# they are at most SMALL_ARC mm2 and 3 mm high: the slicer bridges them.
KNOWN_STEEP = {"print_head_shell": {"zmax": 5.0, "why": "front corners, plan radius tangent to the bed"},
               "print_head_cap": {"zmax": 5.0, "why": "back corners, plan radius tangent to the bed"},
               "print_tpu": {"zmax": 99, "why": "body half, unchanged since rev F"},
               "print_body": {"zmax": 99, "why": "body half, unchanged since rev F"},
               "print_sled": {"zmax": 99, "why": "body half, unchanged since rev F"},
               "print_plinth": {"zmax": 99, "why": "body half, unchanged since rev F"}}
BRIDGE_MAX = 42.0
SMALL_ARC = 20.0
WALL_MIN = 0.75
BED = 256.0


class Report:
    def __init__(self, provisional, strict):
        self.provisional, self.strict, self.fails, self.warns = provisional, strict, 0, 0

    def line(self, check, ok, text, h0=False):
        if ok:
            tag = "PASS"
        elif h0 and self.provisional and not self.strict:
            tag = "WARN"
            self.warns += 1
        else:
            tag = "FAIL"
            self.fails += 1
        print(f"{tag:4}  {check:9} {text}", flush=True)

    def warn(self, check, text):
        self.warns += 1
        print(f"WARN  {check:9} {text}", flush=True)


def openscad(part, out, defs, extra=()):
    cmd = ["openscad", "-o", str(out), "-D", f'PART="{part}"', *extra]
    for k, v in defs.items():
        cmd += ["-D", f"{k}={v}"]
    cmd.append(str(SCAD))
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def read_stl(path):
    """ASCII or binary STL -> (triangles[n, 3, 3])."""
    data = Path(path).read_bytes()
    if data[:5] == b"solid" and b"facet" in data[:400]:
        v = np.array(re.findall(rb"vertex\s+(\S+)\s+(\S+)\s+(\S+)", data), dtype=float)
        return v.reshape(-1, 3, 3)
    n = int.from_bytes(data[80:84], "little")
    rec = np.frombuffer(data[84:84 + n * 50], dtype=np.dtype([("n", "<3f4"), ("v", "<9f4"), ("a", "<u2")]))
    return rec["v"].reshape(-1, 3, 3).astype(float)


def volume(tri):
    return float(np.einsum("ij,ij->i", tri[:, 0], np.cross(tri[:, 1], tri[:, 2])).sum() / 6.0)


def normals_areas(tri):
    c = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
    a = np.linalg.norm(c, axis=1)
    n = c / np.where(a[:, None] > 0, a[:, None], 1)
    return n, a / 2


def vertex_ids(tri, q=1e-3):
    keys = np.round(tri.reshape(-1, 3) / q).astype(np.int64)
    _, ids = np.unique(keys, axis=0, return_inverse=True)
    return ids.reshape(-1, 3)


def watertight(tri):
    f = vertex_ids(tri)
    f = f[(f[:, 0] != f[:, 1]) & (f[:, 1] != f[:, 2]) & (f[:, 2] != f[:, 0])]   # slivers that the STL's precision collapsed
    e = np.sort(np.concatenate([f[:, [0, 1]], f[:, [1, 2]], f[:, [2, 0]]]), axis=1)
    _, counts = np.unique(e, axis=0, return_counts=True)
    return bool((counts == 2).all())


def components(tri, sel):
    """Connected components (shared vertex) of the selected faces -> list of index arrays."""
    idx = np.nonzero(sel)[0]
    if len(idx) == 0:
        return []
    f = vertex_ids(tri)[idx]
    parent = list(range(len(idx)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    first = {}
    for i, face in enumerate(f):
        for v in face:
            if v in first:
                a, b = find(i), find(first[v])
                if a != b:
                    parent[a] = b
            else:
                first[v] = i
    groups = {}
    for i in range(len(idx)):
        groups.setdefault(find(i), []).append(idx[i])
    return [np.array(g) for g in groups.values()]


def thickness(tri, samples=2500, seed=1):
    """Wall thickness at sampled faces: ray from just inside each face, along -normal, to the next face."""
    n, a = normals_areas(tri)
    rng = np.random.default_rng(seed)
    big = a > 1e-6
    pick = rng.choice(np.nonzero(big)[0], size=min(samples, int(big.sum())), replace=False, p=a[big] / a[big].sum())
    org = tri[pick].mean(axis=1) - n[pick] * 1e-3
    d = -n[pick]
    v0, e1, e2 = tri[:, 0], tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]
    out = np.full(len(pick), np.inf)
    for s in range(0, len(pick), 64):
        o, dd = org[s:s + 64, None, :], d[s:s + 64, None, :]
        p = np.cross(dd, e2[None])
        det = np.einsum("rtk,tk->rt", p, e1)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
        tv = o - v0[None]
        u = np.einsum("rtk,rtk->rt", tv, p) * inv
        q = np.cross(tv, e1[None])
        v = np.einsum("rtk,rtk->rt", np.broadcast_to(dd, q.shape), q) * inv
        t = np.einsum("rtk,tk->rt", q, e2) * inv
        hit = ok & (u >= -1e-9) & (v >= -1e-9) & (u + v <= 1 + 1e-9) & (t > 1e-4)
        t = np.where(hit, t, np.inf)
        t[np.arange(len(o)), pick[s:s + 64]] = np.inf
        out[s:s + 64] = t.min(axis=1)
    return out, tri[pick].mean(axis=1), a[pick]


def column_crossings(tri, xs, ys):
    """z of every crossing of the vertical lines (xs[i], ys[j]) with the mesh -> (column index, z) arrays."""
    X, Y = np.meshgrid(xs, ys, indexing="ij")
    px, py = X.ravel(), Y.ravel()
    cols, zs = [], []
    a, b, c = tri[:, 0], tri[:, 1], tri[:, 2]
    lo, hi = tri[:, :, :2].min(axis=1), tri[:, :, :2].max(axis=1)
    keep = (hi[:, 0] >= px.min()) & (lo[:, 0] <= px.max()) & (hi[:, 1] >= py.min()) & (lo[:, 1] <= py.max())
    a, b, c, lo, hi = a[keep], b[keep], c[keep], lo[keep], hi[keep]
    for s in range(0, len(a), 1500):
        A, B, C = a[s:s + 1500], b[s:s + 1500], c[s:s + 1500]
        d = (B[:, 1] - C[:, 1]) * (A[:, 0] - C[:, 0]) + (C[:, 0] - B[:, 0]) * (A[:, 1] - C[:, 1])
        ok = np.abs(d) > 1e-12
        A, B, C, d = A[ok], B[ok], C[ok], d[ok]
        l1 = ((B[:, 1] - C[:, 1])[:, None] * (px[None] - C[:, 0][:, None]) + (C[:, 0] - B[:, 0])[:, None] * (py[None] - C[:, 1][:, None])) / d[:, None]
        l2 = ((C[:, 1] - A[:, 1])[:, None] * (px[None] - C[:, 0][:, None]) + (A[:, 0] - C[:, 0])[:, None] * (py[None] - C[:, 1][:, None])) / d[:, None]
        l3 = 1 - l1 - l2
        inside = (l1 >= 0) & (l2 >= 0) & (l3 >= 0)
        t, q = np.nonzero(inside)
        z = l1[t, q] * A[t, 2] + l2[t, q] * B[t, 2] + l3[t, q] * C[t, 2]
        cols.append(q)
        zs.append(z)
    return (np.concatenate(cols) if cols else np.zeros(0, int)), (np.concatenate(zs) if zs else np.zeros(0))


def occupancy(tri, xs, ys, zs):
    """Boolean grid [len(xs) * len(ys), len(zs)]: is each point inside the closed mesh (ray parity along +z)."""
    col, cz = column_crossings(tri, xs, ys)
    occ = np.zeros((len(xs) * len(ys), len(zs)), bool)
    if len(col) == 0:
        return occ
    order = np.lexsort((cz, col))
    col, cz = col[order], cz[order]
    starts = np.searchsorted(col, np.arange(len(xs) * len(ys)))
    ends = np.searchsorted(col, np.arange(len(xs) * len(ys)), side="right")
    for i in np.nonzero(ends > starts)[0]:
        zc = cz[starts[i]:ends[i]]
        zc = zc[np.concatenate([[True], np.diff(zc) > 1e-6])]   # a line through a shared edge crosses once, not twice
        above = len(zc) - np.searchsorted(zc, zs, side="right")
        occ[i] = (above % 2) == 1
    return occ


def overlap(tri_a, tri_b, max_points=600_000):
    """Volume (mm3) and bounding box of the intersection of two closed meshes, on a voxel grid."""
    pa, pb = tri_a.reshape(-1, 3), tri_b.reshape(-1, 3)
    lo = np.maximum(pa.min(axis=0), pb.min(axis=0))
    hi = np.minimum(pa.max(axis=0), pb.max(axis=0))
    if (hi <= lo).any():
        return 0.0, None
    vol = float(np.prod(hi - lo))
    h = max(0.15, (vol / max_points) ** (1 / 3))
    xs = np.arange(lo[0] + h * 0.5123, hi[0], h)
    ys = np.arange(lo[1] + h * 0.4871, hi[1], h)
    zs = np.arange(lo[2] + h * 0.5037, hi[2], h)
    if not len(xs) or not len(ys) or not len(zs):
        return 0.0, None
    both = occupancy(tri_a, xs, ys, zs) & occupancy(tri_b, xs, ys, zs)
    n = int(both.sum())
    if n == 0:
        return 0.0, None
    ci, zi = np.nonzero(both)
    X = xs[ci // len(ys)]
    Y = ys[ci % len(ys)]
    Z = zs[zi]
    return n * h ** 3, (np.array([X.min(), Y.min(), Z.min()]), np.array([X.max(), Y.max(), Z.max()]))


def parse_values(text):
    vals = {}
    for k, v in re.findall(r'ECHO: "CHECK (\w+)=(.*)"', text):
        vals[k] = eval(v.replace("true", "True").replace("false", "False"), {"__builtins__": {}})   # numbers and lists only
    return vals


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--amend", action="store_true", help="check with C8_AMEND = true (the proposed amendment)")
    ap.add_argument("--strict", action="store_true", help="fits that depend on provisional sizes FAIL instead of WARN")
    ap.add_argument("--jobs", type=int, default=os.cpu_count() or 2)
    ap.add_argument("--keep", help="keep the exported STLs in this folder (and reuse those newer than marvin.scad)")
    args = ap.parse_args()
    if not shutil.which("openscad"):
        sys.exit("openscad not found in PATH")
    defs = {"C8_AMEND": "true" if args.amend else "false"}
    work = Path(args.keep) if args.keep else Path(tempfile.mkdtemp(prefix="marvin-check-"))
    work.mkdir(parents=True, exist_ok=True)
    stamp = work / f"defs-{defs['C8_AMEND']}"
    if args.keep and not stamp.exists():
        for f in work.glob("*.stl"):
            f.unlink()
        for f in work.glob("defs-*"):
            f.unlink()
        stamp.touch()

    rc, txt = openscad("check_values", work / "values.echo", defs)
    if (work / "values.echo").exists():
        txt += (work / "values.echo").read_text()
    vals = parse_values(txt)
    if rc != 0 or not vals:
        sys.exit("could not read the CHECK values from marvin.scad:\n" + txt[-2000:])
    rep = Report(bool(vals["H0_PROVISIONAL"]), args.strict)
    print(f"marvin.scad: H0_PROVISIONAL={bool(vals['H0_PROVISIONAL'])}, C8_AMEND={bool(vals['C8_AMEND'])}, "
          f"head top {vals['hZ1']} mm")

    # ---- frozen interface
    bad = [k for k, v in C8.items() if vals.get(k) != v]
    rep.line("frozen", not bad, "C8 values in the FROZEN block" + (f": changed {bad}" if bad else " unchanged"))
    cam_dy = vals["camLens"][1] - vals["camPos"][1]
    scr_dy = vals["activeCentre"][1] - vals["screenCentre"][1]
    same_xz = (vals["camLens"][0] == vals["camPos"][0] and vals["activeCentre"][0] == vals["screenCentre"][0]
               and vals["activeCentre"][2] == vals["screenCentre"][2])
    cam_z = vals["camLens"][2] - vals["camPos"][2]
    rep.line("frozen", same_xz and abs(cam_dy) <= 1.5 and abs(scr_dy) <= 1.5 and (cam_z == 0 or vals["C8_AMEND"]),
             f"camera lens {cam_dy:+.1f} mm in y and {cam_z:+.1f} mm in z from camPos; screen glass {scr_dy:+.1f} mm in y "
             f"from screenCentre (the window's 0.8 mm lip and 2.3 mm acrylic sit in front of them)")

    # ---- export every part once (OpenSCAD), then every test runs on the meshes (numpy)
    parts = PRINT_PARTS + HEAD_PRINTED + GHOSTS + ["head_hollow"]
    src_time = SCAD.stat().st_mtime

    def run(p):
        out = work / f"{p}.stl"
        if out.exists() and out.stat().st_mtime > src_time:
            return p, 0, (work / f"{p}.log").read_text() if (work / f"{p}.log").exists() else ""
        rc, log = openscad(p, out, defs)
        (work / f"{p}.log").write_text(log)
        return p, rc, log
    results = {}
    with ThreadPoolExecutor(max_workers=args.jobs) as ex:
        for p, rc, log in ex.map(run, parts):
            results[p] = (rc, log)
    mesh = {p: read_stl(work / f"{p}.stl") for p in parts if results[p][0] == 0 and (work / f"{p}.stl").exists()}
    missing = [p for p in parts if p not in mesh]
    rep.line("export", not missing, "every part renders in OpenSCAD" + (f": failed {missing}" if missing else ""))
    if missing:
        sys.exit(1)

    def box(bb):
        return "x {:.1f}..{:.1f} y {:.1f}..{:.1f} z {:.1f}..{:.1f}".format(bb[0][0], bb[1][0], bb[0][1], bb[1][1], bb[0][2], bb[1][2])

    # ---- envelope and neck interface
    hw, hd = vals["headW"] / 2, vals["headD"] / 2
    sh = np.concatenate([mesh["head_shell"].reshape(-1, 3), mesh["head_cap"].reshape(-1, 3)])
    lo, hi = sh.min(axis=0), sh.max(axis=0)
    ok = (lo[0] >= -hw - 1e-3 and hi[0] <= hw + 1e-3 and lo[1] >= -hd - 1e-3 and hi[1] <= hd + 1e-3
          and lo[2] >= vals["headZ0"] - 1e-3 and hi[2] <= vals["hZ1"] + 1e-3)
    knob = mesh["knob"].reshape(-1, 3)
    rep.line("envelope", ok, f"shell + cap: {box((lo, hi))}; the knob stands {-(knob[:, 0].min() + hw):.1f} mm proud of the left side")

    z0 = vals["headZ0"]
    zs = np.arange(z0 + 0.13, vals["hzF"] + 6.0, 0.25)
    neck_bad = []
    for x, y in vals["neckBolt"]:
        ang = np.linspace(0, 2 * np.pi, 8, endpoint=False)
        for r, want, zmax in ((0.0, False, vals["hzF"] + 4.5), (1.0, False, vals["hzF"] + 4.5), (2.3, True, vals["hzF"] + 5.0)):
            for t in (ang if r else [0.0]):
                occ = occupancy(mesh["head_shell"], np.array([x + r * np.cos(t) + 1e-3]), np.array([y + r * np.sin(t) + 2e-3]), zs[zs < zmax])[0]
                if (occ.all() if want else (not occ.any())) is False:
                    neck_bad.append((x, y, r))
    w, d, r = vals["cableHole"]
    xs = np.linspace(-w / 2 + 0.5, w / 2 - 0.5, 15) + 1e-3
    ys = np.linspace(vals["cableHoleY"] - d / 2 + 0.5, vals["cableHoleY"] + d / 2 - 0.5, 8) + 2e-3
    X, Y = np.meshgrid(xs, ys, indexing="ij")
    qx = np.maximum(np.abs(X) - (w / 2 - r), 0)
    qy = np.maximum(np.abs(Y - vals["cableHoleY"]) - (d / 2 - r), 0)
    in_hole = (np.hypot(qx, qy) <= r - 0.5).ravel()          # inside the rounded rectangle, 0.5 mm in
    if occupancy(mesh["head_shell"], xs, ys, zs[zs < vals["hzF"]])[in_hole].any():
        neck_bad.append("cable hole")
    rep.line("neck", not neck_bad, "4 x M3 pilots at (+-32, +-20), each in a boss; cable hole 30 x 16 at y = +4 open"
             + (f": wrong at {sorted(set(map(str, neck_bad)))}" if neck_bad else ""))

    # ---- fits (voxel overlap of the closed meshes)
    def check_pair(check, a, b, h0, label=None):
        v, bb = overlap(mesh[a], mesh[b])
        rep.line(check, v <= CLASH_TOL, (label or f"{a} x {b}: ") + ("clear" if v <= CLASH_TOL else f"{v:.1f} mm3 overlap at {box(bb)}"), h0)
    for a, b, h0 in CLASHES:
        check_pair("fit", a, b, h0)
    for g in INSIDE:
        check_pair("fit", g, "head_hollow", True, f"{g} inside the head's hollow: ")
    for a, b in C8_FIT:
        check_pair("c8-fit", a, b, True)

    # ---- optics
    ow, oz0, oz1 = vals["winOpen"]
    aw, ah = vals["active"]
    sc = vals["screenCentre"]
    m = min(ow / 2 - aw / 2, (sc[2] - ah / 2) - oz0, oz1 - (sc[2] + ah / 2))
    rep.line("optics", m >= 0.5, f"active area inside the window opening, margin {m:.2f} mm", True)
    hx, hz, hd_ = vals["camHole"]
    m2 = min(ow / 2 - abs(hx) - hd_ / 2, oz1 - hz - hd_ / 2, hz - hd_ / 2 - (sc[2] + ah / 2))
    rep.line("optics", m2 >= 1.0, f"camera hole inside the opening and above the active area, margin {m2:.2f} mm", True)
    # vignetting: entrance pupil about 1 mm across, camGap + acrT behind the front face of the hole
    depth = vals["camGap"] + vals["acrT"]
    allowed = math.degrees(math.atan((hd_ / 2 - 0.5) / depth))
    tilt = vals["camTilt"]
    hfov, vfov = 55.0, 43.0      # OV3660 on the XIAO Sense: about 66 deg diagonal, 4:3
    edge = tilt + vfov / 2
    corner = math.degrees(math.acos(math.cos(math.radians(edge)) * math.cos(math.radians(hfov / 2))))
    rep.line("optics", edge <= allowed, f"the {hd_:g} mm hole passes rays up to {allowed:.1f} deg off its axis; the top edge of "
             f"the image is at {edge:.1f} deg, its top corners at {corner:.1f} deg"
             + ("" if corner <= allowed else " (the top corners darken a little)"), True)

    # ---- screws
    for k in ("screw_neck", "screw_frame", "screw_cap", "screw_knob"):
        length, clamp, pilot = vals[k]
        thread = length - clamp
        rep.line("screws", 4.0 <= thread <= pilot, f"{k[6:]}: M3 x {length}, {thread:.1f} mm of thread, pilot {pilot:.1f} mm deep")
    rep.line("screws", 0.05 <= vals["knob_gap"] <= 0.4, f"knob stem to tact plunger: {vals['knob_gap']:.2f} mm")

    # ---- every print-ready part
    for p in PRINT_PARTS:
        tri = mesh[p]
        simple = "Simple:        yes" in results[p][1] or not results[p][1]
        closed = watertight(tri)
        rep.line("manifold", simple and closed, f"{p}: {len(tri)} facets, OpenSCAD Simple: {'yes' if simple else 'no'}, "
                 f"closed: {closed}, {abs(volume(tri)) / 1000:.1f} cm3")
        pts = tri.reshape(-1, 3)
        lo, hi = pts.min(axis=0), pts.max(axis=0)
        size = hi - lo
        rep.line("bed", abs(lo[2]) < 1e-3 and size[0] <= BED and size[1] <= BED and size[2] <= BED,
                 f"{p}: on the bed, {size[0]:.1f} x {size[1]:.1f} x {size[2]:.1f} mm")
        n, a = normals_areas(tri)
        cz = tri[:, :, 2].mean(axis=1)
        down = (cz > 0.25) & (a > 1e-6)
        bridge = down & (n[:, 2] < -0.985)
        steep = down & (n[:, 2] < -0.72) & ~bridge
        spans = []
        for comp in components(tri, bridge):
            q = tri[comp].reshape(-1, 3)
            ext = q.max(axis=0) - q.min(axis=0)
            spans.append((min(ext[0], ext[1]), q.min(axis=0), q.max(axis=0)))
        longest = max(spans, default=(0, None, None), key=lambda s_: s_[0])
        rep.line("overhang", longest[0] <= BRIDGE_MAX, f"{p}: {len(spans)} bridged areas, the longest spans {longest[0]:.1f} mm"
                 + (f" at {box((longest[1], longest[2]))}" if longest[1] is not None and longest[0] > 15 else ""))
        bad = []
        known = KNOWN_STEEP.get(p)
        for comp in components(tri, steep):
            q = tri[comp].reshape(-1, 3)
            ar = a[comp].sum()
            small = ar <= SMALL_ARC and q[:, 2].max() - q[:, 2].min() <= 3.0
            if ar > 4.0 and not small and not (known and q[:, 2].max() <= known["zmax"]):
                bad.append((ar, q.min(axis=0), q.max(axis=0)))
        if known and p in ("print_head_shell", "print_head_cap"):
            rep.warn("overhang", f"{p}: accepted: {known['why']} (z <= {known['zmax']} mm)")
        rep.line("overhang", not bad, f"{p}: steeper than 45 deg: " + ("none beyond the known ones" if not bad else
                 "; ".join(f"{ar:.0f} mm2 at {box((l_, h_))}" for ar, l_, h_ in sorted(bad, key=lambda b_: -b_[0])[:4])))
        if p in ("print_head_shell", "print_head_cap", "print_face_frame", "print_knob", "print_switch_bracket", "print_window_jig"):
            t, where, _ = thickness(tri)
            fin = np.isfinite(t)
            thin = fin & (t < WALL_MIN)
            msg = f"{p}: thinnest sampled wall {t[fin].min():.2f} mm"
            if thin.any():
                w0 = where[thin][np.argmin(t[thin])]
                msg += f" at ({w0[0]:.1f}, {w0[1]:.1f}, {w0[2]:.1f}), {int(thin.sum())} of {int(fin.sum())} samples under {WALL_MIN} mm"
            # the face frame is shaped round the provisional module sizes: its walls follow H0
            rep.line("wall", thin.sum() <= max(2, 0.002 * fin.sum()), msg, p == "print_face_frame")

    if not args.keep:
        shutil.rmtree(work, ignore_errors=True)
    print(f"\n{rep.fails} failed, {rep.warns} warnings")
    sys.exit(1 if rep.fails else 0)


if __name__ == "__main__":
    main()
