# Bill of materials

Prices are indicative. They were checked in September 2026 and move often. Pick the best-rated listing rather than the cheapest one, and allow 2–4 weeks for delivery.

**Total: about €205 in parts with the D800 lidar, or about €165 with the D500** (prices checked 26 September 2026), in two orders: the MR60BHA2 kit from Seeed Studio, everything else from AliExpress. You provide the USB-C power source: a phone charger or a power bank.

Rev F (the upright robot) uses exactly the same electronics as rev D and rev E. It only adds a smoked acrylic face window and a little TPU for the vibration damper.

## Sensors and controller

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [Seeed XIAO ESP32S3 Sense](https://fr.aliexpress.com/item/1005006988111963.html) (Seeed official store, +€5.44 shipping) | 1 | Controller, camera (OV3660), **built-in PDM microphone** and Wi-Fi. It sits in the head, tilted 20° so the camera sees your face. Ships with loose header pins to solder; the [pre-soldered version](https://www.seeedstudio.com/Seeed-Studio-XIAO-ESP32S3-Sense-Pre-Soldered-p-6335.html) avoids that step. | 16 |
| [LDROBOT **D800** lidar kit (STL-27L)](https://fr.aliexpress.com/item/1005010728254952.html), variant "D800 lidar" | 1 | Reference lidar: 360°, 25 m, 21 600 points/s, UART at 921 600 baud. It sits on top of the head. Its footprint (54 × 46.29 mm) matches the LD19 family; check it on the printed template. | 111 |
| *Cheaper option:* same listing, variant "D500 lidar kit" (STL-19P) | — | 12 m, 5 000 points/s, UART at 230 400 baud, same footprint and connector. | 68 |
| [Hi-Link HLK-LD2450, KIT-A](https://fr.aliexpress.com/item/1005007316768708.html) (official Hi-Link store) | 1 | 24 GHz radar, tracks up to 3 people. Behind the body band, tilted 10°. KIT-A ships with its cable. | 8 |
| [Seeed MR60BHA2 kit with XIAO ESP32C6](https://www.seeedstudio.com/MR60BHA2-60GHz-mmWave-Sensor-Breathing-and-Heartbeat-Module-p-5945.html) (Seeed Studio, Germany warehouse) | 1 | 60 GHz radar for breathing and heart rate. Behind the body band, on a sled tilted 20° towards the chest of a seated person. It has its own ESP32-C6 and sends data over Wi-Fi. Kept in Seeed's 54 × 35 × 22 mm case, printed from [Seeed's published files](https://www.printables.com/model/1326287-3d-print-enclosure-for-xiao-60ghz-mmwave-sensors-m). About half the AliExpress price when bought from Seeed. | 29 |
| [1.69" IPS LCD module, 240×280, ST7789](https://fr.aliexpress.com/item/1005007987731480.html), variant "1.69 Inch" | 1 | The face: expressive eyes behind the smoked window, and a mini-map or status on demand. Generic SPI module (the Waveshare one works too, for €15). Measure its board and mounting holes before printing the head frame. | 3 |
| *Optional upgrade:* 2.0" ST7789 LCD, 240×320 | — | Same driver and wiring, fills more of the face window. Change `lcd*` in the CAD if you use it. | 7 |

## Audio

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [MAX98357A I2S amplifier module](https://fr.aliexpress.com/w/wholesale-max98357a.html) | 1 | 3 W class-D mono amp, driven digitally over I2S by three ESP32 pins. Most boards ship with a loose header and a screw terminal. | 2 |
| [2030 cavity speaker, 8 Ω 1 W](https://fr.aliexpress.com/w/wholesale-2030-speaker-8ohm-1w.html) | 1 | 20 × 30 mm speaker in its own small box, behind the dot grille on the right side of the body. | 2 |

The microphone is already on the XIAO Sense board: nothing to buy.

## Wires and connectors

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [JST-ZH 1.5 mm 4-pin → Dupont female cables](https://fr.aliexpress.com/item/1005005840702851.html), variant "ZH1.5 to Dupont / 4Pin 10 pieces" | 1 pack | Lidar to ESP32. | 5 |
| [Dupont jumper wires, female–female, 20 cm](https://fr.aliexpress.com/item/1005012095727159.html) | 1 lot | ESP32 power pins to the lever connectors, the amp's I2S lines, and the runs through the neck. | 2 |
| [5-port lever connectors, 221-415 type](https://fr.aliexpress.com/item/1005007171193407.html), variant "10pcs-415-Bag" | 1 bag of 10 | Four buses: 5 V, GND-A, GND-B, 3V3 (0.15–4 mm² wire). No tools needed. | 3 |
| [USB-C male pigtail, 2 wires, 90° plug](https://fr.aliexpress.com/item/1005010089226136.html) | 1 | Powers the MR60BHA2 kit from the 5 V bus; the right-angle plug fits under the kit. | 2 |
| [USB-C cable with a **90° (right-angle) plug**](https://fr.aliexpress.com/item/1005008765247136.html), single elbow, 1 m | 1 | Enters at the back of the body and climbs through the neck to the XIAO in the head. The other end goes to a charger, a power bank or your computer (for flashing). | 3 |

## Enclosure extras

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [Tinted acrylic sheet, 10 × 20 cm, 2.3 mm](https://fr.aliexpress.com/item/1005006782700043.html), variant "Transparent Black" | 1 | The black-glass face window, about 74 × 44 mm (the CAD uses 2.3 mm). Cut with a fine saw or score-and-snap, then file the corners round. Black PETG would hide the screen. | 5 |
| [TPU filament 95A, 200 g](https://fr.aliexpress.com/item/1005008589285483.html), variant "Black 200g" | ~15 g used | The neck damper between head and body, and the feet. It keeps the lidar motor's vibration away from the 60 GHz radar. Print it on the A1 outside the AMS. | 13 |
| M2.5×10 screws | 3 | Lidar on top of the head. They thread-form into the plastic: no nuts. | from your kit |
| M3 button-head screws | ~8 | Head and body closures. Exact lengths come with the CAD. | from your kit |
| [3 mm double-sided foam strips](https://fr.aliexpress.com/item/1005008290401897.html), variant "Square 20X150mm 5pcs" | 1 | Cut into pads that hold the modules on their sleds, and the WAGOs, amp and speaker. | 2 |

## Tools

| Tool | Needed? | ≈ € |
|---|---|---:|
| [Automatic wire stripper](https://fr.aliexpress.com/item/1005007540063503.html) | Yes | 8 |
| Soldering iron and [0.6 mm solder](https://fr.aliexpress.com/item/1005005618101831.html) | Yes, for the two header strips. | 4 (solder) |
| Small Phillips/hex screwdriver | Yes | — |
| Fine saw or cutter, file | Yes, for the acrylic window | — |
| [Multimeter (ANENG SZ308)](https://fr.aliexpress.com/item/1005007097120431.html) | Recommended, to check the buses before plugging modules in | 6 |

## Power requirements

- USB-C, 5 V, **at least 2 A**. Any phone charger or modern power bank qualifies.
- At about 1 A average draw, a 10 000 mAh power bank lasts roughly 5 hours. On a desk, a wall charger is simpler.
- To flash the firmware, plug the same cable into your computer instead. Never connect both at once.

## Also in the cart (not needed for SuperLens)

The September 2026 order also includes an ESP32 starter kit (breadboard, LEDs, resistors, sensors) and an ESP32-S3 N16R8 dev board, for other projects. They share the AliExpress shipping.
