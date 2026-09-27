// The robot's real sensors on the ESP32-S3 UARTs (built only for ESP32 targets):
// - lidar (LDROBOT D500 / D800) on UART0, RX only on GPIO44 (XIAO D7);
// - HLK-LD2450 on UART1, RX GPIO5 (D4), TX GPIO6 (D5), 256 000 baud, set to multi-target
//   tracking at boot.
// Frames are cut and validated by sensor_framing.h, and handed over raw, as the protocol sends them.
// The MR60BHA2 is not read here: its kit has its own ESP32-C6 and bridge.
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace real {

struct Counters {
  uint32_t lidar_packets;        // valid 47-byte packets
  uint32_t lidar_crc_errors;
  uint32_t lidar_dropped_bytes;  // garbage and bytes of rejected packets
  uint32_t ld2450_frames;        // valid 30-byte target frames
  uint32_t ld2450_bad_frames;
  uint32_t ld2450_dropped_bytes;
  uint32_t uart_overflows;       // UART RX FIFO or buffer overflows, both ports: bytes were lost
};

// 1 = D500 (230 400 baud), 2 = D800 (921 600 baud).
uint32_t lidar_baud(uint8_t lidar_model);

// Opens both UARTs and configures the LD2450 (enable configuration, multi-target tracking, end
// configuration, each ACK checked, with retries). Blocks for up to ~3 s if the LD2450 does not
// answer. Returns true if the LD2450 acknowledged the whole sequence; the stream is read either way.
bool begin(uint8_t lidar_model);

// Reads what the UARTs received; calls on_lidar for every valid 47-byte lidar packet and
// on_ld2450 for every valid 30-byte target frame. Call it often (every loop) so the RX buffers
// never fill up: at 921 600 baud the lidar sends ~92 KB/s.
using FrameFn = void (*)(const uint8_t *frame);
void poll(FrameFn on_lidar, FrameFn on_ld2450);

Counters counters();

}  // namespace real
