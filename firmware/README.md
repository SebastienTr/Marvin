# Firmware (planned)

ESP32-S3 firmware for the XIAO ESP32S3 Sense. **Not written yet.** This file is the spec it will follow.

## Responsibilities

1. Read the **LD19** on UART0 (RX = GPIO44 / D7, 230 400 baud) and validate each 47-byte packet (header `0x54`, CRC8).
2. Read the **HLK-LD2450** on UART1 (RX = GPIO5 / D4, TX = GPIO6 / D5, 256 000 baud), switch it to multi-target mode at boot and parse its target frames.
3. Stamp every packet with a monotonic microsecond clock, synchronised to the host.
4. Send lidar and radar packets to the host as **UDP** datagrams, in a small versioned binary envelope.
5. Serve the camera as an **MJPEG** stream over HTTP.
6. Drive the **1.69" ST7789 screen** (SPI): a top-down lidar mini-map with radar targets, plus Wi-Fi, host link and battery status.
7. Support **OTA** updates after the first USB flash.

The MR60BHA2 kit runs Seeed's own firmware on its ESP32-C6 and talks to the host directly.

## Pin map

| Signal | XIAO pin | GPIO | Peripheral |
|---|---|---|---|
| LD19 Tx → | D7 | 44 | UART0 RX |
| LD2450 TX → | D4 | 5 | UART1 RX |
| → LD2450 RX | D5 | 6 | UART1 TX |
| LD19 PWM ← | D9 | 8 | LEDC (held low = default speed) |
| Screen CS | D0 | 1 | GPIO |
| Screen DC | D1 | 2 | GPIO |
| Screen RST | D3 | 4 | GPIO |
| Screen BL | D6 | 43 | LEDC (dimming) |
| Screen CLK | D8 | 7 | SPI2 SCK |
| Screen DIN | D10 | 9 | SPI2 MOSI |
| Camera | internal | — | DVP (Sense board) |

The screen shares the SPI pins (GPIO7/8/9) with the Sense board's microSD slot. Leave the slot empty, or give each device its own chip-select.

## Planned toolchain

PlatformIO with the Arduino-ESP32 core (`board = seeed_xiao_esp32s3`), USB CDC on boot enabled so UART0 stays free.
