# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Rev F CAD, body half (2026-09-27)
- `hardware/cad/marvin.scad`: body shell (printed upside down, 1.6 mm radome wall, anthracite band by filament change), plinth, sensor sled, TPU neck gasket, grommets and feet, lidar template. Print-ready STLs in `hardware/stl/`.
- The 24 GHz radar now sits **below** the 60 GHz radar (face centres at 16 mm and 42 mm): above it, it would have shadowed the upper part of the 60 GHz beam.
- The head sits on the body through a TPU gasket and four TPU grommets; no screw links them rigidly.
- `hardware/scripts/section_diagram.py` draws the section diagram from the CAD values.
- Rev E (Fanou) sources, STLs and Blender file moved to `hardware/archive/rev-e/`.

### Renamed (2026-09-27): SuperLens is now Marvin
- The project and the robot are now called Marvin. "marvin" is also its wake word, a keyword of Google's open Speech Commands dataset.
- Older entries below keep the file names they had at the time (`superlens.scad`, `superlens.blend`).

### Rev F design (2026-09-25): the upright robot
- New shape: a compact upright robot (about 88 × 80 mm, 15 cm tall with the lidar) replaces the lighthouse. Design studies in `docs/concepts/` (terminal, robot v1–v3, sight lines).
- Sensors are tilted inside an upright shell: 60 GHz radar at 20° (aimed at a seated chest, 0.6–1 m), 24 GHz radar at 10°, camera at 20°, screen vertical.
- Head and body joined through a TPU damper so the lidar motor does not disturb the heart-rate radar.
- Smoked acrylic face window; anthracite band hiding both radars, printed as a filament change.
- Grown-up design language: warm grey, anthracite, one orange knob.
- BOM: MR60BHA2 now bought from Seeed Studio; adds smoked acrylic and TPU. Electronics and wiring unchanged.
- Docs, READMEs and presentation page describe rev F. The rev F CAD is not written yet; `hardware/cad/fanou.scad` still holds rev E.

## [rev E] - 2026-09-25 · Fanou

### Changed
- The handheld sensor head becomes **Fanou**, a desk companion shaped like a little lighthouse. Same sensors, same wiring and pin map as rev D.
- New parametric source `hardware/cad/fanou.scad`: rocky island base, bottom plate, striped tower (1.6 mm PETG wall, half a wavelength at 60 GHz), spine carrying every module, gallery cap with railing and lidar plinth.
- The radars now look through the painted wall: the 60 GHz radar behind an arched door, the 24 GHz radar behind the middle red band.
- The screen is Fanou's face, in portrait behind a framed window; the camera looks through a brass porthole above it.
- The speaker moves to the bottom plate under a grille; the WAGOs and the amp are stuck on the back of the spine.
- Power arrives through a USB-C cable with a right-angle plug, entering at the back of the island.
- The lidar is held by 3 thread-forming M2.5 screws (no nuts); the cap by 3 radial M3 screws.
- New Blender file `hardware/blender/fanou.blend` and Cycles renders `docs/images/fanou_*.png`. Presentation page and READMEs rewritten.

### Added
- Character studies in `docs/concepts/` (Fanou the lighthouse, Hulotte the owl, Tito the robot).

### Removed
- `hardware/cad/superlens.scad` and its parts (head, back cover, grip, stand), `superlens.blend` and the rev D renders.

## [rev D] - 2026-09-24

### Added
- Audio: the XIAO Sense's built-in PDM microphone, plus a MAX98357A I2S amp driving a 2030 speaker held in a cradle on the left wall. The side vents act as its grille, with extra holes.
- Presentation page `docs/index.html` (EN/FR) with a live simulation of the back screen.
- Cycles renders `docs/images/hero_front.png` and `hero_back.png`.

### Changed
- The D800 lidar (STL-27L, 921 600 baud) is now the reference; the D500 remains a cheaper option.
- To free the I2S pins, the lidar PWM goes back to GND and the screen backlight to 3V3. There are now four WAGO buses (5 V, GND-A, GND-B, 3V3) and 29 connections.
- The standard XIAO (loose headers) is the default: two header strips to solder.
- README and README.fr open with the project vision.
- Blender file: flat CAD shading, glowing screen, speaker volume.

## [rev C] - 2026-09-24

### Added
- 1.69" ST7789 colour screen (Waveshare, 240×280) in the back cover, held by two clamp brackets pressing its standoffs through foam.
- Blender file `hardware/blender/superlens.blend` (assembly + print layout) and its build script.

### Changed
- Recommended lidar: LDROBOT D500 (STL-19P), a drop-in replacement for the LD19. The D800 (STL-27L) is listed as an option.
- The power bank now stays in your pocket on a 1 m USB-C cable. The strap slots are gone.
- The lidar PWM pin now goes to D9 instead of GND, so the firmware can control the motor speed.
- Pressing columns moved around the screen: MR60 columns lowered, LD2450 columns moved to the board ends.

## [rev B] - 2026-09-24

### Changed
- Solderless build: a pre-soldered XIAO ESP32S3 Sense, Dupont plugs and WAGO 221 lever connectors replace the soldered distribution board.
- The user's power bank now plugs straight into the XIAO's USB-C port, and the 5V pin feeds the sensors.
- M3 screws thread-form into PETG, so heat-set inserts are no longer needed.
- The tripod mount uses a captive 1/4"-20 nut instead of a heat-set insert.

### Added
- Desk stand that holds the grip so the head stays level.
- Print-bed orientations in the CAD source (`PART="print_*"`) and export scripts.

### Removed
- Trigger button, USB-C input breakout, switch cable and bulk capacitor.

## [rev A] - 2026-09-24

### Added
- First enclosure: head, back cover with pressing columns, raked pistol grip, lidar check template.
