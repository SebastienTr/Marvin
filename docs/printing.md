# Printing

All parts come from one parametric source, [`hardware/cad/superlens.scad`](../hardware/cad/superlens.scad). The STLs in [`hardware/stl/`](../hardware/stl/) are already oriented for the print bed, and **none of them needs supports**.

| File | Material | Orientation (already set) | Suggested settings | Notes |
|---|---|---|---|---|
| `0_lidar_template.stl` | PLA | Flat | 0.2 mm, 2 walls | About 5 min. **Print this first** and test-fit the LD19 on it. |
| `1_head.stl` | PETG | Front face down | 0.2 mm, 4 walls, 20 % gyroid | The windows come out clean on the bed side. |
| `2_back_cover.stl` | PETG | Outer face down | 0.2 mm, 3 walls, 20 % | The pressing columns grow upwards. |
| `3_grip.stl` | PETG | Upside down, flange on the bed | 0.2 mm, 5 walls, 25 % | The extra walls give the self-tapping M3 screws something to bite into. The cavity ceiling is sloped. |
| `4_stand.stl` | PETG or PLA | Base down | 0.2 mm, 3 walls, 40 % | Denser infill adds weight and stability. |

## Why PETG

PETG stays rigid at temperatures that soften PLA, whether from the ESP32 or a car in summer. The radar windows are open cut-outs, so the filament colour has no effect on the radars. A multi-material printer can print the front in an accent colour.

## Fit parameters

Adjust these at the top of `superlens.scad`, then run `hardware/scripts/export_stl.sh`:

| Parameter | Default | Change it when |
|---|---|---|
| `clr` | 0.4 mm | Modules are too loose (lower it) or too tight (raise it) |
| `insM3_d` | 2.6 mm | M3 screws are too hard or too easy to drive |
| `lidPairDY`, `lidSingleDY` | 8.3 / −23.6 mm | The lidar holes do not line up on the template |
| `tripod_nut_af` | 11.5 mm | Your 1/4"-20 nut spins or does not fit |
| `colFoam` | 3 mm | You use a different foam tape thickness |
| `gripA` | 12° | You prefer a more or less raked grip (the stand follows automatically) |

## Regenerating files

```bash
# every print-ready STL
hardware/scripts/export_stl.sh

# preview renders used in the docs (headless Linux: prefix with `xvfb-run -a`)
hardware/scripts/render_previews.sh
```

OpenSCAD 2021.01 or newer is required.
