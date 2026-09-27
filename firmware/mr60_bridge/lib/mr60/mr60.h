// MR60BHA2 60 GHz radar: "tiny frame" UART parser and vital-signs tracker.
// Portable C++ (no Arduino), unit-tested on the host with `pio test -e native`.
//
// Frame layout (header big-endian, payload little-endian), as sent by the radar at 115200 8N1:
//
//   offset  size  field
//   0       1     SOF, 0x01
//   1       2     frame id (counter)
//   3       2     payload length N
//   5       2     type (see Type)
//   7       1     header checksum: ~(xor of bytes 0..6)
//   8       N     payload
//   8+N     1     payload checksum: ~(xor of the payload)   (only when N > 0)
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace mr60 {

constexpr uint8_t SOF = 0x01;
constexpr size_t HEADER_SIZE = 8;
constexpr size_t MAX_PAYLOAD = 512;   // longer frames (none expected from the MR60BHA2) are dropped

enum Type : uint16_t {
  TARGET_INFO = 0x0A04,      // u32 count + count x (x f32, y f32, doppler i32, cluster i32)
  POINT_CLOUD = 0x0A08,      // same layout as TARGET_INFO
  PHASES = 0x0A13,           // total, breath, heart phase: 3 x f32
  BREATH_RATE = 0x0A14,      // f32, breaths per minute
  HEART_RATE = 0x0A15,       // f32, beats per minute
  DISTANCE = 0x0A16,         // u32 flag (0 = no range) + f32 range, cm
  PRESENCE = 0x0F09,         // u8/u16, 0 = nobody, 1 = someone (newer radar firmware)
  FIRMWARE = 0xFFFF,         // project, major, minor, patch: 4 x u8
};

uint8_t checksum(const uint8_t *data, size_t len);

struct Frame {
  uint16_t id;
  uint16_t type;
  const uint8_t *payload;    // valid until the next feed()
  uint16_t len;
};

// Byte-at-a-time frame parser with a fixed buffer. Resynchronises on the next SOF after
// a bad header, a bad checksum or an oversized frame.
class Parser {
 public:
  // Feeds one byte; returns true when it completed a valid frame, then available in frame().
  bool feed(uint8_t byte);
  const Frame &frame() const { return frame_; }
  uint32_t frames() const { return frames_; }
  uint32_t errors() const { return errors_; }

 private:
  bool reject(size_t from);
  bool step(uint8_t byte);

  uint8_t buf_[HEADER_SIZE + MAX_PAYLOAD + 1];
  size_t n_ = 0;
  Frame frame_{};
  uint32_t frames_ = 0, errors_ = 0;
};

// Same fields as proto VITALS / host Vitals: rates in 1/min, waves in -1..1, distance in mm.
struct Vitals {
  bool valid;
  float breath_rate;
  float heart_rate;
  float breath_wave;
  float heart_wave;
  uint16_t distance_mm;
};

constexpr size_t VITALS_SIZE = 11;
// Writes the 11-byte VITALS payload of Marvin protocol v1, returns its size.
size_t encode_vitals(const Vitals &v, uint8_t *out);

// Rescales a phase signal to -1..1: removes its slow offset, then divides by a decaying peak
// (automatic gain), so the host sees the shape of the wave whatever the radar's raw amplitude.
class WaveScaler {
 public:
  WaveScaler(float offset_tau_s, float peak_tau_s, float min_peak)
      : offset_tau_(offset_tau_s), peak_tau_(peak_tau_s), min_peak_(min_peak) {}
  float update(float x, uint32_t now_ms);
  void reset() { started_ = false; }

 private:
  float offset_tau_, peak_tau_, min_peak_;
  bool started_ = false;
  float offset_ = 0, peak_ = 0;
  uint32_t last_ms_ = 0;
};

struct FirmwareVersion {
  uint8_t project, major, minor, patch;
};

// Keeps the latest radar readings and decides what VITALS says.
class Tracker {
 public:
  static constexpr uint32_t RATE_STALE_MS = 5000;     // breath / heart rate older than this: not valid
  static constexpr uint32_t WAVE_STALE_MS = 1000;
  static constexpr uint32_t DISTANCE_STALE_MS = 3000;
  static constexpr uint32_t SILENT_MS = 3000;          // no frame at all for this long: radar lost
  static constexpr float CM_TO_MM = 10.0f;

  Tracker();
  // Applies one frame's payload. Returns false for unknown types or short payloads.
  bool apply(uint16_t type, const uint8_t *payload, size_t len, uint32_t now_ms);
  Vitals vitals(uint32_t now_ms) const;

  bool radar_alive(uint32_t now_ms) const { return any_ && now_ms - last_frame_ms_ < SILENT_MS; }
  bool present(uint32_t now_ms) const;
  bool has_firmware() const { return has_fw_; }
  bool firmware(FirmwareVersion &fw) const { fw = fw_; return has_fw_; }

 private:
  static bool fresh(bool has, uint32_t t, uint32_t now, uint32_t max_age) { return has && now - t < max_age; }

  bool any_ = false;
  uint32_t last_frame_ms_ = 0;
  bool has_presence_ = false, presence_ = false;
  bool has_breath_ = false, has_heart_ = false, has_phase_ = false, has_distance_ = false;
  float breath_ = 0, heart_ = 0, breath_wave_ = 0, heart_wave_ = 0, distance_cm_ = 0;
  uint32_t breath_ms_ = 0, heart_ms_ = 0, phase_ms_ = 0, distance_ms_ = 0;
  WaveScaler breath_scale_, heart_scale_;
  bool has_fw_ = false;
  FirmwareVersion fw_{};
};

}  // namespace mr60
