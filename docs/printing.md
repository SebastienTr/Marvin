# Printing

All parts come from one parametric source, [`hardware/cad/fanou.scad`](../hardware/cad/fanou.scad). The STLs in [`hardware/stl/`](../hardware/stl/) are already oriented for the print bed, and **none of them needs supports**.

| File | Material | Orientation (already set) | Suggested settings | Notes |
|---|---|---|---|---|
| `0_lidar_template.stl` | PLA | Flat | 0.2 mm, 2 walls | About 5 min. **Print this first** and test-fit the lidar on it. |
| `1_base_island.stl` | PETG or PLA, grey | Upside down, top face on the bed | 0.2 mm, 3 walls, 15 % | The groove for the tower and the key come out clean on the bed side. |
| `2_bottom_plate.stl` | PETG or PLA, grey | Flat | 0.2 mm, 3 walls, 20 % | Speaker grille, speaker cradle and tripod-nut boss. |
| `3_tower_shell.stl` | **PETG**, red and white | Upright | 0.2 mm, **exactly 1.6 mm wall** (4 perimeters of 0.4 mm), no infill needed | The stripes are filament changes, see below. Do not use vase mode. |
| `4_spine.stl` | PETG | Lying on its back, plate on the bed | 0.2 mm, 3 walls, 20 % | Carries every front module. |
| `5_gallery_cap.stl` | PETG or PLA, navy | Upright, floor on the bed | 0.2 mm, 3 walls, 20 % | The railing posts are 2.4 mm: print at moderate speed. |

## The stripes

The tower is printed upright, so each stripe is a filament change at a given height. Heights are measured **from the print bed** (the bottom of the tower):

| From | To | Colour | What it hides |
|---:|---:|---|---|
| 0 mm | 26 mm | Red | Bottom of the 60 GHz radar door |
| 26 mm | 58 mm | White | — |
| 58 mm | 82 mm | Red | The 24 GHz radar (LD2450) |
| 82 mm | 114 mm | White | The face |
| 114 mm | 148 mm (top) | Red | The camera porthole |

- **Bambu A1 with AMS lite:** in Bambu Studio, right-click the layer slider at each height and choose "Change filament". To also paint the reliefs, use the Paint tool: door frame, face frame and porthole ring in navy, the porthole ring in brass-coloured PLA/PETG if you have one.
- **Single-extruder printer:** insert a pause (M600 or "Pause print") at each height and swap the filament by hand.

## Why PETG for the tower

The radars look through the tower wall. At 60 GHz, a 1.6 mm PETG wall is half a wavelength thick, so it lets the signal through almost unchanged. Keep the wall at 1.6 mm (four 0.4 mm perimeters) and avoid metallic or carbon-filled filaments on the tower. The filament colour itself does not matter for the radars.

PETG also stays rigid at temperatures that soften PLA. The island, bottom plate and cap can be PLA if you prefer.

## Fit parameters

Adjust these at the top of `fanou.scad`, then run `hardware/scripts/export_stl.sh`:

| Parameter | Default | Change it when |
|---|---|---|
| `clr` | 0.4 mm | Modules or the cap are too loose (lower it) or too tight (raise it) |
| `m3_pilot` | 2.6 mm | M3 screws are too hard or too easy to drive |
| `m25_pilot` | 2.1 mm | The lidar screws are too hard or too easy to drive |
| `lidPairDY`, `lidSingleDY` | 8.3 / −23.6 mm | The lidar holes do not line up on the template |
| `tripod_nut_af` | 11.5 mm | Your 1/4"-20 nut spins or does not fit |
| `colFoam` | 3 mm | You use a different foam tape thickness |
| `lcdT`, `lcdStand`, `lcdSx` | 4.5 / 4 / 13.25 mm | Your screen module is thicker, or its standoffs sit elsewhere |
| `kitW`, `kitH`, `kitD` | 35 / 54 / 22 mm | Your printed MR60BHA2 case has a different size |
| `spkL`, `spkH`, `spkT` | 30.4 / 20.4 / 5 mm | Your speaker has a different size |
| `ws` | 1.6 mm | Only if you print the tower in a filament other than PETG (half a wavelength depends on the material) |

## Regenerating files

```bash
# every print-ready STL
hardware/scripts/export_stl.sh

# preview renders used in the docs (headless Linux: prefix with `xvfb-run -a`)
hardware/scripts/render_previews.sh
```

OpenSCAD 2021.01 or newer is required.
