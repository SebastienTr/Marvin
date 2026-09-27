"""Build hardware/blender/marvin.blend (and optional renders) from the OpenSCAD parts.

Usage (Blender 4.2+):
    blender --background --python hardware/blender/build_blend.py -- <asm_stl_dir> <out.blend> [render_dir]
or with the `bpy` wheel:
    python3 hardware/blender/build_blend.py <asm_stl_dir> <out.blend> [render_dir]

<asm_stl_dir> holds the parts exported in the ASSEMBLY frame (not the print-ready STLs):
    for p in body plinth sled neck grommet foot ghost_mr60 ghost_ld2450 ghost_speaker; do
        openscad -D "PART=\\"$p\\"" -o <asm_stl_dir>/$p.stl hardware/cad/marvin.scad
    done
hardware/scripts/build_blend.sh does all of this.

The head is not in the CAD yet: the "Head (concept)" collection is a stand-in with the
planned outer shape, face window, camera, status LED, knob and lidar, so the model reads
as the whole robot. Replace it when the head CAD lands.

SPDX-License-Identifier: MIT
"""
import math
import os
import sys
from pathlib import Path

import bpy
import bmesh  # noqa: E402  (must come after bpy with the pip wheel)
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
asm_dir, out = Path(argv[0]), Path(argv[1])
render_dir = Path(argv[2]) if len(argv) > 2 else None
repo = Path(__file__).resolve().parents[2]

# ---- parameters mirrored from marvin.scad (mm) ----
BODY_Z1, DECK, BOSS_T, GROM_FLANGE_T = 64, 2.0, 1.2, 1.6
BAND = (20, 50)                                  # anthracite band on the body
NECK_BOLTS = [(-32, -20), (32, -20), (-32, 20), (32, 20)]
PLINTH_W, PLINTH_D = 68, 62
HEAD_W, HEAD_D, HEAD_R, HEAD_Z0, HEAD_Z1 = 88, 80, 16, 68, 124
# A1 / A1 mini bed and Bambu Studio plate spacing, for the print layout
BED, PLATE_STRIDE = 180, 180 * 1.2

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.unit_settings.system = "METRIC"
scene.unit_settings.scale_length = 0.001
scene.unit_settings.length_unit = "MILLIMETERS"


def material(name, rgb, rough=0.5, metal=0.0, emit=0.0, alpha=1.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if emit:
        b.inputs["Emission Color"].default_value = (*rgb, 1)
        b.inputs["Emission Strength"].default_value = emit
    if alpha < 1:
        b.inputs["Alpha"].default_value = alpha
        m.blend_method = "BLEND"
    m.diffuse_color = (*rgb, alpha)
    return m


M = {
    "grey": material("Warm grey PETG", (0.80, 0.79, 0.76), 0.5),
    "anth": material("Anthracite PETG", (0.10, 0.10, 0.11), 0.55),
    "tpu": material("Black TPU", (0.03, 0.03, 0.03), 0.8),
    "orange": material("Orange knob", (0.85, 0.27, 0.06), 0.4),
    "sled": material("Sled PETG", (0.80, 0.79, 0.76), 0.6),
    "mr60": material("MR60BHA2 case", (0.22, 0.22, 0.24), 0.5),
    "ld": material("LD2450 board", (0.08, 0.35, 0.18), 0.4),
    "spk": material("Speaker", (0.75, 0.60, 0.25), 0.4, 0.6),
    "glass": material("Smoked acrylic", (0.006, 0.007, 0.009), 0.12),
    "eye": material("Eyes", (0.72, 0.93, 1.0), 0.3, emit=4.0),
    "led": material("Status LED", (1.0, 0.45, 0.1), 0.3, emit=6.0),
    "lens": material("Lens", (0.02, 0.02, 0.03), 0.05, 0.3),
    "lidar": material("Lidar", (0.03, 0.03, 0.035), 0.35),
    "plate": material("Print preview", (0.85, 0.55, 0.30), 0.6),
}


def collection(name, parent=None):
    c = bpy.data.collections.new(name)
    (parent or scene.collection).children.link(c)
    return c


def link(ob, coll):
    for c in ob.users_collection:
        c.objects.unlink(ob)
    coll.objects.link(ob)


def import_stl(path, name, mats, coll, loc=(0, 0, 0)):
    bpy.ops.wm.stl_import(filepath=str(path))
    ob = bpy.context.selected_objects[0]
    ob.name = ob.data.name = name
    for m in mats:
        ob.data.materials.append(m)
    ob.data.polygons.foreach_set("use_smooth", [False] * len(ob.data.polygons))   # CAD parts: flat shading
    ob.location = loc
    link(ob, coll)
    return ob


def squircle(name, w, d, r, z0, h, mat, coll, bevel=1.5, y=0.0):
    """Rounded-rectangle block (plan view), extruded up from z0."""
    pts = []
    for cx, cy, a0 in ((w / 2 - r, d / 2 - r, 0), (-w / 2 + r, d / 2 - r, 90), (-w / 2 + r, -d / 2 + r, 180), (w / 2 - r, -d / 2 + r, 270)):
        for i in range(13):
            a = math.radians(a0 + 90 * i / 12)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a) + y))
    bm = bmesh.new()
    f = bm.faces.new([bm.verts.new((x, yy, z0)) for x, yy in pts])
    res = bmesh.ops.extrude_face_region(bm, geom=[f])
    for v in [e for e in res["geom"] if isinstance(e, bmesh.types.BMVert)]:
        v.co.z += h
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    coll.objects.link(ob)
    ob.data.materials.append(mat)
    if bevel:
        md = ob.modifiers.new("Bevel", "BEVEL")
        md.width, md.segments, md.limit_method = bevel, 4, "ANGLE"
    return ob


def cylinder(name, r, depth, loc, mat, coll, rot=(0, 0, 0), verts=64):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=r, depth=depth, location=loc, rotation=rot)
    ob = bpy.context.active_object
    ob.name = name
    ob.data.materials.append(mat)
    link(ob, coll)
    return ob


# ------------------------------------------------------------------ body half (from the CAD)
robot = collection("Marvin")
body_half = collection("Body (CAD)", robot)
parts = {
    "plinth": import_stl(asm_dir / "plinth.stl", "Plinth", [M["anth"]], body_half),
    "body": import_stl(asm_dir / "body.stl", "Body", [M["grey"], M["anth"]], body_half),
    "sled": import_stl(asm_dir / "sled.stl", "Sensor sled", [M["sled"]], body_half),
    "neck": import_stl(asm_dir / "neck.stl", "Neck gasket (TPU)", [M["tpu"]], body_half),
}
for i, (x, y) in enumerate(NECK_BOLTS):
    import_stl(asm_dir / "grommet.stl", f"Grommet {i + 1} (TPU)", [M["tpu"]], body_half,
               loc=(x, y, BODY_Z1 - DECK - BOSS_T - GROM_FLANGE_T))
for i, (sx, sy) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
    import_stl(asm_dir / "foot.stl", f"Foot {i + 1} (TPU)", [M["tpu"]], body_half,
               loc=(sx * (PLINTH_W / 2 - 11), sy * (PLINTH_D / 2 - 11), -1.0))

# paint the body: cut the mesh at the band heights, anthracite between them
me = parts["body"].data
bm = bmesh.new()
bm.from_mesh(me)
for z in BAND:
    geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
    bmesh.ops.bisect_plane(bm, geom=geom, plane_co=(0, 0, z), plane_no=(0, 0, 1))
bmesh.ops.triangulate(bm, faces=bm.faces[:])
bm.to_mesh(me)
bm.free()
for p in me.polygons:
    p.material_index = 1 if BAND[0] <= p.center.z <= BAND[1] else 0

modules = collection("Modules", robot)
import_stl(asm_dir / "ghost_mr60.stl", "MR60BHA2 kit (60 GHz)", [M["mr60"]], modules)
import_stl(asm_dir / "ghost_ld2450.stl", "HLK-LD2450 (24 GHz)", [M["ld"]], modules)
import_stl(asm_dir / "ghost_speaker.stl", "Speaker 2030", [M["spk"]], modules)

# ------------------------------------------------------------------ head (concept stand-in)
head = collection("Head (concept)", robot)
squircle("Head shell", HEAD_W, HEAD_D, HEAD_R, HEAD_Z0, HEAD_Z1 - HEAD_Z0, M["grey"], head, bevel=4)
yf = -HEAD_D / 2
win = squircle("Face window (2.3 mm acrylic)", HEAD_W - 14, HEAD_Z1 - HEAD_Z0 - 12, 10, 0, 1.2, M["glass"], head, bevel=0.3)
win.rotation_euler = (math.pi / 2, 0, 0)           # the plate stands up, its thickness points at the user
win.location = (0, yf + 0.2, (HEAD_Z0 + HEAD_Z1) / 2)
for sx in (-1, 1):
    bpy.ops.mesh.primitive_cylinder_add(vertices=32, radius=4.5, depth=0.4, location=(sx * 11, yf - 1.35, 92), rotation=(math.pi / 2, 0, 0))
    e = bpy.context.active_object
    e.name = f"Eye {'left' if sx < 0 else 'right'}"
    e.scale = (1, 1.7, 1)
    e.data.materials.append(M["eye"])
    link(e, head)
cylinder("Camera lens", 2.4, 0.6, (0, yf - 1.4, 113), M["lens"], head, rot=(math.pi / 2, 0, 0))
cylinder("Status LED", 0.9, 0.6, (30, yf - 1.4, 113), M["led"], head, rot=(math.pi / 2, 0, 0))
cylinder("Knob", 10, 7, (-HEAD_W / 2 - 3.5, 6, 96), M["orange"], head, rot=(0, math.pi / 2, 0))
cylinder("Lidar ring", 28, 4, (0, 0, HEAD_Z1 + 2), M["anth"], head)
cylinder("Lidar turret", 17.6, 12.6, (0, 0, HEAD_Z1 + 4 + 6.3), M["lidar"], head)

# ------------------------------------------------------------------ print layout (not rendered)
lay = collection("Print plates (hardware/stl)")
stl_dir = repo / "hardware" / "stl"
for i, f in enumerate(sorted(stl_dir.glob("*.stl"))):
    ox = 300 + i * PLATE_STRIDE
    bpy.ops.mesh.primitive_plane_add(size=BED, location=(ox + BED / 2, BED / 2, -0.05))
    bed = bpy.context.active_object
    bed.name = f"Bed {i + 1}"
    bed.display_type = "WIRE"
    link(bed, lay)
    ob = import_stl(f, f.stem, [M["plate"]], lay)
    bb = [Vector(c) for c in ob.bound_box]
    cx = (min(v.x for v in bb) + max(v.x for v in bb)) / 2
    cy = (min(v.y for v in bb) + max(v.y for v in bb)) / 2
    ob.location = (ox + BED / 2 - cx, BED / 2 - cy, 0)
lay.hide_render = True
# hidden in the viewport by default: tick the eye of "Print plates" in the outliner to see them
bpy.context.view_layer.layer_collection.children[lay.name].hide_viewport = True

# ------------------------------------------------------------------ light, camera, floor
def aim(ob, target):
    ob.rotation_euler = (Vector(target) - ob.location).to_track_quat("-Z", "Y").to_euler()


for name, loc, energy, size in (("Key", (-380, -460, 520), 1.6e6, 420), ("Fill", (420, -380, 260), 0.55e6, 500), ("Rim", (260, 520, 520), 1.4e6, 300)):
    L = bpy.data.lights.new(name, "AREA")
    L.energy, L.size = energy, size
    o = bpy.data.objects.new(name, L)
    o.location = loc
    aim(o, (0, 0, 70))
    scene.collection.objects.link(o)
cam = bpy.data.objects.new("Camera", bpy.data.cameras.new("Camera"))
cam.data.lens, cam.data.clip_end = 95, 5000
cam.location = (-330, -560, 250)
aim(cam, (0, 0, 70))
scene.collection.objects.link(cam)
scene.camera = cam
bpy.ops.mesh.primitive_plane_add(size=2000, location=(0, 0, -1.0))
g = bpy.context.active_object
g.name = "Floor"
g.is_shadow_catcher = True
world = bpy.data.worlds.new("World")
world.use_nodes = True
world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.95, 0.95, 0.97, 1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.7
scene.world = world

# the scene is in millimetres: give the viewport a matching depth range, otherwise the
# default 0.01-1000 clipping causes z-fighting stripes in Solid and Material Preview
cam.data.clip_start = 1.0
for screen in bpy.data.screens:
    for area in screen.areas:
        for space in area.spaces:
            if space.type == "VIEW_3D":
                space.clip_start, space.clip_end = 1.0, 20000.0
                space.overlay.show_floor = True

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
    scene.cycles.samples = int(os.environ.get("SAMPLES", 48))
    scene.cycles.device = "CPU"
    scene.cycles.use_denoising = True
    scene.render.film_transparent = True
    scene.render.resolution_x, scene.render.resolution_y = 1000, 1150
    scene.view_settings.view_transform = "AgX"
    for name, loc, target, lens in (("blender_threequarter", (-330, -560, 250), (0, 0, 70), 95),
                                     ("blender_front", (0, -700, 150), (0, 0, 70), 105)):
        cam.location = loc
        aim(cam, target)
        cam.data.lens = lens
        scene.render.filepath = str(render_dir / f"{name}.png")
        bpy.ops.render.render(write_still=True)
        clean_shadow(scene.render.filepath)
    print("rendered")
