# Bill of materials

Prices are indicative. They were checked on AliExpress in September 2026 and move often. Pick the best-rated listing rather than the cheapest one, and allow 2–4 weeks for delivery.

**Total: about €166 in parts, plus a wire stripper.** You provide the USB-C power bank.

## Sensors and controller

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [Seeed XIAO ESP32S3 Sense, **pre-soldered**](https://www.seeedstudio.com/Seeed-Studio-XIAO-ESP32S3-Sense-Pre-Soldered-p-6335.html) | 1 | Controller, camera (OV3660) and Wi-Fi. The pre-soldered variant is what makes the build solder-free. | 20 |
| [LDROBOT LD19 lidar (D300 kit)](https://www.aliexpress.com/item/1005004295339153.html) | 1 | 360° 2D lidar, 12 m. The kit's USB adapter lets you test it on a computer first. | 85 |
| [Hi-Link HLK-LD2450, with cable](https://www.aliexpress.com/item/1005005854122655.html) | 1 | 24 GHz radar, tracks up to 3 people. Choose the option that ships with its cable. | 8 |
| [Seeed MR60BHA2 kit](https://www.aliexpress.com/item/1005008083283257.html) | 1 | 60 GHz radar for breathing and heart rate. It has its own ESP32-C6 and sends data over Wi-Fi. | 28 |

## Wires and connectors

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [JST-ZH 1.5 mm 4-pin → Dupont female cable](https://www.aliexpress.com/w/wholesale-zh1.5-4pin-to-dupont-female-cable.html) | 1 | Lidar to ESP32. | 2 |
| [Dupont jumper wires, female–female, 20 cm](https://www.aliexpress.com/w/wholesale-dupont-jumper-wire-female-female-20cm.html) | 2 | ESP32 5V and GND to the lever connectors. | 2 |
| [WAGO 221-415 lever connector (5 ports)](https://www.aliexpress.com/w/wholesale-wago-221-415.html) | 2 | One 5 V bus and one GND bus. No tools needed. | 3 |
| [USB-C male pigtail, 2 wires](https://www.aliexpress.com/w/wholesale-usb-c-male-pigtail-2-wire.html) | 1 | Powers the MR60BHA2 kit from the 5 V bus. | 2 |
| USB-C to USB-C cable, 30–50 cm, slim plug | 1 | Power bank to ESP32. The plug must pass through a 14 × 9 mm hole. | 3 |

## Hardware and small parts

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [M3 button-head screws (kit)](https://www.aliexpress.com/w/wholesale-m3-button-head-screw-kit.html) | 6 | 4 × M3×10 for the cover, 2 × M3×12 for the grip. They thread-form into the plastic. | 4 |
| [M2.5 screws and nuts (kit)](https://www.aliexpress.com/w/wholesale-m2.5-screw-nut-kit.html) | 3 + 3 | M2.5×10 screws and M2.5 nuts for the lidar. | 3 |
| [1/4"-20 hex nut](https://www.aliexpress.com/w/wholesale-1%2F4-20-hex-nut.html) | 1 | Tripod thread in the grip butt. | 1 |
| [Double-sided foam tape, 3 mm](https://www.aliexpress.com/w/wholesale-double-sided-foam-tape-3mm.html) | 1 | Pads that press the modules against the front. | 2 |
| [20 mm hook-and-loop strap](https://www.aliexpress.com/w/wholesale-velcro-strap-20mm.html) | 1 | Holds the power bank on the back. | 2 |
| [Silicone bumper feet, Ø10](https://www.aliexpress.com/w/wholesale-silicone-bumper-feet-10mm.html) | 4 | Stops the stand from sliding. | 1 |

## Tools

| Tool | Needed? | ≈ € |
|---|---|---:|
| [Automatic wire stripper](https://www.aliexpress.com/w/wholesale-automatic-wire-stripper.html) | Yes | 8 |
| Small Phillips/hex screwdriver | Yes | — |
| Multimeter | Recommended, to check the 5 V bus before plugging modules in | 10 |

## Power bank requirements

- USB-C output, 5 V, **at least 1.5 A**. Almost every modern power bank qualifies.
- At most about 70 mm wide, so it fits between the strap slots.
- At about 0.8 A average draw, a 10 000 mAh bank lasts roughly 6 hours.
