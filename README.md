# Marvin

**A small desk companion that sees the room five ways at once, and knows how you are doing.**

Marvin is an open-source robot for your desk. A 360° lidar turns on top of its head, a camera and a face live behind a black glass window, and two mmWave radars (24 GHz and 60 GHz) hide behind the band around its body. It hears you through a microphone and answers through a speaker in its side.

It can **see** (camera, lidar), **feel** (who is in the room, how they move, and your breathing and heart rate when you sit in front of it), **hear** (microphone) and **speak** (speaker, expressive eyes). The heavy thinking runs on your computer, which fuses everything into one live model of the room.

<p align="center">
  <img src="docs/images/robot_threequarter.png" alt="The Marvin robot: an upright warm-grey body with an anthracite band, a slightly wider head with a black glass face showing two eyes, an orange knob on the side and a lidar turret on top" width="280">
  <img src="docs/images/robot_front.png" alt="Front view of the robot with a content expression on its face" width="300">
</p>

> Français : [lire en français](README.fr.md) · Presentation page: open [`docs/index.html`](docs/index.html) in a browser.

> **Project status: rev F, parts for the first build ordered (arriving early October), nothing built yet.** The body half of the CAD is designed and printable ([`hardware/cad/marvin.scad`](hardware/cad/marvin.scad)): body, plinth, sensor sled and TPU neck. The head follows once the screen is measured. The firmware is written and tested on the computer and in simulation, and a Wemos D1 mini runs it today with simulated sensors; its real sensor, screen, audio and camera code has not run on the real hardware yet. The host software (fusion, presence events, the app, the local voice) runs today. The rev E lighthouse is archived in [`hardware/archive/rev-e/`](hardware/archive/rev-e/). The wiring is unchanged since rev D.

## What we want to do

Every sensor on its own is half-blind. A lidar draws perfect walls but cannot tell a person from a coat rack. A radar knows who moves and who breathes, but it cannot draw the room. A camera sees everything and understands nothing about distance. Marvin puts them in one small body, on one clock, and gives the result a personality.

1. **Capture: the robot is dumb on purpose.** The ESP32-S3 reads every sensor, stamps each packet with the same clock and streams it over Wi-Fi. It draws its own eyes and plays its own sounds.
2. **Fuse: the computer does the thinking.** It places every measurement in the same 3D frame (lidar geometry, radar people and speeds, vital signs, camera colour) and shows one live overlay in [Rerun](https://rerun.io).
3. **Live: a companion that notices.** It looks at you when you sit down, reminds you to take a break after about 50 minutes seated, shows your breathing and heart rate (measured, not diagnosed), and answers your questions with what its sensors tell it. No cloud is required, and no image needs to leave the room; the one optional exception is the weather tool, which sends a place name to Open-Meteo and can be switched off.

## Designed around the sensors

The robot stands upright on the desk; **the sensors are tilted inside it**, not the body.

- **Heart rate at the desk.** The 60 GHz radar sits in the body on a sled tilted 20° up, so its beam lands on the chest of someone seated 0.65–1.1 m away. It looks through a flat 1.6 mm PETG wall, half a wavelength at 60 GHz; crossing it at 20° costs almost nothing.
- **Room tracking.** The 24 GHz radar sits just below it, tilted 10°, covering ±60° of the room. Below, not above: that way it never shadows the 60 GHz beam.
- **Face and camera.** The screen stands vertical behind a smoked acrylic window; the camera is tilted 20° so it frames your face, not your chest.
- **Lidar on top**, with nothing above its laser plane. On a desk, your monitor and the wall will hide part of its 360° scan; it maps the whole room best from a corner or a shelf.
- **Quiet for the radar.** Head and body are joined through a TPU damper, so the lidar motor does not shake the heart-rate radar.
- **Compact.** About 88 × 80 mm on the desk, about 15 cm tall with the lidar.

<p align="center">
  <img src="docs/images/robot_section.png" alt="Section and desk diagram: the upright shell with the sensors tilted inside, and the 60 GHz radar beam reaching the chest of a person seated 0.7 m away" width="760">
</p>

## What each part adds

| Part | Band | Its role |
|---|---|---|
| LDROBOT **D800** lidar (STL-27L) | 905 nm laser | 360° outline of the room, 21 600 points/s, up to 25 m. The cheaper D500 also fits. |
| Hi-Link **HLK-LD2450** | 24 GHz radar | Position and speed of up to 3 people, up to 6 m |
| Seeed **MR60BHA2** kit | 60 GHz radar | Presence, breathing rate and heart rate, best within 1 m |
| **XIAO ESP32S3 Sense** | Visible light | Camera, microphone, Wi-Fi, and the brain that runs everything |
| Waveshare **1.69" LCD** (ST7789) | — | The face: expressive eyes (a mini-map later) |
| **MAX98357A** amp + 1 W speaker | Sound | The voice: built-in sounds, and spoken answers from the computer |

Heart rate from a radar is consumer-grade: a few beats per minute of error, and only while you stay fairly still. It is not a medical device.

## Design language

The name is also the robot's wake word: "marvin" is one of the keywords in Google's open [Speech Commands](https://arxiv.org/abs/1804.03209) dataset, so a keyword-spotting model can be trained for it without recording a dataset first.


Warm grey shell, anthracite band and face, a single orange knob (volume, and a tap to wake it). No stickers, no cartoon features: the personality comes from the eyes and from how it behaves.

## How it works

```mermaid
flowchart LR
  subgraph Robot["Marvin robot"]
    LID["D800 lidar<br/>UART 921600"] --> ESP["XIAO ESP32S3 Sense<br/>camera + mic + Wi-Fi<br/>face + voice"]
    LD2450["HLK-LD2450<br/>UART 256000"] <--> ESP
    MR60["MR60BHA2 kit<br/>(own ESP32-C6)"]
  end
  PWR["USB-C 5 V"] --> ESP
  ESP -->|5 V| LID & LD2450 & MR60
  ESP -->|"Wi-Fi: UDP frames + MJPEG + audio"| PC["Computer<br/>fusion + companion brain"]
  MR60 -->|"Wi-Fi: vital signs"| PC
```

See [docs/architecture.md](docs/architecture.md) for data rates, the power budget and the reasoning behind each choice.

## Build it

1. **Buy the parts** in [docs/bom.md](docs/bom.md): about €230 with the D800 lidar (about €190 with the D500).
2. **Print** following [docs/printing.md](docs/printing.md). Print the 5-minute lidar template first.
3. **Solder** two header strips, then **wire** the 29 connections in [docs/wiring.md](docs/wiring.md). The guide is written for first-timers.
4. **Assemble** following [docs/assembly.md](docs/assembly.md).
5. **Flash and run.** The [firmware](firmware/) and the [host software](host/) already talk to each other with simulated sensors (a Wemos D1 mini is enough to try the firmware); the real-sensor firmware waits for the first build. The host turns what it sees into events, a face, a local voice and an app for your phone: `./marvin up` starts it (`./marvin demo` without a robot, `./marvin doctor` says what is missing). The Python host's `marvin-host` commands stay as tools: the simulator, the viewer, replays.

The body parts are ready to print; the head parts arrive with the head CAD.

## Repository layout

```
marvin/
├── hardware/
│   ├── cad/                      # parametric OpenSCAD source (marvin.scad, rev F)
│   ├── stl/                      # print-ready STLs (generated)
│   ├── bambu/                    # Bambu Studio project, A1 + AMS lite (generated)
│   ├── blender/                  # assembled model + print layout (generated)
│   ├── scripts/                  # export_stl.sh, render_previews.sh, section_diagram.py, build_3mf.py, build_blend.sh
│   └── archive/                  # earlier revisions (rev E: Fanou the lighthouse)
├── firmware/                     # ESP32 firmware (prototype)
├── host/                         # Python: simulator, viewer, replay tools, the voice sidecar (and the first host)
├── host-java/                    # the host core (Java): ./marvin runs it
├── docs/                         # BOM, wiring, printing, assembly, architecture
│   ├── protocol.md               # robot ↔ host UDP protocol
│   ├── design.md                 # Marvin's brain: next-generation host design (memory, agents, Java core)
│   ├── concepts/                 # design studies, from the lighthouse to the robot
│   └── index.html                # presentation page (open in a browser)
└── LICENSES/                     # full licence texts
```

## Licences

| What | Licence |
|---|---|
| Hardware (`hardware/`) | [CERN-OHL-P-2.0](LICENSES/CERN-OHL-P-2.0.txt) |
| Software (`firmware/`, `host/`, `host-java/`, scripts) | [MIT](LICENSES/MIT.txt) |
| Documentation and images (`docs/`, READMEs) | [CC BY 4.0](LICENSES/CC-BY-4.0.txt) |

All three are permissive: build it, modify it, sell it. Just keep the attribution.

## Contributing

Issues and pull requests are welcome. Build reports with photos are especially useful while no unit has been built yet. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Credits

Mechanical dimensions come from the LDROBOT LD19 datasheet (v2.5, same footprint as the STL-19P and STL-27L in the D500 and D800 kits), the Waveshare 1.69" LCD documentation, the Hi-Link HLK-LD2450 manual, the Seeed MR60BHA2 datasheet and schematic, and the Seeed XIAO ESP32S3 Sense documentation. Sensor names are trademarks of their respective owners. This project is not affiliated with any of them.
