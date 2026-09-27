// Stream framers for the sensor UARTs: they cut a raw byte stream into complete, valid frames.
// Portable C++ (no Arduino), unit-tested on the host with `pio test -e native`.
//
// - LidarFramer: LDROBOT 47-byte packets (D500 / D800), header 54 2C, CRC-8 polynomial 0x4D.
// - Ld2450Framer: HLK-LD2450 30-byte target frames (AA FF 03 00 ... 55 CC) and the
//   command ACK frames (FD FC FB FA, length, data, 04 03 02 01).
// - ld2450_cmd: the LD2450 configuration commands sent at boot, and an ACK decoder.
//
// Both framers resynchronise after garbage, partial or corrupted frames without losing the
// next valid one: when a candidate frame turns out to be wrong, only its first byte is dropped
// and the bytes after it are scanned again for a header.
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace framing {

constexpr size_t LIDAR_PACKET = 47;
constexpr size_t LD2450_FRAME = 30;

// CRC-8 of the LDROBOT packets (polynomial 0x4D, initial value 0, no reflection).
uint8_t ldrobot_crc8(const uint8_t *data, size_t len);

namespace detail {

enum class Verdict : uint8_t {
  Invalid,     // these bytes cannot start a frame
  Incomplete,  // a valid frame prefix so far
  Valid,       // a complete, valid frame
  Bad,         // a complete (or impossible) frame whose check failed
};

// Shared resynchronising byte framer. `Derived` provides:
//   Verdict classify(const uint8_t *buf, size_t len) const;   // called after each byte
//   template <class F> void accept(const uint8_t *frame, size_t len, F &on_frame);
//   void reject(const uint8_t *buf, size_t len);              // a Bad candidate
// CAP is the largest frame size.
template <class Derived, size_t CAP>
class StreamFramer {
 public:
  // Feeds a chunk of bytes; on_frame is called for every complete valid frame, in order.
  template <class F>
  void feed(const uint8_t *data, size_t len, F &&on_frame) {
    for (size_t i = 0; i < len; i++) push(data[i], on_frame);
  }

  template <class F>
  void push(uint8_t byte, F &&on_frame) {
    // Bytes waiting to be (re)examined. After a failed candidate this holds its bytes minus the
    // first one, followed by the rest; it never holds more than CAP bytes.
    uint8_t queue[CAP];
    size_t qlen = 0, qpos = 0;
    queue[qlen++] = byte;
    Derived &self = *static_cast<Derived *>(this);
    while (qpos < qlen) {
      buf_[len_++] = queue[qpos++];
      Verdict v = self.classify(buf_, len_);
      if (v == Verdict::Incomplete) continue;
      if (v == Verdict::Valid) {
        self.accept(buf_, len_, on_frame);
        len_ = 0;
        continue;
      }
      if (v == Verdict::Bad) self.reject(buf_, len_);
      // Resynchronise: drop the first byte, examine everything after it again.
      dropped_++;
      uint8_t rest[CAP];
      size_t n = len_ - 1;
      memcpy(rest, buf_ + 1, n);
      memcpy(rest + n, queue + qpos, qlen - qpos);
      n += qlen - qpos;
      memcpy(queue, rest, n);
      qlen = n;
      qpos = 0;
      len_ = 0;
    }
  }

  // Bytes discarded while looking for frames (garbage, and bytes of rejected frames).
  uint32_t dropped_bytes() const { return dropped_; }
  // Bytes of an incomplete frame currently held.
  size_t pending() const { return len_; }
  void reset() { len_ = 0; }

 protected:
  uint8_t buf_[CAP];
  size_t len_ = 0;
  uint32_t dropped_ = 0;
};

}  // namespace detail

// LDROBOT lidar packets. on_frame(const uint8_t *packet) receives LIDAR_PACKET bytes.
class LidarFramer : public detail::StreamFramer<LidarFramer, LIDAR_PACKET> {
 public:
  uint32_t packets() const { return packets_; }
  uint32_t crc_errors() const { return crc_errors_; }

  detail::Verdict classify(const uint8_t *buf, size_t len) const;
  template <class F>
  void accept(const uint8_t *frame, size_t, F &on_frame) {
    packets_++;
    on_frame(frame);
  }
  void reject(const uint8_t *, size_t) { crc_errors_++; }

 private:
  uint32_t packets_ = 0;
  uint32_t crc_errors_ = 0;
};

// HLK-LD2450 frames. on_frame(Ld2450Framer::Kind kind, const uint8_t *frame, size_t len):
// TARGETS frames are LD2450_FRAME bytes; ACK frames are the whole command ACK, header to footer.
class Ld2450Framer : public detail::StreamFramer<Ld2450Framer, 4 + 2 + 64 + 4> {
 public:
  enum Kind : uint8_t { TARGETS, ACK };
  static constexpr size_t MAX_ACK_DATA = 64;  // in-frame data bytes; the protocol's largest is 30

  uint32_t frames() const { return frames_; }
  uint32_t acks() const { return acks_; }
  // Frames with the right header but a wrong footer or length.
  uint32_t bad_frames() const { return bad_; }

  detail::Verdict classify(const uint8_t *buf, size_t len) const;
  template <class F>
  void accept(const uint8_t *frame, size_t len, F &on_frame) {
    Kind kind = frame[0] == 0xAA ? TARGETS : ACK;
    (kind == TARGETS ? frames_ : acks_)++;
    on_frame(kind, frame, len);
  }
  void reject(const uint8_t *, size_t) { bad_++; }

 private:
  uint32_t frames_ = 0;
  uint32_t acks_ = 0;
  uint32_t bad_ = 0;
};

// HLK-LD2450 configuration commands, from "HLK-LD2450 Serial Communication Protocol V1.03"
// (Shenzhen Hi-Link), section 2.1.2 (frame formats) and 2.2 (commands):
//   command frame: FD FC FB FA, in-frame length (u16 LE), command word (u16 LE), value, 04 03 02 01
//   ACK frame:     FD FC FB FA, in-frame length (u16 LE), command word | 0x0100, status u16 (0 = ok), ...
//   enable configuration  (2.2.1): FD FC FB FA 04 00 FF 00 01 00 04 03 02 01
//   end configuration     (2.2.2): FD FC FB FA 02 00 FE 00 04 03 02 01
//   multi-target tracking (2.2.4): FD FC FB FA 02 00 90 00 04 03 02 01
// The same bytes are used by ESPHome's ld2450 component. Any command other than "enable
// configuration" is ignored by the radar unless it is sent between enable and end configuration.
namespace ld2450_cmd {

constexpr uint16_t ENABLE_CONFIG = 0x00FF;  // value 0x0001
constexpr uint16_t END_CONFIG = 0x00FE;
constexpr uint16_t SINGLE_TARGET = 0x0080;
constexpr uint16_t MULTI_TARGET = 0x0090;
constexpr uint16_t QUERY_MODE = 0x0091;
constexpr size_t MAX_SIZE = 4 + 2 + 2 + 16 + 4;  // largest frame the builders write

// Writes a command frame with an optional value (at most 16 bytes), returns its size.
size_t build(uint16_t command, const uint8_t *value, size_t value_len, uint8_t *out);
size_t enable_config(uint8_t *out);
size_t end_config(uint8_t *out);
size_t multi_target(uint8_t *out);

// Decodes an ACK frame (as delivered by Ld2450Framer): the command it answers and its status
// (0 = success). Returns false if the frame is not a well-formed ACK.
bool parse_ack(const uint8_t *frame, size_t len, uint16_t *command, uint16_t *status);

}  // namespace ld2450_cmd

}  // namespace framing
