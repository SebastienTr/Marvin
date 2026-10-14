"""Build hardware/bambu/marvin_A1.3mf: a Bambu Studio project with every printable part on its plate.

Plates (Bambu Lab A1 + AMS lite, 0.4 mm nozzle, 0.20 mm layers, textured PEI):
  1. Body + sensor sled          warm grey PETG, anthracite band by filament change at 14 and 44 mm
  2. Plinth + face frame,        anthracite PETG
     switch bracket, window template
  3. TPU parts                   black TPU 95A, fed from the external spool (not the AMS lite)
  4. Head shell + back cap,      warm grey PETG, the knob in orange PETG
     knob

Two ways to build it:
    BAMBU_STUDIO=/path/to/bambu-studio python3 hardware/scripts/build_3mf.py [out.3mf]
        Bambu Studio builds the project from the STLs; this script then sets the plates, colours,
        per-part settings and the band colour change, and lets Bambu Studio re-save (and so check) it.
        On Linux, point BAMBU_STUDIO at the extracted AppImage's AppRun (it runs under xvfb-run);
        BAMBU_PROFILES overrides the profile folder.
    python3 hardware/scripts/build_3mf.py --no-studio [out.3mf]
        Writes the same project without Bambu Studio: the meshes, the plates and the per-part
        settings are written here, the printer, process and filament settings come from the
        project already in the repository (made by Bambu Studio), with the orange slot added.
Either way the result is checked (--check FILE checks an existing project only): the XML parses,
every object has a mesh and sits on one plate, inside the A1's bed, with a known filament slot,
at most 4 plates.

It needs the print-ready STLs in hardware/stl (hardware/scripts/export_stl.sh).

SPDX-License-Identifier: MIT
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
STL = REPO / "hardware" / "stl"
DEFAULT_OUT = REPO / "hardware" / "bambu" / "marvin_A1.3mf"

MACHINE, PROCESS = "Bambu Lab A1 0.4 nozzle", "0.20mm Standard @BBL A1"
BED, GAP = 256.0, 1.2                         # A1 bed, Bambu Studio plate spacing
COLS = 2                                      # plates per row in Bambu Studio's plate grid
SPACING = 12.0                                # between parts on a plate
# filament slots: (profile, colour, label)
FILAMENTS = [
    ("Bambu PETG HF @BBL A1", "#D9D5CC", "PETG warm grey"),
    ("Bambu PETG HF @BBL A1", "#3A3A3C", "PETG anthracite"),
    ("Generic TPU @BBL A1", "#1A1A1A", "TPU 95A black"),
    ("Bambu PETG HF @BBL A1", "#D9541A", "PETG orange"),
]
# part file -> (object name, filament slot 1-based, plate 1-based, per-object settings)
PARTS = {
    "1_body": ("Body", 1, 1, {"wall_loops": "4", "sparse_infill_density": "15%"}),
    "3_sensor_sled": ("Sensor sled", 1, 1, {"wall_loops": "3", "sparse_infill_density": "20%"}),
    "2_plinth": ("Plinth", 2, 2, {"wall_loops": "3", "sparse_infill_density": "20%"}),
    "7_face_frame": ("Face frame", 2, 2, {"wall_loops": "3", "sparse_infill_density": "25%",
                                          "brim_type": "outer_only", "brim_width": "5"}),   # tall and thin: a brim
    "9_switch_bracket": ("Switch bracket", 2, 2, {"wall_loops": "4", "sparse_infill_density": "25%"}),
    "10_window_template": ("Window template", 2, 2, {"wall_loops": "3", "sparse_infill_density": "15%"}),
    "4_tpu_neck_grommets_feet": ("TPU neck, grommets and feet", 3, 3,
                                 {"wall_loops": "3", "sparse_infill_density": "15%", "sparse_infill_pattern": "gyroid"}),
    "5_head_shell": ("Head shell", 1, 4, {"wall_loops": "4", "sparse_infill_density": "15%"}),
    "6_head_cap": ("Head back cap", 1, 4, {"wall_loops": "4", "sparse_infill_density": "15%"}),
    "8_knob": ("Knob", 4, 4, {"wall_loops": "3", "sparse_infill_density": "20%"}),
}
PLATES = {1: "1 - Body + sensor sled (band colour change)", 2: "2 - Plinth, face frame, switch bracket, window template",
          3: "3 - TPU (external spool)", 4: "4 - Head shell, back cap, knob (orange)"}
# body band: filament change at these heights from the bed (the body prints upside down)
BAND_CHANGES = [(14.2, 2, "#3A3A3C"), (44.2, 1, "#D9D5CC")]   # (layer top z, new slot, colour)

NS = {"m": "http://schemas.microsoft.com/3dmanufacturing/core/2015/02",
      "p": "http://schemas.microsoft.com/3dmanufacturing/production/2015/06"}


# ------------------------------------------------------------------ meshes and placement
def read_stl(path):
    """ASCII or binary STL -> (vertices [n, 3], triangles [m, 3] of vertex indices)."""
    import numpy as np
    data = Path(path).read_bytes()
    if data[:5] == b"solid" and b"facet" in data[:400]:
        v = np.array(re.findall(rb"vertex\s+(\S+)\s+(\S+)\s+(\S+)", data), dtype=float)
    else:
        n = int.from_bytes(data[80:84], "little")
        rec = np.frombuffer(data[84:84 + n * 50], dtype=np.dtype([("n", "<3f4"), ("v", "<9f4"), ("a", "<u2")]))
        v = rec["v"].reshape(-1, 3).astype(float)
    keys = np.round(v * 1e5).astype(np.int64)
    uniq, first, inv = np.unique(keys, axis=0, return_index=True, return_inverse=True)
    tri = inv.reshape(-1, 3)
    tri = tri[(tri[:, 0] != tri[:, 1]) & (tri[:, 1] != tri[:, 2]) & (tri[:, 2] != tri[:, 0])]
    return v[first], tri


def footprints():
    """Part -> (x0, x1, y0, y1, z0, z1) of its print-ready STL."""
    out = {}
    for k in PARTS:
        v, _ = read_stl(STL / f"{k}.stl")
        lo, hi = v.min(axis=0), v.max(axis=0)
        out[k] = (lo[0], hi[0], lo[1], hi[1], lo[2], hi[2])
    return out


def plate_origin(plate):
    return ((plate - 1) % COLS) * BED * GAP, -((plate - 1) // COLS) * BED * GAP


def arrange(fp):
    """Shelf packing per plate, centred on the bed -> part -> (centre x, centre y) on the plate grid."""
    centres = {}
    for plate in PLATES:
        members = sorted([k for k, v in PARTS.items() if v[2] == plate], key=lambda k: -(fp[k][3] - fp[k][2]))
        rows, row, width = [], [], 0.0
        for k in members:
            w = fp[k][1] - fp[k][0]
            if row and width + SPACING + w > BED - 10:
                rows.append(row)
                row, width = [], 0.0
            width += (SPACING if row else 0) + w
            row.append(k)
        if row:
            rows.append(row)
        depths = [max(fp[k][3] - fp[k][2] for k in r) for r in rows]
        total_d = sum(depths) + SPACING * (len(rows) - 1)
        px, py = plate_origin(plate)
        y = py + BED / 2 + total_d / 2
        for r, d in zip(rows, depths):
            widths = [fp[k][1] - fp[k][0] for k in r]
            x = px + BED / 2 - (sum(widths) + SPACING * (len(r) - 1)) / 2
            for k, w in zip(r, widths):
                centres[k] = (x + w / 2, y - d / 2)
                x += w + SPACING
            y -= d + SPACING
    return centres


# ------------------------------------------------------------------ project settings
def template_settings():
    """Bambu Studio's project settings from the project in the repository, extended to len(FILAMENTS) slots."""
    with zipfile.ZipFile(DEFAULT_OUT) as z:
        ps = json.loads(z.read("Metadata/project_settings.config"))
    n0 = len(ps["filament_colour"])
    n = len(FILAMENTS)
    if n0 < n:
        src = {name: i for i, (name, _, _) in reversed(list(enumerate(FILAMENTS[:n0])))}
        for k, v in list(ps.items()):
            if isinstance(v, list) and len(v) == n0 and k != "flush_volumes_matrix":
                ps[k] = v + [v[src.get(FILAMENTS[i][0], 0)] for i in range(n0, n)]
        m = ps.get("flush_volumes_matrix")
        if m and len(m) == n0 * n0:
            grid = [m[i * n0:(i + 1) * n0] for i in range(n0)]
            for i in range(n0, n):
                s = src.get(FILAMENTS[i][0], 0)
                for row in grid:
                    row.append(row[s])
                grid.append(list(grid[s]))
                grid[i][i] = "0"
            ps["flush_volumes_matrix"] = [x for row in grid for x in row]
    ps["filament_colour"] = [c for _, c, _ in FILAMENTS]
    ps["default_filament_colour"] = [""] * n
    ps["filament_settings_id"] = [name for name, _, _ in FILAMENTS]
    ps["curr_bed_type"] = "Textured PEI Plate"        # the A1's stock plate; PETG must not go on a smooth PEI without glue
    return ps


def band_gcode():
    body_plate = PARTS["1_body"][2]
    layers = "\n".join(f'<layer top_z="{z}" type="2" extruder="{e}" color="{c}" extra="" gcode="tool_change"/>'
                       for z, e, c in BAND_CHANGES)
    return ('<?xml version="1.0" encoding="utf-8"?>\n<custom_gcodes_per_layer>\n'
            f'<plate>\n<plate_info id="{body_plate}"/>\n{layers}\n<mode value="MultiAsSingle"/>\n</plate>\n'
            '</custom_gcodes_per_layer>\n').encode()


def plate_element(plate, members):
    pe = ET.Element("plate")
    for k, v in (("plater_id", str(plate)), ("plater_name", PLATES[plate]), ("locked", "false"),
                 ("filament_map_mode", "Auto For Flush"), ("gcode_file", "")):
        ET.SubElement(pe, "metadata", key=k, value=v)
    for m in members:
        mi = ET.SubElement(pe, "model_instance")
        ET.SubElement(mi, "metadata", key="object_id", value=m)
        ET.SubElement(mi, "metadata", key="instance_id", value="0")
        ET.SubElement(mi, "metadata", key="identify_id", value=str(1000 + int(m)))
    return pe


# ------------------------------------------------------------------ without Bambu Studio
def build_direct(out):
    fp = footprints()
    centres = arrange(fp)
    files = {}
    model_items, resources, rels, settings = [], [], [], ET.Element("config")
    plate_members = {p: [] for p in PLATES}
    for i, (key, (name, slot, plate, opts)) in enumerate(PARTS.items(), start=1):
        mesh_id, obj_id = 2 * i - 1, 2 * i
        v, tri = read_stl(STL / f"{key}.stl")
        x0, x1, y0, y1, z0, z1 = fp[key]
        c = ((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        verts = "\n".join(f'     <vertex x="{a - c[0]:.6g}" y="{b - c[1]:.6g}" z="{d - c[2]:.6g}"/>' for a, b, d in v)
        tris = "\n".join(f'     <triangle v1="{a}" v2="{b}" v3="{d}"/>' for a, b, d in tri)
        files[f"3D/Objects/object_{i}.model"] = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<model unit="millimeter" xml:lang="en-US" xmlns="{NS["m"]}" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021" '
            f'xmlns:p="{NS["p"]}" requiredextensions="p">\n <metadata name="BambuStudio:3mfVersion">1</metadata>\n <resources>\n'
            f'  <object id="{mesh_id}" p:UUID="{uuid.uuid4()}" type="model">\n   <mesh>\n    <vertices>\n{verts}\n    </vertices>\n'
            f'    <triangles>\n{tris}\n    </triangles>\n   </mesh>\n  </object>\n </resources>\n <build/>\n</model>\n').encode()
        resources.append(f'  <object id="{obj_id}" p:UUID="{uuid.uuid4()}" type="model">\n   <components>\n'
                         f'    <component p:path="/3D/Objects/object_{i}.model" objectid="{mesh_id}" p:UUID="{uuid.uuid4()}" '
                         f'transform="1 0 0 0 1 0 0 0 1 0 0 0"/>\n   </components>\n  </object>')
        cx, cy = centres[key]
        model_items.append(f'  <item objectid="{obj_id}" p:UUID="{uuid.uuid4()}" '
                           f'transform="1 0 0 0 1 0 0 0 1 {cx:.4f} {cy:.4f} {c[2] - z0:.4f}" printable="1"/>')
        rels.append(f' <Relationship Target="/3D/Objects/object_{i}.model" Id="rel-{i + 1}" '
                    'Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>')
        o = ET.SubElement(settings, "object", id=str(obj_id))
        ET.SubElement(o, "metadata", key="name", value=name)
        ET.SubElement(o, "metadata", key="extruder", value=str(slot))
        for k, val in opts.items():
            ET.SubElement(o, "metadata", key=k, value=val)
        part = ET.SubElement(o, "part", id=str(mesh_id), subtype="normal_part")
        ET.SubElement(part, "metadata", key="name", value=name)
        ET.SubElement(part, "metadata", key="matrix", value="1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1")
        ET.SubElement(part, "metadata", key="source_file", value=f"{key}.stl")
        ET.SubElement(part, "mesh_stat", face_count=str(len(tri)), edges_fixed="0", degenerate_facets="0",
                      facets_removed="0", facets_reversed="0", backwards_edges="0")
        plate_members[plate].append(str(obj_id))
    for plate, members in plate_members.items():
        settings.append(plate_element(plate, members))
    ET.SubElement(settings, "assemble")
    ET.indent(settings)
    files["3D/3dmodel.model"] = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<model unit="millimeter" xml:lang="en-US" xmlns="{NS["m"]}" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021" '
        f'xmlns:p="{NS["p"]}" requiredextensions="p">\n <metadata name="Application">BambuStudio-02.08.02.61</metadata>\n'
        ' <metadata name="BambuStudio:3mfVersion">1</metadata>\n <metadata name="Title">Marvin rev F</metadata>\n'
        ' <resources>\n' + "\n".join(resources) + '\n </resources>\n'
        f' <build p:UUID="{uuid.uuid4()}">\n' + "\n".join(model_items) + '\n </build>\n</model>\n').encode()
    files["3D/_rels/3dmodel.model.rels"] = ('<?xml version="1.0" encoding="UTF-8"?>\n'
                                           '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\n'
                                           + "\n".join(rels) + '\n</Relationships>\n').encode()
    files["_rels/.rels"] = ('<?xml version="1.0" encoding="UTF-8"?>\n'
                            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\n'
                            ' <Relationship Target="/3D/3dmodel.model" Id="rel-1" '
                            'Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>\n</Relationships>\n').encode()
    files["[Content_Types].xml"] = ('<?xml version="1.0" encoding="UTF-8"?>\n'
                                    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\n'
                                    ' <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\n'
                                    ' <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>\n'
                                    ' <Default Extension="png" ContentType="image/png"/>\n'
                                    ' <Default Extension="gcode" ContentType="text/x.gcode"/>\n</Types>\n').encode()
    files["Metadata/model_settings.config"] = ET.tostring(settings, encoding="UTF-8", xml_declaration=True)
    files["Metadata/project_settings.config"] = json.dumps(template_settings(), indent=4).encode()
    files["Metadata/custom_gcode_per_layer.xml"] = band_gcode()
    files["Metadata/slice_info.config"] = ('<?xml version="1.0" encoding="UTF-8"?>\n<config>\n  <header>\n'
                                           '    <header_item key="X-BBL-Client-Type" value="slicer"/>\n'
                                           '    <header_item key="X-BBL-Client-Version" value="02.08.02.61"/>\n'
                                           '  </header>\n</config>\n').encode()
    files["Metadata/filament_sequence.json"] = json.dumps(
        {f"plate_{p}": {"nozzle_sequence": [], "optimal_assignment": [], "sequence": []} for p in PLATES}).encode()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for n in ("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model", "3D/_rels/3dmodel.model.rels",
                  *sorted(k for k in files if k.startswith("3D/Objects/")), *sorted(k for k in files if k.startswith("Metadata/"))):
            z.writestr(n, files[n])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(buf.getvalue())


# ------------------------------------------------------------------ with Bambu Studio
def studio_paths():
    studio = Path(os.environ.get("BAMBU_STUDIO", "bambu-studio"))
    profiles = Path(os.environ["BAMBU_PROFILES"]) if "BAMBU_PROFILES" in os.environ else next(
        (d / "resources" / "profiles" / "BBL" for d in studio.resolve().parents if (d / "resources" / "profiles" / "BBL").is_dir()),
        Path("/Applications/BambuStudio.app/Contents/Resources/profiles/BBL"))
    return studio, profiles


def resolve(profiles, kind, name):
    """Flatten a Bambu system profile (follow 'inherits')."""
    for p in (profiles / kind).rglob("*.json"):
        try:
            d = json.loads(p.read_text())
        except ValueError:
            continue
        if d.get("name") == name:
            base = resolve(profiles, kind, d["inherits"]) if d.get("inherits") else {}
            out = {**base, **d}
            out.pop("inherits", None)
            out["from"] = "system"
            return out
    raise SystemExit(f"profile not found: {kind}/{name}")


def run(studio, args, cwd):
    cmd = [str(studio), "--debug", "1", *args]
    if sys.platform.startswith("linux") and not os.environ.get("DISPLAY"):
        cmd = ["xvfb-run", "-a", *cmd]
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    res = Path(cwd, "out", "result.json")
    if not res.exists() or json.loads(res.read_text()).get("return_code") != 0:
        sys.stderr.write(r.stdout[-4000:] + r.stderr[-4000:])
        raise SystemExit("Bambu Studio failed")


def build_studio(out):
    studio, profiles = studio_paths()
    work = Path(tempfile.mkdtemp())
    (work / "out").mkdir()
    json.dump(resolve(profiles, "machine", MACHINE), open(work / "machine.json", "w"))
    json.dump(resolve(profiles, "process", PROCESS), open(work / "process.json", "w"))
    fil = []
    for i, (name, colour, _) in enumerate(FILAMENTS):
        d = resolve(profiles, "filament", name)
        d["filament_colour"] = [colour]
        json.dump(d, open(work / f"fil{i}.json", "w"))
        fil.append(str(work / f"fil{i}.json"))
    stls = [STL / f"{k}.stl" for k in PARTS]
    run(studio, ["--arrange", "0", "--load-settings", f"{work / 'machine.json'};{work / 'process.json'}",
                 "--load-filaments", ";".join(fil), "--load-filament-ids", ",".join(str(v[1]) for v in PARTS.values()),
                 "--export-3mf", "raw.3mf", "--outputdir", "out", *map(str, stls)], work)

    src = zipfile.ZipFile(work / "out" / "raw.3mf")
    files = {n: src.read(n) for n in src.namelist()}
    ms = ET.fromstring(files["Metadata/model_settings.config"])
    objs = {Path(o.find("part/metadata[@key='source_file']").get("value")).stem: o for o in ms.findall("object")}
    ET.register_namespace("", NS["m"])
    ET.register_namespace("p", NS["p"])
    ET.register_namespace("BambuStudio", "http://schemas.bambulab.com/package/2021")
    model = ET.fromstring(files["3D/3dmodel.model"])
    comp = {}
    for o in model.find("m:resources", NS).findall("m:object", NS):
        c = o.find("m:components/m:component", NS)
        comp[o.get("id")] = (c.get(f"{{{NS['p']}}}path").lstrip("/"), [float(v) for v in c.get("transform").split()])
    fp = footprints()
    centres = arrange(fp)
    plate_members = {k: [] for k in PLATES}
    item_xy = {}
    for key, (name, slot, plate, opts) in PARTS.items():
        o = objs[key]
        oid = o.get("id")
        o.find("metadata[@key='name']").set("value", name)
        o.find("part/metadata[@key='name']").set("value", name)
        o.find("metadata[@key='extruder']").set("value", str(slot))
        for k, v in opts.items():
            ET.SubElement(o, "metadata", key=k, value=v)
        plate_members[plate].append(oid)
        path, t = comp[oid]
        xs, ys = [], []
        for m in re.finditer(rb'<vertex x="([-\d.e]+)" y="([-\d.e]+)"', src.read(path)):
            xs.append(float(m[1]))
            ys.append(float(m[2]))
        # the item moves the mesh's centre (as Bambu Studio stored it) onto the packed spot
        item_xy[oid] = (centres[key][0] - ((min(xs) + max(xs)) / 2 + t[9]), centres[key][1] - ((min(ys) + max(ys)) / 2 + t[10]))
    for p in ms.findall("plate"):
        ms.remove(p)
    asm = ms.find("assemble")
    for plate, members in plate_members.items():
        pe = plate_element(plate, members)
        if asm is not None:
            ms.insert(list(ms).index(asm), pe)
        else:
            ms.append(pe)
    files["Metadata/model_settings.config"] = ET.tostring(ms, encoding="UTF-8", xml_declaration=True)
    build = model.find("m:build", NS)
    for it in build.findall("m:item", NS):
        x, y = item_xy[it.get("objectid")]
        z = it.get("transform").split()[11]
        it.set("transform", f"1 0 0 0 1 0 0 0 1 {x:.4f} {y:.4f} {z}")
    files["3D/3dmodel.model"] = ET.tostring(model, encoding="UTF-8", xml_declaration=True)
    ps = json.loads(files["Metadata/project_settings.config"])
    ps["filament_colour"] = [c for _, c, _ in FILAMENTS]
    ps["default_filament_colour"] = [""] * len(FILAMENTS)
    ps["curr_bed_type"] = "Textured PEI Plate"
    files["Metadata/project_settings.config"] = json.dumps(ps, indent=4).encode()
    files["Metadata/custom_gcode_per_layer.xml"] = band_gcode()
    tmp = work / "edited.3mf"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for n, data in files.items():
            z.writestr(n, data)
    run(studio, ["--export-3mf", "final.3mf", "--outputdir", "out", str(tmp)], work)   # re-read and re-saved: checks the edits
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(work / "out" / "final.3mf", out)
    shutil.rmtree(work)


# ------------------------------------------------------------------ check
def check(path):
    """Structural check of a Bambu project; returns a list of problems (empty = fine)."""
    errs = []
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    for n in ("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model", "Metadata/model_settings.config",
              "Metadata/project_settings.config"):
        if n not in names:
            errs.append(f"missing {n}")
    for n in names:
        if n.endswith((".model", ".config", ".xml", ".rels")) and n != "Metadata/project_settings.config":
            try:
                ET.fromstring(z.read(n))
            except ET.ParseError as e:
                errs.append(f"{n}: {e}")
    ps = json.loads(z.read("Metadata/project_settings.config"))
    nfil = len(ps.get("filament_colour", []))
    if len(ps.get("filament_settings_id", [])) != nfil:
        errs.append("filament_settings_id and filament_colour disagree")
    model = ET.fromstring(z.read("3D/3dmodel.model"))
    objs = {}
    for o in model.find("m:resources", NS).findall("m:object", NS):
        c = o.find("m:components/m:component", NS)
        if c is None:
            errs.append(f"object {o.get('id')} has no component")
            continue
        part = c.get(f"{{{NS['p']}}}path").lstrip("/")
        if part not in names:
            errs.append(f"object {o.get('id')}: {part} missing")
            continue
        sub = ET.fromstring(z.read(part))
        vs = sub.findall(".//m:vertex", NS)
        ts = sub.findall(".//m:triangle", NS)
        if not vs or not ts:
            errs.append(f"{part}: empty mesh")
            continue
        xyz = [[float(v.get(a)) for a in "xyz"] for v in vs]
        nv = len(vs)
        if any(int(t.get(k)) >= nv for t in ts for k in ("v1", "v2", "v3")):
            errs.append(f"{part}: triangle index out of range")
        lo = [min(p[i] for p in xyz) for i in range(3)]
        hi = [max(p[i] for p in xyz) for i in range(3)]
        tr = [float(v) for v in c.get("transform").split()]
        objs[o.get("id")] = (lo, hi, tr)
    ms = ET.fromstring(z.read("Metadata/model_settings.config"))
    plates = ms.findall("plate")
    if not 1 <= len(plates) <= 4:
        errs.append(f"{len(plates)} plates (want 1 to 4)")
    on_plate = {}
    for p in plates:
        pid = int(p.find("metadata[@key='plater_id']").get("value"))
        for mi in p.findall("model_instance"):
            oid = mi.find("metadata[@key='object_id']").get("value")
            if oid in on_plate:
                errs.append(f"object {oid} on two plates")
            on_plate[oid] = pid
    slots = {}
    for o in ms.findall("object"):
        e = o.find("metadata[@key='extruder']")
        slots[o.get("id")] = int(e.get("value")) if e is not None else 0
    for it in model.find("m:build", NS).findall("m:item", NS):
        oid = it.get("objectid")
        if oid not in objs:
            errs.append(f"build item {oid} has no object")
            continue
        if oid not in on_plate:
            errs.append(f"object {oid} is on no plate")
            continue
        if not 1 <= slots.get(oid, 0) <= nfil:
            errs.append(f"object {oid}: filament slot {slots.get(oid)} of {nfil}")
        t = [float(v) for v in it.get("transform").split()]
        lo, hi, ct = objs[oid]
        px, py = plate_origin(on_plate[oid])
        x0, x1 = lo[0] + ct[9] + t[9] - px, hi[0] + ct[9] + t[9] - px
        y0, y1 = lo[1] + ct[10] + t[10] - py, hi[1] + ct[10] + t[10] - py
        z0 = lo[2] + ct[11] + t[11]
        if x0 < 0 or y0 < 0 or x1 > BED or y1 > BED:
            errs.append(f"object {oid} leaves plate {on_plate[oid]}: x {x0:.1f}..{x1:.1f} y {y0:.1f}..{y1:.1f}")
        if abs(z0) > 0.05:
            errs.append(f"object {oid} does not sit on the bed (z {z0:.2f})")
    # parts on one plate must not overlap (footprints)
    by_plate = {}
    for it in model.find("m:build", NS).findall("m:item", NS):
        oid = it.get("objectid")
        if oid in objs and oid in on_plate:
            t = [float(v) for v in it.get("transform").split()]
            lo, hi, ct = objs[oid]
            by_plate.setdefault(on_plate[oid], []).append((oid, lo[0] + t[9], hi[0] + t[9], lo[1] + t[10], hi[1] + t[10]))
    for plate, boxes in by_plate.items():
        for i, a in enumerate(boxes):
            for b in boxes[i + 1:]:
                if a[1] < b[2] and b[1] < a[2] and a[3] < b[4] and b[3] < a[4]:
                    errs.append(f"plate {plate}: objects {a[0]} and {b[0]} overlap")
    return errs, len(plates), len(on_plate)


def main():
    args = [a for a in sys.argv[1:]]
    if args[:1] == ["--check"]:
        errs, np_, no = check(Path(args[1]))
        print(f"{args[1]}: {np_} plates, {no} objects" + ("" if not errs else "\n  " + "\n  ".join(errs)))
        sys.exit(1 if errs else 0)
    direct = "--no-studio" in args
    args = [a for a in args if a != "--no-studio"]
    out = Path(args[0]) if args else DEFAULT_OUT
    tmp = Path(tempfile.mkdtemp()) / "marvin.3mf"
    if direct:
        build_direct(tmp)
    else:
        build_studio(tmp)
    errs, np_, no = check(tmp)
    if errs:
        sys.exit("the project does not check:\n  " + "\n  ".join(errs))
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(tmp, out)
    shutil.rmtree(tmp.parent)
    print(f"wrote {out}: {np_} plates, {no} objects")


if __name__ == "__main__":
    main()
