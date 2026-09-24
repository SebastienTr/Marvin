# Bill of materials

Prices are indicative. They were checked on AliExpress in September 2026 and move often. Pick the best-rated listing rather than the cheapest one, and allow 2–4 weeks for delivery.

**Total: about €240 in parts with the D800 lidar, or about €200 with the D500.** You provide the USB-C power bank, which stays in your pocket.

## Sensors and controller

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [Seeed XIAO ESP32S3 Sense](https://fr.aliexpress.com/w/wholesale-xiao-esp32s3-sense.html) (Seeed store) | 1 | Controller, camera (OV3660), **built-in PDM microphone** and Wi-Fi. It ships with loose header pins to solder. The [pre-soldered version](https://www.seeedstudio.com/Seeed-Studio-XIAO-ESP32S3-Sense-Pre-Soldered-p-6335.html) sold by Seeed avoids that step. | 16 |
| [LDROBOT **D800** lidar kit (STL-27L)](https://fr.aliexpress.com/item/1005010728254952.html), variant "D800 lidar" | 1 | Reference lidar: 360°, 25 m, 21 600 points/s, UART at 921 600 baud. Its footprint (54 × 46.29 mm) matches the LD19 family; check it on the printed template. | 111 |
| *Cheaper option:* same listing, variant "D500 lidar kit" (STL-19P) | — | 12 m, 5 000 points/s, UART at 230 400 baud, same footprint and connector. | 68 |
| [Hi-Link HLK-LD2450, KIT-A](https://fr.aliexpress.com/item/1005007316768708.html) (official Hi-Link store) | 1 | 24 GHz radar, tracks up to 3 people. KIT-A ships with its cable. | 8 |
| [Seeed MR60BHA2 kit with XIAO ESP32C6](https://fr.aliexpress.com/item/1005008715574827.html) | 1 | 60 GHz radar for breathing and heart rate. It has its own ESP32-C6 and sends data over Wi-Fi. The head holds it in Seeed's 54 × 35 × 22 mm case, which you print from [Seeed's published files](https://www.printables.com/model/1326287-3d-print-enclosure-for-xiao-60ghz-mmwave-sensors-m). Seeed sells it directly for less. | 47 |
| [Waveshare 1.69" LCD module, 240×280, ST7789V2](https://fr.aliexpress.com/item/1005007287851933.html) | 1 | Back screen: live top-down lidar map, radar targets and status. Ships with a cable ending in Dupont plugs. | 15 |

## Audio

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [MAX98357A I2S amplifier module](https://fr.aliexpress.com/w/wholesale-max98357a.html) | 1 | 3 W class-D mono amp, driven digitally over I2S by three ESP32 pins. Most boards ship with a loose header and a screw terminal. | 2 |
| [2030 cavity speaker, 8 Ω 1 W](https://fr.aliexpress.com/w/wholesale-2030-speaker-8ohm-1w.html) | 1 | 20 × 30 mm speaker in its own small box. It sits in a cradle on the left wall, behind the side vents. | 2 |

The microphone is already on the XIAO Sense board: nothing to buy.

## Wires and connectors

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [JST-ZH 1.5 mm 4-pin → Dupont female cable](https://fr.aliexpress.com/w/wholesale-zh1.5-4pin-to-dupont-female.html) | 1 | Lidar to ESP32. | 4 |
| [Dupont jumper wires, female–female, 20 cm](https://fr.aliexpress.com/item/1005012095727159.html) | 1 lot | ESP32 power pins to the lever connectors, and the amp's I2S lines. | 2 |
| [WAGO 221-415 lever connector (5 ports)](https://fr.aliexpress.com/item/1005012951077248.html), pack of 2 | 2 packs | Four buses: 5 V, GND-A, GND-B, 3V3. No tools needed. | 12 |
| [USB-C male pigtail, 2 wires](https://www.aliexpress.com/w/wholesale-usb-c-male-pigtail-2-wire.html) | 1 | Powers the MR60BHA2 kit from the 5 V bus. | 2 |
| USB-C to USB-C cable, **1–1.5 m**, slim plug | 1 | From the power bank in your pocket to the ESP32. The plug must pass through a 14 × 9 mm hole. | 3 |

## Hardware and small parts

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| M3 button-head screws | 6 | 4 × M3×10 for the cover, 2 × M3×12 for the grip. They thread-form into the plastic. | 4 |
| M2.5 screws and nuts | 3 + 3 | M2.5×10 screws and M2.5 nuts for the lidar. | 3 |
| [1/4"-20 hex nut](https://www.aliexpress.com/w/wholesale-1%2F4-20-hex-nut.html) | 1 | Tripod thread in the grip butt. | 3 |
| [Double-sided foam tape, 3 mm](https://www.aliexpress.com/w/wholesale-double-sided-foam-tape-3mm.html) | 1 | Pads that press the modules against the front, and hold the speaker and the amp. | 5 |
| [Silicone bumper feet](https://www.aliexpress.com/w/wholesale-silicone-bumper-feet-10mm.html) | 4 | Stops the stand from sliding. | 2 |

## Tools

| Tool | Needed? | ≈ € |
|---|---|---:|
| [Automatic wire stripper](https://fr.aliexpress.com/item/1005007540063503.html) | Yes | 8 |
| Soldering iron (e.g. Pinecil) and 0.6 mm solder | Yes, for the two header strips. Not needed if you buy both boards pre-soldered. | 35 |
| Small Phillips/hex screwdriver | Yes | — |
| Multimeter | Recommended, to check the buses before plugging modules in | 10 |

## Power bank requirements

- USB-C output, 5 V, **at least 2 A**. Most modern power banks qualify.
- Any size: it stays in your pocket, and the back of the device is taken by the screen.
- At about 1 A average draw, a 10 000 mAh bank lasts roughly 5 hours.
