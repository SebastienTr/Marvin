# Firmware

Firmware for the Marvin robot's XIAO ESP32S3 Sense, built with PlatformIO.

**Status: prototype.** The Wi-Fi link and the [UDP protocol](../docs/protocol.md) work, with **simulated sensors**: a lidar (D500 or D800) and an HLK-LD2450 seeing a room with a person walking around, byte-exact to the real frames. It runs on a Wemos D1 mini (ESP8266) as well as on the ESP32-S3 boards, so the whole chain can be tested before the robot is built. The real UART readers, the screen and the audio come next.

## Build and flash

1. Install PlatformIO: `pip install platformio` (or the VS Code extension).
2. Copy `include/secrets.example.h` to `include/secrets.h` and put your Wi-Fi name and password in it (2.4 GHz network). `secrets.h` is ignored by git.
3. Plug the board in over USB, then:

```bash
cd firmware
pio run -e d1_mini -t upload       # Wemos D1 mini
pio device monitor                 # serial log, 115200 baud (Ctrl+C to quit)
```

Other boards: `-e esp32s3` (ESP32-S3 DevKitC-1) and `-e xiao_esp32s3` (the robot). Simulate a D800 instead of a D500 with `-DMARVIN_SIM_LIDAR_MODEL=2` in `platformio.ini`.

Then run `marvin-host run` on a computer on the same network (see [host/README.md](../host/README.md)). The on-board LED blinks fast while the robot looks for the host, and flashes briefly every 2 s once linked.

## Source layout

| File | Role |
|---|---|
| `src/main.cpp` | Wi-Fi, host discovery (`HELLO` / `HOST_ACK`), streaming loop, status LED |
| `src/protocol.h` | UDP envelope, mirrors `host/marvin_host/protocol.py` |
| `src/sensors_sim.cpp` | Simulated lidar packets and LD2450 frames, same room as the host simulator |

## Responsibilities (full firmware)

1. Read the **lidar** on UART0 (RX = GPIO44 / D7): 921 600 baud for the D800 (STL-27L), 230 400 baud for the D500 (STL-19P). Validate each 47-byte LDROBOT packet (header `0x54`, CRC8).
2. Read the **HLK-LD2450** on UART1 (RX = GPIO5 / D4, TX = GPIO6 / D5, 256 000 baud), switch it to multi-target mode at boot and parse its target frames.
3. Stamp every packet with a monotonic microsecond clock, synchronised to the host.
4. Send lidar and radar packets to the host as **UDP** datagrams, in a small versioned binary envelope.
5. Serve the camera as an **MJPEG** stream over HTTP.
6. Drive the **1.69" ST7789 screen** (SPI), the robot's face:
   - **eyes** by default: blinking, looking towards the nearest person tracked by the LD2450, sleepy when nobody is around, and any expression the host sends;
   - a top-down lidar **mini-map** with radar targets, and a status page (Wi-Fi, host link), on request.
7. **Audio in**: read the built-in PDM microphone (I2S0, 16 kHz) and stream it on request, or detect simple sound events locally.
8. **Audio out**: play chirps, beeps and speech streamed from the host through the MAX98357A (I2S1), with a volume cap.
9. Keep the robot **alive without a computer**: eyes, presence reactions and sounds work on the device alone; the host adds understanding and language.
10. Support **OTA** updates after the first USB flash.

The MR60BHA2 kit runs Seeed's own firmware on its ESP32-C6 and talks to the host directly.

## Pin map

Unchanged since rev D.

| Signal | XIAO pin | GPIO | Peripheral |
|---|---|---|---|
| Lidar Tx → | D7 | 44 | UART0 RX |
| LD2450 TX → | D4 | 5 | UART1 RX |
| → LD2450 RX | D5 | 6 | UART1 TX |
| Screen CS | D0 | 1 | GPIO |
| Screen DC | D1 | 2 | GPIO |
| Screen RST | D3 | 4 | GPIO |
| Screen CLK | D8 | 7 | SPI2 SCK |
| Screen DIN | D10 | 9 | SPI2 MOSI |
| Amp BCLK | D9 | 8 | I2S1 |
| Amp LRC / WS | D6 | 43 | I2S1 |
| Amp DIN | D2 | 3 | I2S1 |
| Mic CLK / DATA | internal | 42 / 41 | I2S0 PDM (Sense board) |
| Camera | internal | — | DVP (Sense board) |

The screen shares the SPI pins (GPIO7/8/9) with the Sense board's microSD slot. Leave the slot empty, or give each device its own chip-select.

## Planned toolchain

PlatformIO with the Arduino-ESP32 core (`board = seeed_xiao_esp32s3`), USB CDC on boot enabled so UART0 stays free.
