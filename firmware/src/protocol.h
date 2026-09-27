// Marvin UDP protocol, version 1. Mirrors host/marvin_host/protocol.py; see docs/protocol.md.
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace proto {

constexpr uint8_t VERSION = 1;
constexpr uint16_t HOST_PORT = 47100;
constexpr uint16_t DEVICE_PORT = 47101;
constexpr size_t HEADER_SIZE = 16;

enum Type : uint8_t {
  HELLO = 0x01,
  LIDAR = 0x02,
  LD2450 = 0x03,
  LOG = 0x04,
  VITALS = 0x05,
  HOST_ACK = 0x81,
};

constexpr uint8_t FLAG_SIMULATED = 0x01;

// Little-endian writers (both ESP8266 and ESP32 are little-endian, but keep it explicit).
inline uint8_t *put16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; return p + 2; }
inline uint8_t *put32(uint8_t *p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = v >> (8 * i); return p + 4; }
inline uint8_t *put64(uint8_t *p, uint64_t v) { for (int i = 0; i < 8; i++) p[i] = v >> (8 * i); return p + 8; }
inline uint64_t get64(const uint8_t *p) { uint64_t v = 0; for (int i = 7; i >= 0; i--) v = (v << 8) | p[i]; return v; }

// Writes the 16-byte header, returns a pointer to the payload.
inline uint8_t *header(uint8_t *buf, Type type, uint32_t seq, uint64_t t_us) {
  buf[0] = 'M';
  buf[1] = 'V';
  buf[2] = VERSION;
  buf[3] = type;
  put32(buf + 4, seq);
  put64(buf + 8, t_us);
  return buf + HEADER_SIZE;
}

inline bool valid(const uint8_t *buf, size_t len) {
  return len >= HEADER_SIZE && buf[0] == 'M' && buf[1] == 'V' && buf[2] == VERSION;
}

// HELLO payload: mac[6], board u8, flags u8, rssi i8, uptime_ms u32, firmware (u8 length + text).
inline size_t hello(uint8_t *p, const uint8_t mac[6], uint8_t board, uint8_t flags, int8_t rssi,
                    uint32_t uptime_ms, const char *fw) {
  uint8_t *s = p;
  memcpy(p, mac, 6);
  p += 6;
  *p++ = board;
  *p++ = flags;
  *p++ = (uint8_t)rssi;
  p = put32(p, uptime_ms);
  size_t n = strlen(fw);
  if (n > 255) n = 255;
  *p++ = n;
  memcpy(p, fw, n);
  return p + n - s;
}

}  // namespace proto
