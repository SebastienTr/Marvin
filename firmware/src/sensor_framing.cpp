// SPDX-License-Identifier: MIT
#include "sensor_framing.h"

namespace framing {
namespace {

constexpr uint8_t LIDAR_HEAD[2] = {0x54, 0x2C};
constexpr uint8_t TARGETS_HEAD[4] = {0xAA, 0xFF, 0x03, 0x00};
constexpr uint8_t TARGETS_TAIL[2] = {0x55, 0xCC};
constexpr uint8_t CMD_HEAD[4] = {0xFD, 0xFC, 0xFB, 0xFA};
constexpr uint8_t CMD_TAIL[4] = {0x04, 0x03, 0x02, 0x01};

// True if the first min(len, n) bytes of buf match head.
bool prefix(const uint8_t *buf, size_t len, const uint8_t *head, size_t n) {
  return memcmp(buf, head, len < n ? len : n) == 0;
}

uint16_t le16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }

}  // namespace

uint8_t ldrobot_crc8(const uint8_t *data, size_t len) {
  uint8_t c = 0;
  while (len--) {
    c ^= *data++;
    for (int i = 0; i < 8; i++) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x4D) : (uint8_t)(c << 1);
  }
  return c;
}

detail::Verdict LidarFramer::classify(const uint8_t *buf, size_t len) const {
  using detail::Verdict;
  if (!prefix(buf, len, LIDAR_HEAD, sizeof(LIDAR_HEAD))) return Verdict::Invalid;
  if (len < LIDAR_PACKET) return Verdict::Incomplete;
  return ldrobot_crc8(buf, LIDAR_PACKET - 1) == buf[LIDAR_PACKET - 1] ? Verdict::Valid : Verdict::Bad;
}

detail::Verdict Ld2450Framer::classify(const uint8_t *buf, size_t len) const {
  using detail::Verdict;
  if (prefix(buf, len, TARGETS_HEAD, sizeof(TARGETS_HEAD))) {
    if (len < LD2450_FRAME) return Verdict::Incomplete;
    return memcmp(buf + LD2450_FRAME - 2, TARGETS_TAIL, 2) == 0 ? Verdict::Valid : Verdict::Bad;
  }
  if (prefix(buf, len, CMD_HEAD, sizeof(CMD_HEAD))) {
    if (len < 6) return Verdict::Incomplete;
    size_t data = le16(buf + 4);
    if (data < 4 || data > MAX_ACK_DATA) return Verdict::Bad;  // an ACK holds a word and a status
    size_t total = 4 + 2 + data + 4;
    if (len < total) return Verdict::Incomplete;
    return memcmp(buf + total - 4, CMD_TAIL, 4) == 0 ? Verdict::Valid : Verdict::Bad;
  }
  return Verdict::Invalid;
}

namespace ld2450_cmd {

size_t build(uint16_t command, const uint8_t *value, size_t value_len, uint8_t *out) {
  if (value_len > 16) value_len = 16;
  uint8_t *p = out;
  memcpy(p, CMD_HEAD, 4);
  p += 4;
  size_t data = 2 + value_len;
  *p++ = (uint8_t)data;
  *p++ = (uint8_t)(data >> 8);
  *p++ = (uint8_t)command;
  *p++ = (uint8_t)(command >> 8);
  if (value_len) memcpy(p, value, value_len);
  p += value_len;
  memcpy(p, CMD_TAIL, 4);
  return (size_t)(p + 4 - out);
}

size_t enable_config(uint8_t *out) {
  static const uint8_t value[2] = {0x01, 0x00};
  return build(ENABLE_CONFIG, value, sizeof(value), out);
}

size_t end_config(uint8_t *out) { return build(END_CONFIG, nullptr, 0, out); }

size_t multi_target(uint8_t *out) { return build(MULTI_TARGET, nullptr, 0, out); }

bool parse_ack(const uint8_t *frame, size_t len, uint16_t *command, uint16_t *status) {
  if (len < 14 || memcmp(frame, CMD_HEAD, 4) != 0) return false;
  size_t data = le16(frame + 4);
  if (data < 4 || len != 4 + 2 + data + 4 || memcmp(frame + len - 4, CMD_TAIL, 4) != 0) return false;
  uint16_t word = le16(frame + 6);
  if (!(word & 0x0100)) return false;
  if (command) *command = (uint16_t)(word & ~0x0100);
  if (status) *status = le16(frame + 8);
  return true;
}

}  // namespace ld2450_cmd

}  // namespace framing
