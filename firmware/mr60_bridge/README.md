# MR60BHA2 bridge

Firmware for the **Seeed MR60BHA2 kit** (60 GHz breathing and heartbeat radar on a XIAO ESP32C6). It replaces the kit's stock ESPHome firmware: it reads the radar and sends breathing rate, heart rate, both waves and the distance to the Marvin host as [`VITALS`](../../docs/protocol.md) messages.

## Why a separate firmware

The kit already has its own Wi-Fi microcontroller, the ESP32-C6 the radar is wired to. Rather than running a wire to the robot, the kit talks to the host directly, exactly like the robot does: same UDP protocol v1, same discovery (`HELLO` / `HOST_ACK`), its own device id. The host receives each device separately and the brain merges them, so the vitals arrive in the same stream as the robot's LD2450, whether the kit sits on the robot or on the desk.

## Hardware

Everything is on the kit's board; nothing to wire.

| Part | XIAO pin | GPIO | Notes |
|---|---|---|---|
| MR60BHA2 TX → C6 | D7 | 17 | UART1 RX, 115 200 baud 8N1 |
| C6 → MR60BHA2 RX | D6 | 16 | UART1 TX (not used yet) |
| WS2812 RGB LED | D1 | 1 | status light |
| BH1750 light sensor | D4 / D5 | 22 / 23 | I2C, not used yet |

Pins are those of Seeed's stock ESPHome configuration.

## Build and flash

1. Install PlatformIO: `pip install platformio` (or the VS Code extension).
2. Copy `include/secrets.example.h` to `include/secrets.h` and put your Wi-Fi in it (2.4 GHz network; same file format as the main firmware), and choose an `OTA_PASSWORD`. `secrets.h` is ignored by git.
3. Plug the kit in with a USB-C cable (the port is on the XIAO), then:

```bash
cd firmware/mr60_bridge
pio run -t upload        # the first run downloads the ESP32-C6 toolchain, a few minutes
pio device monitor       # serial log at 115200 (Ctrl+C to quit)
```

If the upload cannot find or open the port (the stock firmware can keep the USB busy, or a crashed sketch can hide it): unplug the kit, **hold the XIAO's BOOT button** (marked B) while plugging it back in, release it, run the upload again, then press **RESET** (R) once it is done.

The official `platformio/espressif32` platform only supports ESP-IDF on the ESP32-C6. Arduino on the C6 needs Arduino-ESP32 3.x, packaged by the community [pioarduino](https://github.com/pioarduino/platform-espressif32) platform, pinned in `platformio.ini` to a release. pioarduino also calls itself `espressif32`, so this project keeps it in its own PlatformIO directory (`core_dir = ~/.platformio-pioarduino`): it cannot replace the platform used by the main firmware.

### Over Wi-Fi (OTA)

Once a first USB upload has put this firmware on the kit, later updates go over Wi-Fi, so the kit can stay where it is mounted. It answers as `marvin-xxxxxx.local` (the last three bytes of its MAC; the serial log prints `OTA: ready as marvin-xxxxxx.local`, and the host lists the same name):

```bash
pio run -e xiao_esp32c6_ota -t upload --upload-port marvin-xxxxxx.local     # or its IP
```

The upload gives the `OTA_PASSWORD` of `include/secrets.h` (read by the main firmware's `scripts/ota_auth.py`; `MARVIN_OTA_PASSWORD` in the environment wins). Without a password anyone on your network can flash the kit, and the log says so. The LED is purple while an update is written; the kit reboots into the new firmware when it is done. The OTA code is the robot's own (`../src/ota.cpp`, built here through `src/ota_shared.cpp`).

### Tests

Unit tests of the radar parser, the vitals logic and the `VITALS` encoder run on the computer:

```bash
pio test -e native
```

### Back to the stock ESPHome firmware

Seeed's firmware can be put back at any time, in a Chrome or Edge browser:

- the [MR60BHA2 web flasher](https://limengdu.github.io/MR60BHA2_ESPHome_external_components/): plug the kit in, click CONNECT; or
- the `*.factory.bin` from the [releases of the ESPHome component](https://github.com/limengdu/MR60BHA2_ESPHome_external_components/releases), flashed with [ESPHome Web](https://web.esphome.io/).

Use the BOOT button as above if the browser cannot connect. The radar module keeps its own firmware either way; only the C6 is reflashed.

## Expected log

```
Marvin MR60BHA2 bridge mr60-bridge-0.2.0, board 4
radar on UART1, RX GPIO17, TX GPIO16, 115200 baud
Wi-Fi: joining my-network
Wi-Fi: joined my-network, IP 192.168.1.42, RSSI -61 dBm
OTA: ready as marvin-a1b2c3.local (192.168.1.42), port 3232, password protected
linked to host 192.168.1.10
radar: nobody, breath 0.0/min, heart 0.0/min, distance 0 mm (frames 812, errors 0)
radar: person, breath 14.2/min, heart 71.0/min, distance 820 mm, valid, steady (frames 1630, errors 0)
```

A status line is printed every 5 s. `radar: no data` means no valid frame came from the radar for 3 s: check that this is an MR60BHA2 kit (the MR60FDA2 fall kit looks the same). A growing `errors` count means corrupted UART frames. If the radar reports its firmware version, it is printed once and sent to the host in the `LOG` message on link (`firmware mr60-bridge-0.2.0 up, MR60BHA2 radar 1.2.3 (project 2)`, or `not reported`).

## Reconnecting on its own

- **Wi-Fi lost** (router restart, out of range): the kit rejoins without blocking, first at once, then after 5, 10, 20, 40 and every 60 s, logging `Wi-Fi: lost, rejoining`. The radar keeps being read meanwhile; once back, it looks for the host again.
- **Host lost** (no `HOST_ACK` for 6 s): back to broadcasting `HELLO` until a host answers.
- **Radar silent** for 10 s (loose contact, brown-out): its UART is reopened, then again after 20, 40 and every 60 s while it stays silent; each try is logged (`radar: silent for 10 s, reopening its UART`) and sent to the host as a `LOG` when linked.

## Status light

The kit's RGB LED, kept dim:

| Light | Meaning |
|---|---|
| Blue blink | joining Wi-Fi (fast) or looking for the host (one short flash per second) |
| Purple | a firmware update is being written (OTA) |
| Slow green pulse | linked to the host, no valid vital signs |
| Soft red heartbeat | a person is measured; it beats at the measured heart rate |

## What is sent

`HELLO` every 0.5 s by broadcast until the host answers, then every 2 s to the host; board id **4**, flags 0 (real sensor data), firmware `mr60-bridge-0.2.0`. After 6 s without `HOST_ACK`, back to broadcasting. Once linked, `VITALS` every 100 ms (10 Hz) with the latest readings:

| `VITALS` field | From the radar (frame type) |
|---|---|
| flags, bit 0: valid | 1 only if someone is present (`0x0F09`, or a valid range `0x0A16` on radar firmware without `0x0F09`), the radar spoke in the last 3 s, and both a breath rate and a heart rate above zero arrived in the last 5 s |
| flags, bit 1: quality reported | always 1 from this firmware: bit 2 means something (senders without it, the simulators, say nothing about quality) |
| flags, bit 2: steady | the bridge's signal-quality flag, only with valid: both rates refreshed by the radar in the last 3 s, a range known and within 1.5 m (the radar's vital-signs zone), breathing 6–30/min and heart 40–140/min, and no jump of more than 15 bpm between two heart rate reports in the last 5 s |
| breath rate | `0x0A14`, float, per minute |
| heart rate | `0x0A15`, float, per minute |
| breathing wave | breath phase of `0x0A13`, rescaled (below) |
| heartbeat wave | heart phase of `0x0A13`, rescaled (below) |
| distance | `0x0A16` range when its flag is set, **cm × 10** → mm; 0 when unknown (unit below) |

Protocol v1 decoders read only bit 0 of the flags byte (`valid & 1` in the host's `Vitals.decode`), so bits 1 and 2 change nothing for a host that does not know them; the Java host's vitals pipeline uses `steady` in its quality gate when the sender reports it.

Rates and waves are 0 when nobody is present. A "nobody" report clears the rates, so after someone comes back `valid` waits for fresh ones.

**Units, checked against the sources.** The breath and heart rates are floats in breaths and beats per minute (ESPHome's `seeed_mr60bha2` component, maintained with Seeed, publishes them as is in bpm). The range of `0x0A16` is a float in **centimetres**: the same component publishes it as is with `unit_of_measurement: cm`, after checking the flag (first word) is non-zero; Seeed's Arduino library (`SEEED_MR60BHA2::getDistance`) passes the same float on raw. Seeed's wiki states no unit for either.

**Wave scaling.** The radar's phases (`0x0A13`: total, breath and heart phase, three floats) are raw and uncalibrated: neither Seeed's library (`getHeartBreathPhases`, which copies the floats as is) nor its wiki gives a unit or a range, and ESPHome does not read them at all. Their amplitude depends on distance and posture. Each one is rescaled to −1…1 by automatic gain: a slow moving average (10 s time constant) removes the offset, then the signal is divided by its recent peak, which decays with an 8 s time constant. The host therefore gets the shape and timing of each breath and beat, not an absolute amplitude. Waves are 0 when no phase frame came in the last second.

## Radar protocol

The radar sends Seeed's "tiny frame" protocol at 115 200 baud: `0x01`, frame id (u16), payload length (u16), type (u16), header checksum, payload, payload checksum; the header is big-endian, the payload little-endian (floats), checksums are the inverted XOR of their bytes. `lib/mr60` holds a small parser for it rather than Seeed's Arduino library: that library blocks the loop for the whole read timeout, allocates a vector per frame, reads payloads without checking their length, and is not in the PlatformIO registry. The parser is portable, uses a fixed buffer, resynchronises on the next frame after any error and is unit-tested.

| File | Role |
|---|---|
| `src/main.cpp` | Wi-Fi, host discovery, UART reading, `VITALS` at 10 Hz, status light and log |
| `lib/mr60/mr60.h`, `mr60.cpp` | Tiny-frame parser, vitals tracker, wave scaling, `VITALS` encoder (no Arduino) |
| `test/test_mr60/` | Unit tests (`pio test -e native`) |
| `../src/protocol.h` | Shared with the main firmware, included through `-I../src` |

## Known limits

- **Heart rate at a desk is not reliable.** Seeed recommends the breathing and heart rate functions for sleep only, with the kit about 1 m above the head, tilted 45° down, and asks not to use them while the person is seated at a desk or moving ([Seeed wiki](https://wiki.seeedstudio.com/getting_started_with_mr60bha2_mmwave_kit/)). Breathing and heart rate need a still person within about 1.5 m; presence works up to about 6 m. The host must treat these values as hints, never as measurements, and `valid` only means that the radar produced them.
- The distance unit (cm) is ESPHome's and matches how Seeed's library treats the value, but has not been measured on a real kit yet: put a person at a tape-measured 80 cm and check the log says about 800 mm (`Tracker::CM_TO_MM` changes it).
- How often the radar refreshes its rates is not documented; `steady` assumes at least every 3 s (`Tracker::STEADY_FRESH_MS`). If a real kit never shows `steady` while `valid`, raise it.
- Presence frames (`0x0F09`) and the firmware version frame only exist on newer radar firmware; without them, presence falls back to the range flag and the version shows as `not reported`.
- The light sensor, the target list (`0x0A04`) and the radar's configuration commands are not used yet.
