# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Each hardware revision is a git tag (`rev-B` to `rev-E`; rev A predates the repository). Rev F will be tagged once the head is designed and the first unit is built.

## [Unreleased]

### USB sensors: the lidar on the computer, no ESP32 (2026-10-04)
- The USB sensor bridge (`host/marvin_host/usb_sensors.py`, `marvin-host usb-sensors`, `docs/usb-sensors.md`): a lidar (D800 / STL-27L at 921600 baud, D500 / STL-19P at 230400) or an HLK-LD2450 (256000) on a USB-serial adapter is found by probing the adapters for valid frames, and sent to the host exactly as a robot board sends it: `HELLO`, `HOST_ACK` and the link timeout as the firmware's `link_step`, 10 lidar packets per datagram with a 20 ms flush, the counters in a `LOG` every 5 s. Unplugged and plugged back in, it comes back as the same device (a MAC made from the adapter's serial number, a fixed source port).
- `host/marvin_host/sensor_framing.py`: the firmware's resynchronising framers (LDROBOT packets, LD2450 frames and ACKs) and LD2450 commands in Python, checked against the firmware's own native tests. pyserial joins the Python host's dependencies.
- Protocol v1, without a version bump: board id 6, *USB sensor bridge (computer)*, in both hosts, `docs/protocol.md`, `protocol.h`'s comment and the protocol vectors (`hello_usb_bridge`).
- Real sensors beat simulated ones: a simulated device's lidar scans are not drawn while a real lidar's are fresh (`SensorScene`), and its radar targets and vital signs do not reach the brain while a real radar is live (`PresenceFeed`).
- `./marvin up` and `restart` start the bridge when an adapter is plugged in (it probes in the background while the host starts, and says what it found), `down` stops it, `status`, `logs` and `doctor` show it, `./marvin usb-sensors` runs it in the foreground (`--detect`: what is plugged in). `MARVIN_USB_SENSORS=off`, `MARVIN_USB_PORTS`.
- The app: the device *USB sensor bridge* ("Real sensors over USB", on this computer, the lidar's rate) in Marvin > Robot; with only sensors that see no one (a lidar), the top bar says *Sensors live · no presence sensor* and the Overview *Sensors only*, instead of *Robot offline*.
- `docs/usb-sensors.md`: the STL-27L on a CP2102 adapter (ZH1.5 pin order; this kit's cable colours, black = TX, green = GND, red = P5V, white = PWM to ground), use, behaviour and troubleshooting.

### Marvin's own voice, and the voice's plumbing (2026-10-01)
- Marvin speaks with its own voice: Qwen3-TTS (0.6B Base) cloning "Malicieux", the voice chosen for it (a French and an English sentence in `host/marvin_host/voice/voices/marvin/`, made once with Qwen3-TTS VoiceDesign), on the Mac's GPU through mlx-audio, streamed, one voice for both languages. Each clause is read in its own language: the Java host cuts answers into clauses and tags them (`SpeechLanguage`: a run of three words of the other language is cut out, isolated anglicisms stay). Piper and `say` stay as fallbacks (one voice per reply, "Marvine" for a French voice). `./marvin up` installs it and downloads the model once; `./marvin doctor` checks it; Marvin > Voice & models offers **Marvin's voice (Qwen3-TTS)**.
- Talk now and barge-in are clean: the voice keeps one lock around its state, so a cut answer can never play a sentence or say "speaking" after the cut; the microphone reopens as soon as the speaker is really silent, keeping what was said meanwhile as pre-roll (the start of the question is never clipped); Talk now while Marvin thinks leaves no empty "interrupted" answer, and what follows replaces it. The longer pause after "et", "parce que"... also works with speculative recognition off.
- Audio devices: the Java host decides once where the voice hears and speaks (the computer, or a robot) and sends the device with its profile (half or full duplex, echo tail, output latency, VAD floor, "too quiet", earcons); a robot linking or leaving swaps the device under the running voice, without restarting it. `devices` in `voice.json` tunes each profile.
- One place for the voice's settings and defaults (`VoiceSettings`, contract version 2: every value sent, `continue_join_s` and `speculate_after_s` exposed; `golden/voice/configure_defaults.json` keeps the sidecar's copies equal).
- One earcon decider (`VoiceChimes`) and one device per earcon: the voice's own (the computer's speaker, the robot's built-in sounds), else a bench board's buzzer; the app chimes only when neither does. No more double chimes.
- Where the time went, to the speaker: the first sound synthesised and when the speaker played it (`first_audio`, `playback_start`), one line per answer in both logs, the **Speaker** stage in the reply inspector; "first word" is when it was heard.
- Retired: the Python host's own conversation (`marvin-host talk`, `run --voice`, its assistant, Ollama client, tools, persona and voice controller, the Python app's voice panel, `buttons.py`). The Python side keeps the voice's audio and models (the sidecar) and its tools (simulator, viewer, recordings, calibration, the demo's week). The API and conversation goldens are now hand-kept specifications.

### Marvin looks when asked (2026-10-01)
- The `look` tool (Java host, `docs/vision.md`): asked what it sees, what you hold or to read your screen, Marvin takes one picture, the model for images describes it in a request of its own, and the answer comes from that description. Offered only while a camera can take a picture, with a persona that says so; without one, Marvin still says it cannot look. The picture is never kept and never leaves the computer.
- Two interchangeable cameras behind one port (`FrameSource`, new `vision` context and `marvin-adapter-camera`): the computer's camera through an `ffmpeg` process started for one picture (AVFoundation on macOS, V4L2 on Linux; the first frames dropped while the exposure settles), and the robot's MJPEG camera (`/capture`, or the first picture of `/stream`). Both give a JPEG of at most 1280 px, as an image shown in Talk.
- The voice setting `camera` (`auto`: the robot's when one with a camera is connected, else the computer's; `computer`; `robot`; `off`), in both hosts and the conversation vectors (the Python host keeps it and ignores it).
- The app: Marvin > Voice & models > **Camera** (which camera, what each can do now and how to fix it, **Test camera** with its picture in the panel), the camera on the Overview, a note each time Marvin takes a picture, and the line in the Log panel. API: `GET /api/camera`, `POST /api/camera/snapshot`, a `camera` event.
- `./marvin doctor` checks for ffmpeg (`brew install ffmpeg`). `e2e/camera.py` walks it in the app with ffmpeg's test pattern as the camera.

### Room sensors on the bench board (2026-09-30)
- `firmware/src/room/` (`MARVIN_HAS_ROOM_SENSORS`, envs `esp32dev` and `esp32s3`): an HC-SR501 PIR (a minute's warm-up, debounced, motion start and end sent at once), a DHT11 read every 5 s in its own task (its own small driver: checksum and range checked, retried, invalid within 12 s when unplugged) and a light sensor (`MARVIN_HAS_LDR`, off until the module is wired). Unity tests in `test/test_room`.
- Protocol v1, without a version bump: `ENVIRONMENT` (0x08, robot → host, 12 bytes: which sensors, motion, warming up, temperature, humidity, light, seconds since the last motion) and `HELLO` flag bit 6 (room sensors). Python and Java hosts and the protocol vectors updated together.
- Presence: on a board whose radar data is simulated, a real, warm PIR is the presence (the simulated person no longer drives the brain): motion is `arrived` at once, 2 minutes without movement is `left`. A live radar always wins. Both hosts (`brain.py`; `Brain.onMotion` and `PresenceFeed`), `motion_presence` in the presence state, the golden recordings' states regenerated.
- The room in Marvin's context ("The room, from your own sensors: 23.4 °C, 48 % humidity, movement just now.", only while the readings are under 2 minutes old; the presence line says a motion sensor noticed movement, not that someone is in front of Marvin), and a Room card on the app's Home. The simulated robot sends a simulated room (marked as such). Both hosts (`room.py`, `persona.py`; `Room`, `RoomState`, `Persona.roomFact`, `LiveState`), the conversation vectors and the API snapshots' `state` updated.
- `docs/wiring-bench.md`: the PIR, the DHT11 and the light sensor on both DevKits, their settings and troubleshooting.

### Bench board (2026-09-30)
- `firmware/src/bench/` (`MARVIN_HAS_BENCH_IO`, envs `esp32dev` and `esp32s3`): Marvin's eyes on a 0.96" SSD1306 OLED (the robot's face at half scale, fed by `FACE_STATE` and `FACE_EVENT`, with moods from the voice's sounds; found again when a loose screen comes back), a Talk and a Mute button, a passive buzzer playing the earcons, and a status LED for Wi-Fi, host search and link (the S3 DevKitC-1's RGB LED on v1.0 and v1.1 boards). U8g2 pinned, built only by these envs. Unity tests in `test/test_bench`.
- Protocol v1, without a version bump: `BUTTON` (0x07, robot → host: button, action) and `HELLO` flags bit 3 (buttons), 4 (buzzer) and 5 (the face on a screen of its own). Python and Java hosts and the protocol vectors updated together.
- Hosts: a Talk press is the app's Talk now, a Mute press mutes or unmutes; the voice's moments (listening, done, error, muted) play as chimes on robots with a buzzer; the face messages go to any device announcing a screen (`host/marvin_host/buttons.py`; `ButtonFeed`, `VoiceChimes` and `BuzzerChimeService` in the Java host).
- `docs/wiring-bench.md`: the pin maps of both DevKits, no soldering, how to flash and what to expect.

### Java host (2026-09-28)
- `host-java/`: the host core in Java 25 and Spring Boot 4.1 (hexagonal, bounded contexts, ArchUnit), built phase by phase from `docs/design.md` and checked against the Python host (`marvin-contracts`: protocol vectors, recordings, API snapshots, face and conversation vectors). It reaches parity with `marvin-host run`: the robot link (UDP protocol v1), the brain, the face, the history in PostgreSQL (the Python host's `marvin.db` imported once), the app unchanged, and the voice: the conversation in Java (Ollama through Spring AI, tools, memory, reminders), the audio loop in the Python voice sidecar it supervises (`host/marvin_host/sidecar/voice`, gRPC `marvin.voice.v1`).
- `./marvin up` is now the way to run Marvin (PostgreSQL, the Java host and its voice); `./marvin demo`, `status`, `logs`, `doctor`, `down`. The `marvin-host` commands stay as tools: simulator, viewer, recording, replay, calibration.

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
