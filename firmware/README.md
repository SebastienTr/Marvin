# Firmware (planned)

ESP32-S3 firmware for the XIAO ESP32S3 Sense. **Not written yet.** This file is the spec it will follow.

## Responsibilities

1. Read the **LD19** on UART0 (RX = GPIO44 / D7, 230 400 baud) and validate each 47-byte packet (header `0x54`, CRC8).
2. Read the **HLK-LD2450** on UART1 (RX = GPIO5 / D4, TX = GPIO6 / D5, 256 000 baud), switch it to multi-target mode at boot and parse its target frames.
3. Stamp every packet with a monotonic microsecond clock, synchronised to the host.
4. Send lidar and radar packets to the host as **UDP** datagrams, in a small versioned binary envelope.
5. Serve the camera as an **MJPEG** stream over HTTP.
6. Support **OTA** updates after the first USB flash.

The MR60BHA2 kit runs Seeed's own firmware on its ESP32-C6 and talks to the host directly.

## Pin map

| Signal | XIAO pin | GPIO | Peripheral |
|---|---|---|---|
| LD19 Tx → | D7 | 44 | UART0 RX |
| LD2450 TX → | D4 | 5 | UART1 RX |
| → LD2450 RX | D5 | 6 | UART1 TX |
| Camera | internal | — | DVP (Sense board) |

## Planned toolchain

PlatformIO with the Arduino-ESP32 core (`board = seeed_xiao_esp32s3`), USB CDC on boot enabled so UART0 stays free.
