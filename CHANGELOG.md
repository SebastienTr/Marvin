# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
