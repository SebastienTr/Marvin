// MR60BHA2 tiny-frame parser and vital-signs tracker. See mr60.h.
// SPDX-License-Identifier: MIT
#include "mr60.h"

#include <math.h>
#include <string.h>

#include "protocol.h"

namespace mr60 {

namespace {

uint16_t be16(const uint8_t *p) { return (uint16_t)(p[0] << 8 | p[1]); }
uint32_t le32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
float lef32(const uint8_t *p) {
  uint32_t u = le32(p);
  float f;
  memcpy(&f, &u, sizeof f);   // unaligned-safe, and byte order explicit
  return f;
}
float clamp1(float x) { return x > 1 ? 1 : x < -1 ? -1 : x; }
bool finite(float x) { return x == x && x < 1e30f && x > -1e30f; }

}  // namespace

uint8_t checksum(const uint8_t *data, size_t len) {
  uint8_t c = 0;
  for (size_t i = 0; i < len; i++) c ^= data[i];
  return (uint8_t)~c;
}

bool Parser::feed(uint8_t byte) { return step(byte); }

// Drops the current candidate frame and re-scans the bytes after its SOF for another one.
// Only called on header errors, so at most 7 bytes are re-fed and a frame cannot complete here.
bool Parser::reject(size_t from) {
  errors_++;
  size_t end = n_;
  n_ = 0;
  for (size_t i = from; i < end; i++) step(buf_[i]);   // step() writes below i: safe in place
  return false;
}

bool Parser::step(uint8_t byte) {
  if (n_ == 0 && byte != SOF) return false;            // between frames: skip noise
  buf_[n_++] = byte;
  if (n_ < HEADER_SIZE) return false;
  uint16_t len = be16(buf_ + 3);
  if (n_ == HEADER_SIZE) {
    if (byte != checksum(buf_, HEADER_SIZE - 1) || len > MAX_PAYLOAD) return reject(1);
    if (len > 0) return false;
  } else if (n_ < HEADER_SIZE + len + 1) {
    return false;
  } else if (byte != checksum(buf_ + HEADER_SIZE, len)) {
    errors_++;                                         // bad payload: drop it, wait for the next SOF
    n_ = 0;
    return false;
  }
  frame_ = Frame{be16(buf_ + 1), be16(buf_ + 5), buf_ + HEADER_SIZE, len};
  frames_++;
  n_ = 0;
  return true;
}

size_t encode_vitals(const Vitals &v, uint8_t *out) {
  uint8_t *p = out;
  *p++ = v.valid ? 1 : 0;
  auto rate = [](float r) -> uint16_t {
    if (!finite(r) || r <= 0) return 0;
    float c = roundf(r * 100);
    return c > 65535 ? 65535 : (uint16_t)c;
  };
  auto wave = [](float w) -> uint16_t {
    if (!finite(w)) return 0;
    return (uint16_t)(int16_t)lroundf(clamp1(w) * 32767);
  };
  p = proto::put16(p, rate(v.breath_rate));
  p = proto::put16(p, rate(v.heart_rate));
  p = proto::put16(p, wave(v.breath_wave));
  p = proto::put16(p, wave(v.heart_wave));
  p = proto::put16(p, v.distance_mm);
  return p - out;
}

float WaveScaler::update(float x, uint32_t now_ms) {
  if (!finite(x)) return 0;
  if (!started_) {
    started_ = true;
    offset_ = x;
    peak_ = min_peak_;
    last_ms_ = now_ms;
    return 0;
  }
  float dt = (now_ms - last_ms_) / 1000.0f;
  last_ms_ = now_ms;
  if (dt > 5) {                                        // long gap: start over
    reset();
    return update(x, now_ms);
  }
  offset_ += (x - offset_) * (1 - expf(-dt / offset_tau_));
  float y = x - offset_;
  peak_ *= expf(-dt / peak_tau_);
  if (fabsf(y) > peak_) peak_ = fabsf(y);
  if (peak_ < min_peak_) peak_ = min_peak_;
  return clamp1(y / peak_);
}

// Waves: offset time constant 10 s (breathing is 0.1-0.5 Hz), peak decays over 8 s.
Tracker::Tracker() : breath_scale_(10.0f, 8.0f, 1e-4f), heart_scale_(10.0f, 8.0f, 1e-5f) {}

bool Tracker::apply(uint16_t type, const uint8_t *d, size_t len, uint32_t now) {
  any_ = true;
  last_frame_ms_ = now;
  switch (type) {
    case PHASES:
      if (len < 12) return false;
      breath_wave_ = breath_scale_.update(lef32(d + 4), now);
      heart_wave_ = heart_scale_.update(lef32(d + 8), now);
      has_phase_ = true;
      phase_ms_ = now;
      return true;
    case BREATH_RATE:
      if (len < 4) return false;
      breath_ = lef32(d);
      has_breath_ = finite(breath_) && breath_ > 0;    // the radar reports 0 while it has no estimate
      breath_ms_ = now;
      return true;
    case HEART_RATE:
      if (len < 4) return false;
      heart_ = lef32(d);
      has_heart_ = finite(heart_) && heart_ > 0;
      heart_ms_ = now;
      return true;
    case DISTANCE:
      if (len < 8) return false;
      distance_cm_ = lef32(d + 4);
      has_distance_ = le32(d) != 0 && finite(distance_cm_) && distance_cm_ >= 0;
      distance_ms_ = now;
      return true;
    case PRESENCE:
      if (len < 1) return false;
      has_presence_ = true;
      presence_ = d[0] != 0 || (len >= 2 && d[1] != 0);
      if (!presence_) has_breath_ = has_heart_ = false;   // like the stock ESPHome firmware
      return true;
    case FIRMWARE:
      if (len < 4) return false;
      fw_ = FirmwareVersion{d[0], d[1], d[2], d[3]};
      has_fw_ = true;
      return true;
    case TARGET_INFO:
    case POINT_CLOUD:
      return true;                                     // known, not used
    default:
      return false;
  }
}

bool Tracker::present(uint32_t now) const {
  if (!radar_alive(now)) return false;
  // Newer radar firmware reports presence explicitly; older one only gives a range when someone is there.
  if (has_presence_) return presence_;
  return fresh(has_distance_, distance_ms_, now, DISTANCE_STALE_MS);
}

Vitals Tracker::vitals(uint32_t now) const {
  Vitals v{};
  if (!present(now)) return v;
  bool breath = fresh(has_breath_, breath_ms_, now, RATE_STALE_MS);
  bool heart = fresh(has_heart_, heart_ms_, now, RATE_STALE_MS);
  v.valid = breath && heart;
  v.breath_rate = breath ? breath_ : 0;
  v.heart_rate = heart ? heart_ : 0;
  if (fresh(has_phase_, phase_ms_, now, WAVE_STALE_MS)) {
    v.breath_wave = breath_wave_;
    v.heart_wave = heart_wave_;
  }
  if (fresh(has_distance_, distance_ms_, now, DISTANCE_STALE_MS)) {
    float mm = distance_cm_ * CM_TO_MM;
    v.distance_mm = mm > 65535 ? 65535 : (uint16_t)lroundf(mm);
  }
  return v;
}

}  // namespace mr60
