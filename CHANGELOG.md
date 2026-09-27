# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Each hardware revision is a git tag (`rev-B` to `rev-E`; rev A predates the repository). Rev F will be tagged once the head is designed and the first unit is built.

## [Unreleased]

### Software prototype (2026-09-27)
- `docs/protocol.md`: robot → host UDP protocol v1. Zero-configuration discovery (`HELLO` broadcast, `HOST_ACK`), raw lidar and LD2450 frames in a 16-byte envelope.
- `host/`: the `marvin-host` Python package. Parsers for LDROBOT and HLK-LD2450 frames, sensor extrinsics, UDP receiver with loss counting, live Rerun view and recording, and a simulated robot (room, walking person, D500 or D800 lidar). `marvin-host demo` runs everything on one computer. Tests with `pytest`.
- Simulated home office (`host/marvin_host/scene.py`, shared with the firmware through a generated header): walls, furniture at their real heights, and a person who comes in, sits at the desk and leaves. Simulated camera rendering the same room, and MR60BHA2 vital signs (`VITALS` message).
- Viewer layout: 3D, camera with lidar and radar projected onto the image, lidar top view, radar-style mmWave view, presence and vital-sign time series.
- The brain (`host/marvin_host/brain.py`): presence, seating, stillness and vital-sign reliability as debounced events (`events.py`), shown in the viewer. The simulated MR60BHA2 now drops out while the person fidgets, as the real one does.
- The face (`host/marvin_host/face.py`, `docs/face.md`): the robot's eyes as a reference renderer for the 240 × 280 screen, following the person, blinking, reacting to events and falling asleep; shown live in the viewer.
- Real sensors (`firmware/src/sensor_framing.*`, `sensors_real.*`): resynchronising framers for the LDROBOT lidar and the HLK-LD2450 (with its multi-target init sequence), UART drivers for the XIAO ESP32-S3; `xiao_esp32s3` builds the real robot, `xiao_esp32s3_sim` keeps simulated sensors. Unity tests run on the PC (`pio test -e native`).
- The face on the robot (`firmware/src/face/`): C++ port of the face, identical to the Python reference frame for frame, a small ST7789 driver sending only what changed, and host → robot messages `FACE_STATE` and `FACE_EVENT` (`host/marvin_host/link.py`).
- `firmware/mr60_bridge/`: firmware for the MR60BHA2 kit's ESP32-C6 that sends its vital signs to the host (`VITALS`, board 4).
- The app (`host/marvin_host/ui/`, `docs/ui.md`): a local web page for computer and phone with the live face, today's timeline, breaks, 7-day history and settings; events and daily stats kept in SQLite on the computer. `marvin-host ui --demo` shows it with a simulated robot.
- The voice (`host/marvin_host/voice/`, `docs/voice.md`): "Marvin" wake word, Whisper speech-to-text, a local LLM through Ollama and macOS/Piper speech, with the brain's context and break reminders; all local. `marvin-host talk`, `marvin-host run --voice`.
- Audio and camera on the robot (`firmware/src/audio/`, `firmware/src/camera/`, `docs/audio.md`): I2S speaker with earcons and streamed speech, PDM microphone streamed to the host, MJPEG camera on port 81 shown in the viewer; protocol messages `AUDIO_IN`, `AUDIO_OUT`, `AUDIO_CTRL`, `SOUND` and HELLO capability flags.
- Tools (`docs/tools.md`): wireless firmware updates (OTA, `xiao_esp32s3_ota`), raw session recording and replay (`.mvrec`), automatic or manual lidar yaw calibration.
- PlatformIO platforms are pinned (espressif32 7.1.3, espressif8266 4.2.1).
- `firmware/`: first PlatformIO firmware. Wi-Fi, host discovery and streaming with simulated sensors, on the Wemos D1 mini (ESP8266), the ESP32-S3 DevKitC and the XIAO ESP32S3.

### Rev F CAD, body half (2026-09-27)
- `hardware/cad/marvin.scad`: body shell (printed upside down, 1.6 mm radome wall, anthracite band by filament change), plinth, sensor sled, TPU neck gasket, grommets and feet, lidar template. Print-ready STLs in `hardware/stl/`.
- The 24 GHz radar now sits **below** the 60 GHz radar (face centres at 16 mm and 42 mm): above it, it would have shadowed the upper part of the 60 GHz beam.
- The head sits on the body through a TPU gasket and four TPU grommets; no screw links them rigidly.
- `hardware/scripts/section_diagram.py` draws the section diagram from the CAD values.
- Rev E (Fanou) sources, STLs and Blender file moved to `hardware/archive/rev-e/`.
- `hardware/blender/marvin.blend`: the body half from the CAD (band painted), the modules, a concept stand-in for the head, and the print plates. Built by `hardware/scripts/build_blend.sh`.
- `hardware/bambu/marvin_A1.3mf`: Bambu Studio project for the A1 + AMS lite (4 plates, colours, per-part settings, band colour change). Built by `hardware/scripts/build_3mf.py` with the Bambu Studio command line.

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
