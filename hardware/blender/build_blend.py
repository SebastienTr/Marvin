"""Build hardware/blender/superlens.blend from the exported STLs.

Usage (Blender 4.2+):
    blender --background --python hardware/blender/build_blend.py -- <stl_dir> <out.blend>
or with the `bpy` wheel:
    python3 hardware/blender/build_blend.py <stl_dir> <out.blend>

<stl_dir> must contain head.stl, cover.stl, grip.stl, dock.stl, template.stl
exported in the ASSEMBLY frame (openscad -D 'PART="head"' ...).

SPDX-License-Identifier: MIT
"""
import math
import sys
from pathlib import Path

import bpy
from mathutils import Matrix, Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
stl_dir, out = Path(argv[0]), Path(argv[1])

# ---- CAD parameters mirrored from superlens.scad (mm) ----
t, clr = 2.4, 0.4
W, D, H = 76, 62, 84
kitX0, kitZ0 = -W / 2 + t + 1, t + 1
ldCz, xCz, lidY = 48.5, 67, 31

# ---- fresh scene, millimetres ----
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.unit_settings.system = "METRIC"
scene.unit_settings.scale_length = 0.001
scene.unit_settings.length_unit = "MILLIMETERS"


def material(name, rgb, rough=0.55, metal=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = m.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (*rgb, 1)
    bsdf.inputs["Roughness"].default_value = rough
    bsdf.inputs["Metallic"].default_value = metal
    m.diffuse_color = (*rgb, 1)  # solid-view colour
    return m


MAT = {
    "petg": material("PETG orange", (0.85, 0.36, 0.08), 0.45),
    "cover": material("PETG gris", (0.22, 0.25, 0.28), 0.5),
    "stand": material("Socle gris", (0.35, 0.38, 0.42), 0.6),
    "pla": material("PLA gabarit", (0.9, 0.9, 0.85), 0.6),
    "lidar": material("Lidar noir", (0.02, 0.02, 0.02), 0.35),
    "kit": material("Kit MR60 noir", (0.05, 0.05, 0.06), 0.7),
    "ld2450": material("PCB vert", (0.05, 0.35, 0.18), 0.4),
    "xiao": material("PCB bleu", (0.08, 0.18, 0.6), 0.4),
    "lcd": material("Ecran", (0.01, 0.02, 0.05), 0.15),
}


def collection(name, parent=None):
    c = bpy.data.collections.new(name)
    (parent or scene.collection).children.link(c)
    return c


def import_stl(path, name, mat, coll):
    bpy.ops.wm.stl_import(filepath=str(path))
    ob = bpy.context.selected_objects[0]
    ob.name = ob.data.name = name
    ob.data.materials.append(mat)
    for c in ob.users_collection:
        c.objects.unlink(ob)
    coll.objects.link(ob)
    return ob


def box(name, x0, y0, z0, dx, dy, dz, mat, coll):
    me = bpy.data.meshes.new(name)
    v = [(x0 + a * dx, y0 + b * dy, z0 + c * dz) for a in (0, 1) for b in (0, 1) for c in (0, 1)]
    f = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    me.from_pydata(v, [], f)
    ob = bpy.data.objects.new(name, me)
    ob.data.materials.append(mat)
    coll.objects.link(ob)
    return ob


def cylinder(name, cx, cy, z0, d, h, mat, coll):
    bpy.ops.mesh.primitive_cylinder_add(vertices=64, radius=d / 2, depth=h, location=(cx, cy, z0 + h / 2))
    ob = bpy.context.active_object
    ob.name = ob.data.name = name
    ob.data.materials.append(mat)
    for c in ob.users_collection:
        c.objects.unlink(ob)
    coll.objects.link(ob)
    return ob


# ---- 1. Assembly ----
asm = collection("SuperLens - Assemblage")
parts = {
    "Tete": ("head", "petg"),
    "Couvercle": ("cover", "cover"),
    "Poignee": ("grip", "petg"),
    "Socle": ("dock", "stand"),
}
asm_objs = {n: import_stl(stl_dir / f"{f}.stl", n, MAT[m], asm) for n, (f, m) in parts.items()}

sens = collection("Capteurs (volumes)", asm)
box("Kit MR60BHA2 (60 GHz)", kitX0 + clr / 2, t, kitZ0 + clr / 2, 54, 22, 35, MAT["kit"], sens)
box("HLK-LD2450 (24 GHz)", -22, t, ldCz - 7.5, 44, 1.6, 15, MAT["ld2450"], sens)
box("XIAO ESP32S3 Sense", -10.5, t, xCz - 8.9, 21, 15, 17.8, MAT["xiao"], sens)
box("Ecran 1.69in ST7789", -15.75, D - 4.5, 40 - 19.5, 31.5, 4.5, 39, MAT["lcd"], sens)
box("Lidar D500 - socle", -38.59 / 2, lidY - 38.59 / 2, H, 38.59, 38.59, 22.2, MAT["lidar"], sens)
cylinder("Lidar D500 - tete", 0, lidY, H + 22.2, 35.29, 12.6, MAT["lidar"], sens)

# ---- 2. Print-bed copies (same orientation as the STL exports) ----
prn = collection("Impression (oriente plateau)")
print_rot = {
    "Tete": Matrix.Rotation(math.radians(90), 4, "X"),
    "Couvercle": Matrix.Rotation(math.radians(-90), 4, "X"),
    "Poignee": Matrix.Rotation(math.radians(180), 4, "X"),
    "Socle": Matrix.Identity(4),
}
x_cursor = 200.0
for name, rot in print_rot.items():
    src = asm_objs[name]
    ob = src.copy()
    ob.data = src.data.copy()
    ob.name = ob.data.name = f"{name} (impression)"
    ob.data.transform(rot)
    xs = [v.co.x for v in ob.data.vertices]
    ys = [v.co.y for v in ob.data.vertices]
    zs = [v.co.z for v in ob.data.vertices]
    w = max(xs) - min(xs)
    ob.data.transform(Matrix.Translation(Vector((-(min(xs) + max(xs)) / 2, -(min(ys) + max(ys)) / 2, -min(zs)))))
    ob.location = (x_cursor + w / 2, 0, 0)
    x_cursor += w + 25
    prn.objects.link(ob)
tpl = import_stl(stl_dir / "template.stl", "Gabarit lidar (impression)", MAT["pla"], prn)
tpl.location = (x_cursor + 31, -lidY, 0)

# ---- 3. Light + camera on the assembly ----
sun = bpy.data.objects.new("Soleil", bpy.data.lights.new("Soleil", "SUN"))
sun.data.energy = 3.0
sun.rotation_euler = (math.radians(50), math.radians(10), math.radians(-35))
scene.collection.objects.link(sun)

cam = bpy.data.objects.new("Camera", bpy.data.cameras.new("Camera"))
cam.data.lens = 60
cam.data.clip_start, cam.data.clip_end = 1, 5000
cam.location = (-320, -420, 180)
direction = Vector((0, 30, 10)) - cam.location
cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
scene.collection.objects.link(cam)
scene.camera = cam

world = bpy.data.worlds.new("Monde")
world.use_nodes = True
world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.9, 0.92, 0.94, 1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.6
scene.world = world

out.parent.mkdir(parents=True, exist_ok=True)
bpy.ops.wm.save_as_mainfile(filepath=str(out), compress=True)
print("saved", out)
