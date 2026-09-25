"""Build hardware/blender/fanou.blend (and optional renders) from the OpenSCAD parts.

Usage (Blender 4.2+):
    blender --background --python hardware/blender/build_blend.py -- <stl_dir> <out.blend> [render_dir]
or with the `bpy` wheel:
    python3 hardware/blender/build_blend.py <stl_dir> <out.blend> [render_dir]

<stl_dir> must contain base.stl, bottom.stl, shell.stl, spine.stl, cap.stl exported in the
ASSEMBLY frame, e.g.  openscad -D 'PART="shell"' -o shell.stl hardware/cad/fanou.scad

SPDX-License-Identifier: MIT
"""
import math
import sys
from pathlib import Path

import bpy
import bmesh  # noqa: E402  (must come after bpy with the pip wheel)
from mathutils import Matrix, Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
stl_dir, out = Path(argv[0]), Path(argv[1])
render_dir = Path(argv[2]) if len(argv) > 2 else None

# ---- parameters mirrored from fanou.scad (mm) ----
zb, zt, Rb, Rt, ws, gap = 22, 170, 52, 40, 1.6, 0.8
k = (Rb - Rt) / (zt - zb)
a = math.atan(k)
y0 = -(Rb - ws - gap)
galT, plinthH = 4, 6
lcdW, lcdH, lcdU, lcdS = 31.5, 39.0, 81, 3.6
xW, xH, xU, xS = 21, 17.8, 122, 1.6
# colour bands of the tower (z ranges), bottom to top
BANDS = [(22, 48, "red"), (48, 80, "white"), (80, 104, "red"), (104, 136, "white"), (136, 171, "red")]


def r(z):
    return Rb - k * (z - zb)


def F(x, w, u):
    """Face frame -> world."""
    return Vector((x, y0 + w * math.cos(a) + u * math.sin(a), zb - w * math.sin(a) + u * math.cos(a)))


bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.unit_settings.system = "METRIC"
scene.unit_settings.scale_length = 0.001
scene.unit_settings.length_unit = "MILLIMETERS"


def material(name, rgb, rough=0.5, metal=0.0, emit=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if emit:
        b.inputs["Emission Color"].default_value = (*rgb, 1)
        b.inputs["Emission Strength"].default_value = emit
    m.diffuse_color = (*rgb, 1)
    return m


M = {
    "red": material("Rouge phare", (0.72, 0.10, 0.08), 0.45),
    "white": material("Blanc phare", (0.93, 0.91, 0.86), 0.45),
    "navy": material("Bleu marine", (0.05, 0.11, 0.22), 0.5),
    "brass": material("Laiton", (0.80, 0.60, 0.28), 0.3, 0.8),
    "rock": material("Rocher", (0.33, 0.37, 0.40), 0.8),
    "spine": material("Squelette", (0.80, 0.70, 0.52), 0.6),
    "lidar": material("Lidar", (0.02, 0.02, 0.025), 0.35),
    "lcd": material("Ecran", (0.01, 0.025, 0.04), 0.1),
    "eye": material("Yeux", (0.72, 0.96, 1.0), 0.3, emit=3.5),
    "hl": material("Reflet", (1, 1, 1), 0.2, emit=6),
    "lens": material("Objectif", (0.01, 0.01, 0.02), 0.05, 0.4),
}


def collection(name, parent=None):
    c = bpy.data.collections.new(name)
    (parent or scene.collection).children.link(c)
    return c


def link(ob, coll):
    for c in ob.users_collection:
        c.objects.unlink(ob)
    coll.objects.link(ob)


def import_stl(name, mats, coll):
    bpy.ops.wm.stl_import(filepath=str(stl_dir / f"{name}.stl"))
    ob = bpy.context.selected_objects[0]
    ob.name = ob.data.name = name.capitalize()
    for m in mats:
        ob.data.materials.append(m)
    ob.data.polygons.foreach_set("use_smooth", [False] * len(ob.data.polygons))
    link(ob, coll)
    return ob


asm = collection("Fanou")
parts = {
    "base": import_stl("base", [M["rock"]], asm),
    "bottom": import_stl("bottom", [M["rock"]], asm),
    "spine": import_stl("spine", [M["spine"]], asm),
    "cap": import_stl("cap", [M["navy"]], asm),
    "shell": import_stl("shell", [M["white"], M["red"], M["navy"], M["brass"]], asm),
}
# paint the shell: cut the mesh at the band heights, then colour bands by height
# and reliefs (any vertex outside the cone, front half) in navy / brass
sh = parts["shell"].data
bm = bmesh.new()
bm.from_mesh(sh)
for z in [b[1] for b in BANDS[:-1]]:
    geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
    bmesh.ops.bisect_plane(bm, geom=geom, plane_co=(0, 0, z), plane_no=(0, 0, 1))
bmesh.ops.triangulate(bm, faces=bm.faces[:])
bm.to_mesh(sh)
bm.free()
idx = {"white": 0, "red": 1, "navy": 2, "brass": 3}
for p in sh.polygons:
    c = p.center
    band = next((b[2] for b in BANDS if b[0] <= c.z < b[1]), "red")
    mi = idx[band]
    vs = [sh.vertices[i].co for i in p.vertices]
    off = max(math.hypot(v.x, v.y) - r(v.z) for v in vs)
    if off > 0.2 and c.y < -10 and abs(c.x) < 30:
        mi = idx["brass"] if math.hypot(c.x, c.z - F(0, xS, xU + xH / 2).z) < 10.5 else idx["navy"]
    p.material_index = mi

# print layout: the print-ready STLs from hardware/stl, in a row beside the model (not rendered)
print_dir = Path(__file__).resolve().parent.parent / "stl"
if print_dir.is_dir():
    lay = collection("Plateau d'impression")
    x = 250.0
    for f in sorted(print_dir.glob("*.stl")):
        bpy.ops.wm.stl_import(filepath=str(f))
        ob = bpy.context.selected_objects[0]
        ob.name = f.stem
        ob.data.materials.append(M["spine"])
        link(ob, lay)
        w = ob.dimensions.x
        ob.location.x = x + w / 2 - (ob.bound_box[0][0] + ob.bound_box[6][0]) / 2
        x += w + 20
    lay.hide_render = True

# face: screen with two friendly eyes, seen through the window
face = collection("Visage", asm)
n = (F(0, 0, 1) - F(0, 0, 0)).normalized()          # up along the face
fwd = (F(0, -1, 0) - F(0, 0, 0)).normalized()       # out of the face
ctr = F(0, lcdS - 0.01, lcdU + lcdH / 2)
rot = Matrix.Rotation(-a, 4, "X")


def disc(name, radius, sx, sy, center, mat, depth_off):
    bpy.ops.mesh.primitive_cylinder_add(vertices=48, radius=radius, depth=0.4)
    ob = bpy.context.active_object
    ob.name = name
    ob.scale = (sx, 1, sy)
    ob.rotation_euler = (math.pi / 2 - a, 0, 0)
    ob.location = center + fwd * depth_off
    ob.data.materials.append(mat)
    link(ob, face)
    return ob


bpy.ops.mesh.primitive_cube_add(size=1)
scr = bpy.context.active_object
scr.name = "Ecran"
scr.scale = (lcdW, 1.0, lcdH)
scr.rotation_euler = (-a, 0, 0)
scr.location = ctr + fwd * -0.5
scr.data.materials.append(M["lcd"])
link(scr, face)
for sx in (-1, 1):
    c = ctr + Vector((sx * 7.0, 0, 0)) + n * 2
    disc(f"Oeil {sx}", 4.6, 1, 1.35, c, M["eye"], 0.3)
    disc(f"Reflet {sx}", 1.3, 1, 1, c + Vector((1.6, 0, 0)) + n * 2.6, M["hl"], 0.5)
lens_c = F(0, xS - 0.3, xU + xH / 2)
disc("Objectif", 3.4, 1, 1, lens_c, M["lens"], 0)

# lidar lantern
lid = collection("Lidar", asm)
bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, zt + galT + plinthH + 11.1))
ob = bpy.context.active_object
ob.name = "Lidar socle"
ob.scale = (38.59, 38.59, 22.2)
ob.data.materials.append(M["lidar"])
link(ob, lid)
bpy.ops.mesh.primitive_cylinder_add(vertices=96, radius=17.6, depth=12.6, location=(0, 0, zt + galT + plinthH + 22.2 + 6.3))
ob = bpy.context.active_object
ob.name = "Lidar tete"
ob.data.materials.append(M["lidar"])
link(ob, lid)

# light, camera, shadow catcher
def aim(ob, target):
    ob.rotation_euler = (Vector(target) - ob.location).to_track_quat("-Z", "Y").to_euler()


for name, loc, energy, size in (("Cle", (-420, -520, 650), 2.4e6, 420), ("Contre", (520, 420, 480), 1.3e6, 320)):
    L = bpy.data.lights.new(name, "AREA")
    L.energy, L.size = energy, size
    o = bpy.data.objects.new(name, L)
    o.location = loc
    aim(o, (0, 0, 100))
    scene.collection.objects.link(o)
cam = bpy.data.objects.new("Camera", bpy.data.cameras.new("Camera"))
cam.data.lens, cam.data.clip_end = 60, 5000
cam.location = (-300, -560, 250)
aim(cam, (0, 0, 105))
scene.collection.objects.link(cam)
scene.camera = cam
bpy.ops.mesh.primitive_plane_add(size=2000, location=(0, 0, 0))
g = bpy.context.active_object
g.name = "Sol"
g.is_shadow_catcher = True
world = bpy.data.worlds.new("Monde")
world.use_nodes = True
world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.95, 0.95, 0.97, 1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.7
scene.world = world

out.parent.mkdir(parents=True, exist_ok=True)
bpy.ops.wm.save_as_mainfile(filepath=str(out), compress=True)
print("saved", out)



def clean_shadow(path):
    """Keep the contact shadow, drop the faint grey haze the shadow catcher leaves on the whole floor."""
    try:
        import numpy as np
        from PIL import Image
    except ImportError:
        return
    a = np.array(Image.open(path)).astype(np.float32)
    shadow = a[..., :3].max(axis=2) < 40
    a[..., 3] = np.where(shadow, np.clip((a[..., 3] - 45) * 1.8, 0, 255), a[..., 3])
    Image.fromarray(a.astype(np.uint8)).save(path, optimize=True)


if render_dir:
    render_dir.mkdir(parents=True, exist_ok=True)
    scene.render.engine = "CYCLES"
    scene.cycles.samples = int(__import__("os").environ.get("SAMPLES", 48))
    scene.cycles.device = "CPU"
    scene.cycles.use_denoising = True
    scene.render.film_transparent = True
    scene.render.resolution_x, scene.render.resolution_y = 1000, 1300
    scene.view_settings.view_transform = "AgX"
    shots = {
        "fanou_front": (-300, -560, 250),
        "fanou_face": (-120, -330, 190),
        "fanou_back": (380, 520, 300),
    }
    only = __import__("os").environ.get("ONLY")
    for name, loc in shots.items():
        if only and name not in only.split(","):
            continue
        cam.location = loc
        aim(cam, (0, 0, 130) if name == "fanou_face" else (0, 0, 110))
        cam.data.lens = 80 if name == "fanou_face" else 88
        scene.render.filepath = str(render_dir / f"{name}.png")
        bpy.ops.render.render(write_still=True)
        clean_shadow(scene.render.filepath)
    # exploded view
    if only and "fanou_exploded" not in only.split(","):
        sys.exit(0)
    cam.location = (-420, -700, 330)
    aim(cam, (0, 0, 160))
    cam.data.lens = 54
    offsets = {"bottom": -30, "shell": 90, "cap": 150}
    for key, dz in offsets.items():
        parts[key].location.z += dz
    for o in face.objects:
        o.location.z += 90
    for o in lid.objects:
        o.location.z += 185
    scene.render.filepath = str(render_dir / "fanou_exploded.png")
    bpy.ops.render.render(write_still=True)
    clean_shadow(scene.render.filepath)
    print("rendered")
