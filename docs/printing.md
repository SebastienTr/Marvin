# Printing

> **Rev F status:** the parts below are the planned rev F set. Their STLs arrive with the rev F CAD. Until then, [`hardware/stl/`](../hardware/stl/) holds the rev E (lighthouse) parts, and only `0_lidar_template.stl` is valid for both.

All parts come from one parametric OpenSCAD source in [`hardware/cad/`](../hardware/cad/). The STLs are exported already oriented for the print bed, and **none of them needs supports**. Every part fits a 180 × 180 mm bed (Bambu A1 mini and up).

## Parts (rev F)

| Part | Material | Orientation | Suggested settings | Notes |
|---|---|---|---|---|
| Lidar template | PLA | Flat | 0.2 mm, 2 walls | About 5 min. **Print this first** and test-fit the lidar on it. |
| Body shell | **PETG**, warm grey + anthracite | Upright | 0.2 mm, **exactly 1.6 mm wall in front of the radars** (4 × 0.4 mm perimeters) | The anthracite band is a filament change by layer (see below). The wall thickness matters for the radars; the colour does not. |
| Plinth | PETG or PLA, anthracite | Flat | 0.2 mm, 3 walls, 20 % | Closes the body from below, carries the USB-C entry and the feet. |
| Sensor sled | PETG | Lying on its back | 0.2 mm, 3 walls, 20 % | Holds the 60 GHz radar at 20° and the 24 GHz radar at 10°; the WAGOs and the amp stick on its back. |
| Neck damper | **TPU 95A** | Flat | 0.2 mm, 3 walls, 15 % gyroid, slow (≤ 40 mm/s) | Decouples the head (lidar motor) from the body (heart-rate radar). Print outside the AMS. |
| Head shell | PETG, warm grey | **Upside down**, top on the bed | 0.2 mm, 3 walls, 15 % | Printing it upside down avoids an 80 mm unsupported roof. The face opening comes out clean. |
| Head frame | PETG | Flat | 0.2 mm, 3 walls, 20 % | Holds the screen vertical, the XIAO tilted 20° for the camera, and clamps the acrylic window. |
| Lidar ring | PETG or PLA, anthracite | Flat | 0.2 mm, 3 walls | Flush ring on top of the head; the lidar screws through it. |
| Knob | PETG or PLA, orange | Flat, face down | 0.12 mm layers for the knurl | Decorative in rev F (all XIAO pins are in use). |
| Feet | TPU 95A | Flat | 0.2 mm | Optional: silicone bumper feet work too. |

The **face window** is not printed: cut it from 2 mm smoked acrylic (about 74 × 44 mm, corners filed to a 10 mm radius). Black PETG would hide the screen.

## The anthracite band

The body shell is printed upright, so the band is a filament change at two heights. The exact heights come with the CAD; the plan is:

| From | To | Colour | What it hides |
|---:|---:|---|---|
| 0 | ≈ 8 mm | Warm grey | — |
| ≈ 8 mm | ≈ 60 mm | Anthracite | Both radars |
| ≈ 60 mm | top | Warm grey | — |

- **Bambu A1 with AMS lite:** in Bambu Studio, right-click the layer slider at each height and choose "Change filament".
- **Single-extruder printer:** insert a pause (M600 or "Pause print") at each height and swap the filament by hand.

## Why PETG in front of the radars

At 60 GHz, a 1.6 mm PETG wall is half a wavelength thick, so it lets the signal through almost unchanged, even when the radar looks through it at 20°. Keep that wall at 1.6 mm, keep it flat, and avoid metallic, glitter or carbon-filled filaments on the body. The filament colour itself does not matter.

## Regenerating files

```bash
# every print-ready STL
hardware/scripts/export_stl.sh

# preview renders used in the docs (headless Linux: prefix with `xvfb-run -a`)
hardware/scripts/render_previews.sh
```

OpenSCAD 2021.01 or newer is required.
