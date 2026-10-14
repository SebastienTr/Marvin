<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Marvin's documentation

Start at the top and go down: each tutorial assumes the one before it. Everything else is reference,
read when you need it. New to electronics? The tutorials are written for you: they say what to type,
what you should see, and what to do when you see something else.

## The tutorials, in order

| # | Tutorial | What you end up with | Hardware needed |
|---|---|---|---|
| 1 | [Run Marvin on your Mac](getting-started.md) | The host and its app running, first with a simulated robot, then for real with a local model | None |
| 2 | [The XIAO bench](xiao-bench.md) | The robot's own electronics on a breadboard, every part checked by the boot self-test | The parts from the [BOM](bom.md) |
| 3 | [Print](printing.md) and [assemble](assembly.md) | A closed robot on your desk | A 3D printer, the bench from tutorial 2 |
| 4 | A first conversation (comes with the first build) | Marvin greets you when you sit down and answers you through its own speaker | The assembled robot |

Then check the whole robot against the [v1 acceptance checklist](test-plan.md) (about 30 minutes).

An older, simpler build is the ["looks" bench](looks-bench.md): a classic ESP32 DevKit, a radar and a servo
head that turns to look at you. It is not part of the robot; it is a good first project if the XIAO bench feels
like too much at once.

## Reference

**The robot**

| Document | What is in it |
|---|---|
| [bom.md](bom.md) | Every part, where to buy it, the price |
| [wiring.md](wiring.md) | The 25 connections of the robot, for first-timers |
| [printing.md](printing.md) | The printed parts, filaments, slicer settings |
| [assembly.md](assembly.md) | How the parts go together |
| [architecture.md](architecture.md) | Why the robot is built this way: data rates, power, coordinate frame, roadmap |
| [protocol.md](protocol.md) | The robot ↔ host UDP protocol (v1) |
| [face.md](face.md) | The eyes on the screen |
| [audio.md](audio.md) | The robot's microphone and speaker |
| [tools.md](tools.md) | Over-the-air updates, recordings, calibration |

**The host (the software on your computer)**

| Document | What is in it |
|---|---|
| [ui.md](ui.md) | The app, screen by screen |
| [voice.md](voice.md) | Listening, speaking, the models, latency |
| [vision.md](vision.md) | Marvin looks when you ask |
| [memory.md](memory.md) | What Marvin remembers, and how you correct it |
| [intelligence.md](intelligence.md) | Perception, background tasks, what machine learning adds |
| [design.md](design.md) | The host's design: memory, agents, connectors, Soul; the decisions log |
| [usb-sensors.md](usb-sensors.md) | A radar (or a lidar) on a USB adapter, no ESP32 |

**Benches and side projects** (not part of the robot): [wiring-bench.md](wiring-bench.md) (a classic ESP32 with
OLED eyes and buttons), [looks-bench.md](looks-bench.md), [concepts/](concepts/) (the design studies, from the
lighthouse to the robot).

**For contributors**: [CONTRIBUTING.md](../CONTRIBUTING.md), [the firmware](../firmware/README.md),
[the Python host](../host/README.md), [the Java host](../host-java/README.md),
[the v1 acceptance checklist](test-plan.md), and the archived [review guide](review-guide.md) of the move to
the Java host.

The presentation page, [index.html](index.html), is the same project in pictures (open it in a browser).
