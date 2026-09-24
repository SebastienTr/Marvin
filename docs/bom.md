# Bill of materials

Prices are indicative. They were checked on AliExpress in September 2026 and move often. Pick the best-rated listing rather than the cheapest one, and allow 2–4 weeks for delivery.

**Total: about €160 in parts, plus a wire stripper.** You provide the USB-C power bank, which stays in your pocket.

## Sensors and controller

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [Seeed XIAO ESP32S3 Sense, **pre-soldered**](https://www.seeedstudio.com/Seeed-Studio-XIAO-ESP32S3-Sense-Pre-Soldered-p-6335.html) | 1 | Controller, camera (OV3660) and Wi-Fi. The pre-soldered variant is what makes the build solder-free. | 20 |
| [LDROBOT **D500** lidar kit (STL-19P)](https://fr.aliexpress.com/item/1005010728254952.html), variant "D500 lidar kit" | 1 | 360° 2D lidar, 12 m, 5 000 points/s. It replaces the LD19 (D300 kit) with the same footprint, connector and protocol, and handles sunlight better. The kit's USB adapter lets you test it on a computer first. | 68 |
| *Option:* [LDROBOT **D800** (STL-27L)](https://fr.aliexpress.com/item/1005010728254952.html), variant "D800 lidar" | — | 25 m, 21 600 points/s, 4× denser. Its footprint (54 × 46.29 mm) should match; check it on the template. Its UART runs at 921 600 baud. | +43 |
| [Hi-Link HLK-LD2450, with cable](https://www.aliexpress.com/item/1005005854122655.html) | 1 | 24 GHz radar, tracks up to 3 people. Choose the option that ships with its cable. | 8 |
| [Seeed MR60BHA2 kit](https://www.aliexpress.com/item/1005008083283257.html) | 1 | 60 GHz radar for breathing and heart rate. It has its own ESP32-C6 and sends data over Wi-Fi. | 28 |
| [Waveshare 1.69" LCD module, 240×280, ST7789V2](https://www.waveshare.com/1.69inch-lcd-module.htm) | 1 | Back screen: live top-down lidar map, radar targets and status. Ships with a cable ending in Dupont plugs. Also sold by the Waveshare store on AliExpress. | 10 |

## Wires and connectors

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [JST-ZH 1.5 mm 4-pin → Dupont female cable](https://www.aliexpress.com/w/wholesale-zh1.5-4pin-to-dupont-female-cable.html) | 1 | Lidar to ESP32. | 2 |
| [Dupont jumper wires, female–female, 20 cm](https://www.aliexpress.com/w/wholesale-dupont-jumper-wire-female-female-20cm.html) | 2 | ESP32 5V and GND to the lever connectors. | 2 |
| [WAGO 221-415 lever connector (5 ports)](https://www.aliexpress.com/w/wholesale-wago-221-415.html) | 2 | One 5 V bus and one GND bus. No tools needed. | 3 |
| [USB-C male pigtail, 2 wires](https://www.aliexpress.com/w/wholesale-usb-c-male-pigtail-2-wire.html) | 1 | Powers the MR60BHA2 kit from the 5 V bus. | 2 |
| USB-C to USB-C cable, **1–1.5 m**, slim plug | 1 | From the power bank in your pocket to the ESP32. The plug must pass through a 14 × 9 mm hole. | 3 |

## Hardware and small parts

| Part | Qty | Purpose | ≈ € |
|---|---:|---|---:|
| [M3 button-head screws (kit)](https://www.aliexpress.com/w/wholesale-m3-button-head-screw-kit.html) | 6 | 4 × M3×10 for the cover, 2 × M3×12 for the grip. They thread-form into the plastic. | 4 |
| [M2.5 screws and nuts (kit)](https://www.aliexpress.com/w/wholesale-m2.5-screw-nut-kit.html) | 3 + 3 | M2.5×10 screws and M2.5 nuts for the lidar. | 3 |
| [1/4"-20 hex nut](https://www.aliexpress.com/w/wholesale-1%2F4-20-hex-nut.html) | 1 | Tripod thread in the grip butt. | 1 |
| [Double-sided foam tape, 3 mm](https://www.aliexpress.com/w/wholesale-double-sided-foam-tape-3mm.html) | 1 | Pads that press the modules against the front. | 2 |
| [Silicone bumper feet, Ø10](https://www.aliexpress.com/w/wholesale-silicone-bumper-feet-10mm.html) | 4 | Stops the stand from sliding. | 1 |

## Tools

| Tool | Needed? | ≈ € |
|---|---|---:|
| [Automatic wire stripper](https://www.aliexpress.com/w/wholesale-automatic-wire-stripper.html) | Yes | 8 |
| Small Phillips/hex screwdriver | Yes | — |
| Multimeter | Recommended, to check the 5 V bus before plugging modules in | 10 |

## Power bank requirements

- USB-C output, 5 V, **at least 1.5 A**. Almost every modern power bank qualifies.
- Any size: it stays in your pocket, and the back of the device is taken by the screen.
- At about 0.9–1 A average draw, a 10 000 mAh bank lasts roughly 5–6 hours.
