"""Build hardware/blender/marvin.blend (and optional renders) from the OpenSCAD parts.

Usage (Blender 4.2+):
    blender --background --python hardware/blender/build_blend.py -- <asm_stl_dir> <out.blend> [render_dir]
or with the `bpy` wheel:
    python3 hardware/blender/build_blend.py <asm_stl_dir> <out.blend> [render_dir]

<asm_stl_dir> holds the parts exported in the ASSEMBLY frame (not the print-ready STLs), and
values.echo, the numbers the head is built on (PART="check_values"):
    for p in body plinth sled neck grommet foot ghost_mr60 ghost_ld2450 ghost_speaker \
             head_shell head_cap face_frame knob switch_bracket window ghost_lcd ghost_xiao ghost_usb ghost_tact; do
        openscad -D "PART=\\"$p\\"" -o <asm_stl_dir>/$p.stl hardware/cad/marvin.scad
    done
hardware/scripts/build_blend.sh does all of this.

The head is the CAD head: shell, back cap, face frame, knob and switch bracket, the acrylic window,
and the modules inside it (screen, XIAO, USB-C plug, tact switch). The screen shows Marvin's neutral
face, drawn by host/marvin_host/face.py (a plain stand-in if that cannot be imported).

SPDX-License-Identifier: MIT
"""
import math
import os
import sys
from pathlib import Path

import bpy
import bmesh
import numpy as np  # noqa: E402  (must come after bpy with the pip wheel)
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


def acrylic(name, tint):
    """Smoked acrylic: glass that lets a little light through, so the lit face shows and the rest stays black."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*tint, 1)
    b.inputs["Roughness"].default_value = 0.03
    b.inputs["IOR"].default_value = 1.49
    b.inputs["Transmission Weight"].default_value = 1.0
    m.diffuse_color = (*tint, 1)
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
    "pcb": material("Screen module (PCB, glass)", (0.02, 0.05, 0.10), 0.35),
    "xiao": material("XIAO (shield)", (0.62, 0.62, 0.64), 0.35, 0.8),
    "plate": material("Print preview", (0.85, 0.55, 0.30), 0.6),
    "acrylic": acrylic("Smoked acrylic", (0.045, 0.045, 0.05)),
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

# ------------------------------------------------------------------ head (from the CAD)
vals = {}
echo = asm_dir / "values.echo"
if echo.exists():
    import ast
    import re
    for k, v in re.findall(r'ECHO: "CHECK (\w+)=(.*)"', echo.read_text()):
        vals[k] = ast.literal_eval(v)
SCREEN = vals.get("screenCentre", [0, -38, 92])
ACTIVE = vals.get("active", [27.97, 32.63])
Y_GLASS = vals.get("activeCentre", [0, -36.9, 92])[1]

head = collection("Head (CAD)", robot)
head_parts = {
    "shell": import_stl(asm_dir / "head_shell.stl", "Head shell", [M["grey"]], head),
    "cap": import_stl(asm_dir / "head_cap.stl", "Head back cap", [M["grey"]], head),
    "frame": import_stl(asm_dir / "face_frame.stl", "Face frame", [M["anth"]], head),
    "window": import_stl(asm_dir / "window.stl", "Face window (2.3 mm smoked acrylic)", [M["acrylic"]], head),
    "knob": import_stl(asm_dir / "knob.stl", "Knob", [M["orange"]], head),
    "bracket": import_stl(asm_dir / "switch_bracket.stl", "Switch bracket", [M["anth"]], head),
}
head_modules = collection("Head modules", robot)
head_mods = {
    "lcd": import_stl(asm_dir / "ghost_lcd.stl", "1.69in ST7789 module", [M["pcb"]], head_modules),
    "xiao": import_stl(asm_dir / "ghost_xiao.stl", "XIAO ESP32S3 Sense", [M["xiao"]], head_modules),
    "usb": import_stl(asm_dir / "ghost_usb.stl", "USB-C plug, 90 deg", [M["tpu"]], head_modules),
    "tact": import_stl(asm_dir / "ghost_tact.stl", "Tact switch 6 x 6", [M["tpu"]], head_modules),
}


def face_image(path, w=240, h=280):
    """Marvin's neutral face at the screen's resolution, from the host's face renderer."""
    try:
        sys.path.insert(0, str(repo / "host"))
        from marvin_host import face as mface
        px = np.asarray(mface.render(mface.EXPRESSIONS["neutral"]), dtype=np.float32)[..., :3] / 255.0
    except Exception as e:                                   # noqa: BLE001 - any failure: plain stand-in
        print("face.py not available, drawing a stand-in:", e)
        px = np.full((h, w, 3), 20 / 255.0, np.float32)
        yy, xx = np.mgrid[0:h, 0:w]
        for cx in (68, 172):
            px[((xx - cx) / 32) ** 4 + ((yy - 146) / 41) ** 4 < 1] = 0.92
    img = bpy.data.images.new("Marvin face", w, h)
    img.colorspace_settings.name = "sRGB"
    rgba = np.ones((h, w, 4), np.float32)
    rgba[..., :3] = px[::-1]                                   # Blender images start at the bottom row
    img.pixels.foreach_set(rgba.ravel())
    img.filepath_raw = str(path)
    img.file_format = "PNG"
    return img


face_img = face_image(Path(bpy.app.tempdir or "/tmp") / "marvin_face.png")
mface_mat = bpy.data.materials.new("Screen (face)")
mface_mat.use_nodes = True
nt = mface_mat.node_tree
for n in list(nt.nodes):
    nt.nodes.remove(n)
tex = nt.nodes.new("ShaderNodeTexImage")
tex.image = face_img
tex.interpolation = "Closest"
emit = nt.nodes.new("ShaderNodeEmission")
emit.inputs["Strength"].default_value = float(os.environ.get("FACE_GLOW", 10.0))
out_node = nt.nodes.new("ShaderNodeOutputMaterial")
nt.links.new(tex.outputs["Color"], emit.inputs["Color"])
nt.links.new(emit.outputs["Emission"], out_node.inputs["Surface"])
bpy.ops.mesh.primitive_plane_add(size=1, location=(SCREEN[0], Y_GLASS - 0.01, SCREEN[2]), rotation=(math.pi / 2, 0, 0))
scr = bpy.context.active_object
scr.name = "Screen (active area, neutral face)"
scr.scale = (ACTIVE[0], ACTIVE[1], 1)
scr.data.materials.append(mface_mat)
link(scr, head_modules)

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


def explode(on):
    """Pull the head apart along its assembly directions (or put it back)."""
    k = 1.0 if on else 0.0
    moves = {head_parts["window"]: (0, -32, 0), head_parts["cap"]: (0, 150, 0), head_parts["knob"]: (-30, 0, 0),
             head_parts["bracket"]: (24, 0, 0), head_mods["tact"]: (24, 0, 0),
             head_parts["frame"]: (0, 82, 0), head_mods["lcd"]: (0, 68, 0), scr: (0, 68, 0),
             head_mods["xiao"]: (0, 98, 0), head_mods["usb"]: (0, 98, 0)}
    for ob, d in moves.items():
        base = tuple(ob.get("base", tuple(ob.location)))
        ob["base"] = base
        ob.location = (base[0] + k * d[0], base[1] + k * d[1], base[2] + k * d[2])
    for ob in body_half.objects:
        ob.hide_render = on
    for ob in modules.objects:
        ob.hide_render = on
    g.hide_render = on                          # no floor shadow under parts in the air


if render_dir:
    render_dir.mkdir(parents=True, exist_ok=True)
    scene.render.engine = "CYCLES"
    scene.cycles.samples = int(os.environ.get("SAMPLES", 64))
    scene.cycles.device = "CPU"
    scene.cycles.use_denoising = True
    scene.render.film_transparent = True
    scene.view_settings.view_transform = "AgX"
    # name, size, camera position, target, lens; the sizes match the images already used by the docs
    views = [("robot_threequarter", (640, 846), (-330, -560, 250), (0, 0, 64), 95),
             ("robot_front", (710, 845), (0, -640, 110), (0, 0, 64), 125),
             ("robot_side", (887, 845), (-640, 0, 110), (0, 0, 64), 125),
             ("robot_head_exploded", (1200, 760), (-560, -170, 330), (0, 52, 92), 58)]
    only = os.environ.get("VIEWS")
    for name, (w, h), loc, target, lens in views:
        if only and name not in only.split(","):
            continue
        explode(name.endswith("exploded"))
        scene.render.resolution_x, scene.render.resolution_y = w, h
        cam.location = loc
        aim(cam, target)
        cam.data.lens = lens
        scene.render.filepath = str(render_dir / f"{name}.png")
        bpy.ops.render.render(write_still=True)
        clean_shadow(scene.render.filepath)
    explode(False)
    print("rendered")
