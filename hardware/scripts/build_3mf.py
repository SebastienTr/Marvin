"""Build hardware/bambu/marvin_A1.3mf: a Bambu Studio project with every printable part on its plate.

Plates (Bambu Lab A1 + AMS lite, 0.4 mm nozzle, 0.20 mm layers):
  1. Lidar template        anthracite PETG   print it first and test-fit the lidar
  2. Body + sensor sled    warm grey PETG, anthracite band by filament change at 14 and 44 mm
  3. Plinth                anthracite PETG
  4. TPU parts             black TPU 95A, fed from the external spool (not the AMS lite)

Usage:
    BAMBU_STUDIO=/path/to/bambu-studio python3 hardware/scripts/build_3mf.py [out.3mf]
(on Linux, point BAMBU_STUDIO at the extracted AppImage's AppRun; BAMBU_PROFILES overrides the profile folder)

It needs the Bambu Studio command line (the AppImage works headless under xvfb-run on
Linux) and the print-ready STLs in hardware/stl (hardware/scripts/export_stl.sh).
Bambu Studio builds the project; this script then sets the plates, the colours, the
per-part settings and the band colour change, and lets Bambu Studio re-save it.

SPDX-License-Identifier: MIT
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
STL = REPO / "hardware" / "stl"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "hardware" / "bambu" / "marvin_A1.3mf"
STUDIO = Path(os.environ.get("BAMBU_STUDIO", "bambu-studio"))
PROFILES = Path(os.environ["BAMBU_PROFILES"]) if "BAMBU_PROFILES" in os.environ else next(
    (d / "resources" / "profiles" / "BBL" for d in STUDIO.resolve().parents if (d / "resources" / "profiles" / "BBL").is_dir()),
    Path("/Applications/BambuStudio.app/Contents/Resources/profiles/BBL"))

MACHINE, PROCESS = "Bambu Lab A1 0.4 nozzle", "0.20mm Standard @BBL A1"
BED, GAP = 256.0, 1.2                         # A1 bed, Bambu Studio plate spacing
# filament slots: (profile, colour, label)
FILAMENTS = [
    ("Bambu PETG HF @BBL A1", "#D9D5CC", "PETG warm grey"),
    ("Bambu PETG HF @BBL A1", "#3A3A3C", "PETG anthracite"),
    ("Generic TPU @BBL A1", "#1A1A1A", "TPU 95A black"),
]
# part file -> (object name, filament slot 1-based, plate 1-based, per-object settings)
PARTS = {
    "0_lidar_template": ("Lidar template", 2, 1, {}),
    "1_body": ("Body", 1, 2, {"wall_loops": "4", "sparse_infill_density": "15%"}),
    "3_sensor_sled": ("Sensor sled", 1, 2, {"wall_loops": "3", "sparse_infill_density": "20%"}),
    "2_plinth": ("Plinth", 2, 3, {"wall_loops": "3", "sparse_infill_density": "20%"}),
    "4_tpu_neck_grommets_feet": ("TPU neck, grommets and feet", 3, 4,
                                 {"wall_loops": "3", "sparse_infill_density": "15%", "sparse_infill_pattern": "gyroid"}),
}
PLATES = {1: "1 - Lidar template (print first)", 2: "2 - Body + sensor sled (band colour change)",
          3: "3 - Plinth", 4: "4 - TPU (external spool)"}
# body band: filament change at these heights from the bed (the body prints upside down)
BAND_CHANGES = [(14.2, 2, "#3A3A3C"), (44.2, 1, "#D9D5CC")]   # (layer top z, new slot, colour)


def resolve(kind, name):
    """Flatten a Bambu system profile (follow 'inherits')."""
    for p in (PROFILES / kind).rglob("*.json"):
        try:
            d = json.loads(p.read_text())
        except ValueError:
            continue
        if d.get("name") == name:
            base = resolve(kind, d["inherits"]) if d.get("inherits") else {}
            out = {**base, **d}
            out.pop("inherits", None)
            out["from"] = "system"
            return out
    raise SystemExit(f"profile not found: {kind}/{name}")


def run(args, cwd):
    cmd = [str(STUDIO), "--debug", "1", *args]
    if sys.platform.startswith("linux") and not os.environ.get("DISPLAY"):
        cmd = ["xvfb-run", "-a", *cmd]
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    res = Path(cwd, "out", "result.json")
    if not res.exists() or json.loads(res.read_text()).get("return_code") != 0:
        sys.stderr.write(r.stdout[-4000:] + r.stderr[-4000:])
        raise SystemExit("Bambu Studio failed")


def mesh_bbox(zf, path):
    xs, ys = [], []
    for m in re.finditer(rb'<vertex x="([-\d.e]+)" y="([-\d.e]+)"', zf.read(path)):
        xs.append(float(m[1]))
        ys.append(float(m[2]))
    return min(xs), max(xs), min(ys), max(ys)


def main():
    work = Path(tempfile.mkdtemp())
    (work / "out").mkdir()
    json.dump(resolve("machine", MACHINE), open(work / "machine.json", "w"))
    json.dump(resolve("process", PROCESS), open(work / "process.json", "w"))
    fil = []
    for i, (name, colour, _) in enumerate(FILAMENTS):
        d = resolve("filament", name)
        d["filament_colour"] = [colour]
        json.dump(d, open(work / f"fil{i}.json", "w"))
        fil.append(str(work / f"fil{i}.json"))
    stls = [STL / f"{k}.stl" for k in PARTS]
    run(["--arrange", "0", "--load-settings", f"{work / 'machine.json'};{work / 'process.json'}",
         "--load-filaments", ";".join(fil), "--load-filament-ids", ",".join(str(v[1]) for v in PARTS.values()),
         "--export-3mf", "raw.3mf", "--outputdir", "out", *map(str, stls)], work)

    src = zipfile.ZipFile(work / "out" / "raw.3mf")
    files = {n: src.read(n) for n in src.namelist()}

    # --- object ids by source file, mesh centres
    ms = ET.fromstring(files["Metadata/model_settings.config"])
    objs = {}
    for o in ms.findall("object"):
        fname = o.find("part/metadata[@key='source_file']").get("value")
        objs[Path(fname).stem] = o
    ns = {"m": "http://schemas.microsoft.com/3dmanufacturing/core/2015/02",
          "p": "http://schemas.microsoft.com/3dmanufacturing/production/2015/06"}
    ET.register_namespace("", ns["m"])
    ET.register_namespace("p", ns["p"])
    ET.register_namespace("BambuStudio", "http://schemas.bambulab.com/package/2021")
    model = ET.fromstring(files["3D/3dmodel.model"])
    comp_path = {}
    for o in model.find("m:resources", ns).findall("m:object", ns):
        c = o.find("m:components/m:component", ns)
        comp_path[o.get("id")] = (c.get(f"{{{ns['p']}}}path").lstrip("/"), [float(v) for v in c.get("transform").split()])

    # --- per object: name, filament, settings; per plate: members and placement
    cols = 2
    placement = {}
    plate_members = {k: [] for k in PLATES}
    for key, (name, slot, plate, settings) in PARTS.items():
        o = objs[key]
        oid = o.get("id")
        o.find("metadata[@key='name']").set("value", name)
        o.find("part/metadata[@key='name']").set("value", name)
        o.find("metadata[@key='extruder']").set("value", str(slot))
        for k, v in settings.items():
            ET.SubElement(o, "metadata", key=k, value=v)
        plate_members[plate].append(oid)
        path, t = comp_path[oid]
        x0, x1, y0, y1 = mesh_bbox(src, path)
        placement[oid] = (plate, (x0 + x1) / 2 + t[9], (y0 + y1) / 2 + t[10], x1 - x0)
    # side by side when two parts share a plate
    item_xy = {}
    for plate, members in plate_members.items():
        px = ((plate - 1) % cols) * BED * GAP
        py = -((plate - 1) // cols) * BED * GAP
        widths = [placement[m][3] for m in members]
        total = sum(widths) + 15 * (len(members) - 1)
        x = px + BED / 2 - total / 2
        for m, w in zip(members, widths):
            _, cx, cy, _ = placement[m]
            item_xy[m] = (x + w / 2 - cx, py + BED / 2 - cy)
            x += w + 15

    for p in ms.findall("plate"):
        ms.remove(p)
    asm = ms.find("assemble")
    for plate, members in plate_members.items():
        pe = ET.Element("plate")
        for k, v in (("plater_id", str(plate)), ("plater_name", PLATES[plate]), ("locked", "false"),
                     ("filament_map_mode", "Auto For Flush"), ("gcode_file", "")):
            ET.SubElement(pe, "metadata", key=k, value=v)
        for m in members:
            mi = ET.SubElement(pe, "model_instance")
            ET.SubElement(mi, "metadata", key="object_id", value=m)
            ET.SubElement(mi, "metadata", key="instance_id", value="0")
            ET.SubElement(mi, "metadata", key="identify_id", value=str(1000 + int(m)))
        if asm is not None:
            ms.insert(list(ms).index(asm), pe)
        else:
            ms.append(pe)
    files["Metadata/model_settings.config"] = ET.tostring(ms, encoding="UTF-8", xml_declaration=True)

    build = model.find("m:build", ns)
    for it in build.findall("m:item", ns):
        x, y = item_xy[it.get("objectid")]
        it.set("transform", f"1 0 0 0 1 0 0 0 1 {x:.4f} {y:.4f} 0")
    files["3D/3dmodel.model"] = ET.tostring(model, encoding="UTF-8", xml_declaration=True)

    # --- project: filament colours and names
    ps = json.loads(files["Metadata/project_settings.config"])
    ps["filament_colour"] = [c for _, c, _ in FILAMENTS]
    ps["default_filament_colour"] = [""] * len(FILAMENTS)
    ps["curr_bed_type"] = "Textured PEI Plate"        # the A1's stock plate; PETG must not go on a smooth PEI without glue
    files["Metadata/project_settings.config"] = json.dumps(ps, indent=4).encode()

    # --- band colour change on the body plate
    body_plate = PARTS["1_body"][2]
    layers = "\n".join(f'<layer top_z="{z}" type="2" extruder="{e}" color="{c}" extra="" gcode="tool_change"/>'
                       for z, e, c in BAND_CHANGES)
    files["Metadata/custom_gcode_per_layer.xml"] = (
        '<?xml version="1.0" encoding="utf-8"?>\n<custom_gcodes_per_layer>\n'
        f'<plate>\n<plate_info id="{body_plate}"/>\n{layers}\n<mode value="MultiAsSingle"/>\n</plate>\n'
        '</custom_gcodes_per_layer>\n').encode()

    tmp = work / "edited.3mf"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for n, data in files.items():
            z.writestr(n, data)
    # let Bambu Studio re-read and re-save it, which validates the edits
    run(["--export-3mf", "final.3mf", "--outputdir", "out", str(tmp)], work)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(work / "out" / "final.3mf", OUT)
    shutil.rmtree(work)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
