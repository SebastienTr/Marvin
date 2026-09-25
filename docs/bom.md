# Bill of materials

Prices are indicative. They were checked on AliExpress in September 2026 and move often. Pick the best-rated listing rather than the cheapest one, and allow 2–4 weeks for delivery.

**Total: about €240 in parts with the D800 lidar, or about €200 with the D500.** You provide the USB-C power source: a phone charger or a power bank.

This list is the same as rev D except for the small hardware (screws, nut, feet) and the USB-C cable, which needs a right-angle plug.

## Sensors and controller

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [Seeed XIAO ESP32S3 Sense](https://fr.aliexpress.com/w/wholesale-xiao-esp32s3-sense.html) (Seeed store) | 1 | Controller, camera (OV3660), **built-in PDM microphone** and Wi-Fi. It ships with loose header pins to solder. The [pre-soldered version](https://www.seeedstudio.com/Seeed-Studio-XIAO-ESP32S3-Sense-Pre-Soldered-p-6335.html) sold by Seeed avoids that step. | 16 |
| [LDROBOT **D800** lidar kit (STL-27L)](https://fr.aliexpress.com/item/1005010728254952.html), variant "D800 lidar" | 1 | Reference lidar: 360°, 25 m, 21 600 points/s, UART at 921 600 baud. Its footprint (54 × 46.29 mm) matches the LD19 family; check it on the printed template. | 111 |
| *Cheaper option:* same listing, variant "D500 lidar kit" (STL-19P) | — | 12 m, 5 000 points/s, UART at 230 400 baud, same footprint and connector. | 68 |
| [Hi-Link HLK-LD2450, KIT-A](https://fr.aliexpress.com/item/1005007316768708.html) (official Hi-Link store) | 1 | 24 GHz radar, tracks up to 3 people. KIT-A ships with its cable. | 8 |
| [Seeed MR60BHA2 kit with XIAO ESP32C6](https://fr.aliexpress.com/item/1005008715574827.html) | 1 | 60 GHz radar for breathing and heart rate. It has its own ESP32-C6 and sends data over Wi-Fi. Fanou holds it behind the arched door, in Seeed's 54 × 35 × 22 mm case, which you print from [Seeed's published files](https://www.printables.com/model/1326287-3d-print-enclosure-for-xiao-60ghz-mmwave-sensors-m). Seeed sells it directly for less. | 47 |
| [Waveshare 1.69" LCD module, 240×280, ST7789V2](https://fr.aliexpress.com/item/1005007287851933.html) | 1 | Fanou's face: expressive eyes, and a mini-map or status on demand. Ships with a cable ending in Dupont plugs. | 15 |

## Audio

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [MAX98357A I2S amplifier module](https://fr.aliexpress.com/w/wholesale-max98357a.html) | 1 | 3 W class-D mono amp, driven digitally over I2S by three ESP32 pins. Most boards ship with a loose header and a screw terminal. | 2 |
| [2030 cavity speaker, 8 Ω 1 W](https://fr.aliexpress.com/w/wholesale-2030-speaker-8ohm-1w.html) | 1 | 20 × 30 mm speaker in its own small box. It sits in a cradle on the bottom plate, under the grille. | 2 |

The microphone is already on the XIAO Sense board: nothing to buy.

## Wires and connectors

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [JST-ZH 1.5 mm 4-pin → Dupont female cable](https://fr.aliexpress.com/w/wholesale-zh1.5-4pin-to-dupont-female.html) | 1 | Lidar to ESP32. | 4 |
| [Dupont jumper wires, female–female, 20 cm](https://fr.aliexpress.com/item/1005012095727159.html) | 1 lot | ESP32 power pins to the lever connectors, and the amp's I2S lines. | 2 |
| [WAGO 221-415 lever connector (5 ports)](https://fr.aliexpress.com/item/1005012951077248.html), pack of 2 | 2 packs | Four buses: 5 V, GND-A, GND-B, 3V3. No tools needed. | 12 |
| [USB-C male pigtail, 2 wires](https://www.aliexpress.com/w/wholesale-usb-c-male-pigtail-2-wire.html) | 1 | Powers the MR60BHA2 kit from the 5 V bus. | 2 |
| [USB-C cable with a **90° (right-angle) plug**](https://fr.aliexpress.com/w/wholesale-usb-c-cable-90-degree.html), 1–1.5 m | 1 | Runs from the back of the island up inside the tower to the XIAO. The right-angle end plugs into the XIAO, the straight end must pass through the 14 × 9 mm hole in the island. The other end goes to a charger, a power bank or your computer (for flashing). | 4 |

## Hardware and small parts

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| M2.5×10 screws | 3 | Lidar on the lantern plinth. They thread-form into the plastic: no nuts. | from your kit |
| M3×12 button-head screws | 3 | Gallery cap, driven radially through the tower top. | from your kit |
| M3×10 button-head screws | 4 | Bottom plate into the island. | from your kit |
| M3×6 screws | 2 | Spine foot, driven from inside the island. | from your kit |
| [1/4"-20 hex nut](https://www.aliexpress.com/w/wholesale-1%2F4-20-hex-nut.html) | 1 | Optional tripod thread in the bottom plate. | 3 |
| [Double-sided foam tape, 3 mm](https://www.aliexpress.com/w/wholesale-double-sided-foam-tape-3mm.html) | 1 | Pads that press the modules against the wall, and hold the WAGOs, the amp and the speaker. | 5 |
| [Silicone bumper feet](https://www.aliexpress.com/w/wholesale-silicone-bumper-feet-10mm.html) | 4 | Under the bottom plate, so Fanou does not slide and the speaker grille can breathe. | 2 |

## Tools

| Tool | Needed? | ≈ € |
|---|---|---:|
| [Automatic wire stripper](https://fr.aliexpress.com/item/1005007540063503.html) | Yes | 8 |
| Soldering iron (e.g. Pinecil) and 0.6 mm solder | Yes, for the two header strips. Not needed if you buy both boards pre-soldered. | 35 |
| Small Phillips/hex screwdriver | Yes | — |
| Multimeter | Recommended, to check the buses before plugging modules in | 10 |

## Power requirements

- USB-C, 5 V, **at least 2 A**. Any phone charger or modern power bank qualifies.
- At about 1 A average draw, a 10 000 mAh power bank lasts roughly 5 hours. On a desk, a wall charger is simpler.
- To flash the firmware, plug the same cable into your computer instead. Never connect both at once.
