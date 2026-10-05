// SPDX-License-Identifier: MIT
#include "head_logic.h"

#include <math.h>

namespace head {

namespace {

constexpr double PI = 3.14159265358979323846;
constexpr double SNAP_DEG = 0.05;  // closer than this to the goal: there

inline double clampd(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline bool reached(uint32_t now, uint32_t at) { return (int32_t)(now - at) >= 0; }

// The LD2450's signed fields: bit 15 set = positive, magnitude in bits 0-14.
inline int32_t ld2450_signed(uint16_t v) { return (v & 0x8000) ? (int32_t)(v & 0x7FFF) : -(int32_t)(v & 0x7FFF); }

inline int16_t deci(double deg) {
  long v = lround(deg * 10.0);
  return (int16_t)(v < -32767 ? -32767 : (v > 32767 ? 32767 : v));
}

}  // namespace

bool nearest_target(const uint8_t *f, size_t n, int x_sign, Target &out) {
  out = Target();
  if (f == nullptr || n < 30 || f[0] != 0xAA || f[1] != 0xFF || f[2] != 0x03 || f[3] != 0x00 || f[28] != 0x55 ||
      f[29] != 0xCC)
    return false;
  double best = 0;
  for (int i = 0; i < 3; i++) {
    const uint8_t *t = f + 4 + 8 * i;
    const uint16_t rx = proto::get16(t), ry = proto::get16(t + 2), rs = proto::get16(t + 4), rr = proto::get16(t + 6);
    if (rx == 0 && ry == 0 && rs == 0 && rr == 0) continue;  // an empty slot
    const int32_t x = ld2450_signed(rx) * (x_sign < 0 ? -1 : 1), y = ld2450_signed(ry);
    if (y <= 0) continue;
    const double d = hypot((double)x, (double)y);
    if (d > MAX_RANGE_MM || (out.valid && d >= best)) continue;
    best = d;
    out.valid = true;
    out.x_mm = (int16_t)x;
    out.y_mm = (int16_t)y;
    out.distance_mm = (uint16_t)lround(d);
    out.yaw_deg = atan2((double)x, (double)y) * 180.0 / PI;
  }
  return out.valid;
}

// ---- Tracker ----------------------------------------------------------------------------------

Tracker::Tracker(const Config &config, uint32_t seed) : c_(config), rng_(seed ? seed : 1) {}

uint32_t Tracker::rand_range(uint32_t lo, uint32_t hi) {
  rng_ ^= rng_ << 13;  // xorshift32
  rng_ ^= rng_ >> 17;
  rng_ ^= rng_ << 5;
  return hi > lo ? lo + rng_ % (hi - lo + 1) : lo;
}

double Tracker::min_yaw() const { return c_.mirror ? c_.center_deg - c_.max_deg : c_.min_deg - c_.center_deg; }
double Tracker::max_yaw() const { return c_.mirror ? c_.center_deg - c_.min_deg : c_.max_deg - c_.center_deg; }
double Tracker::clamp_yaw(double y) const { return clampd(y, min_yaw(), max_yaw()); }

double Tracker::servo_deg() const {
  return clampd(c_.center_deg + (c_.mirror ? -yaw_ : yaw_), c_.min_deg, c_.max_deg);
}

void Tracker::on_target(const Target &t, uint32_t now_ms) {
  has_target_ = t.valid;
  if (!t.valid) return;
  const double rel = t.yaw_deg + (c_.radar_on_head ? yaw_ : 0.0);
  const uint32_t since = now_ms - last_seen_ms_;
  if (!seen_ || since > c_.lost_ms || !has_filtered_) {
    filtered_ = rel;  // a new target: no smoothing from an old one
    has_filtered_ = true;
  } else {
    const double alpha = c_.smoothing_s > 0 ? 1.0 - exp(-(since / 1000.0) / c_.smoothing_s) : 1.0;
    filtered_ += (rel - filtered_) * alpha;
  }
  target_yaw_ = rel;
  target_distance_mm_ = t.distance_mm;
  seen_ = true;
  last_seen_ms_ = now_ms;
  idle_since_ms_ = now_ms;
  next_glance_ms_ = now_ms + c_.idle_ms;
}

bool Tracker::set_mode(uint8_t mode, double manual_deg, uint32_t) {
  if (mode != proto::HEAD_AUTO && mode != proto::HEAD_MANUAL && mode != proto::HEAD_CENTER) return false;
  mode_ = mode;
  manual_ = clamp_yaw(manual_deg);
  glancing_ = false;
  return true;
}

void Tracker::host_lost(uint32_t) {
  if (mode_ == proto::HEAD_MANUAL) mode_ = proto::HEAD_AUTO;
}

double Tracker::step(uint32_t now_ms) {
  if (!has_clock_) {
    has_clock_ = true;
    last_ms_ = now_ms;
    idle_since_ms_ = now_ms;
    next_glance_ms_ = now_ms + c_.idle_ms;
  }
  const double dt = (uint32_t)(now_ms - last_ms_) / 1000.0;
  last_ms_ = now_ms;

  double cap = c_.max_speed_dps;
  tracking_ = mode_ == proto::HEAD_AUTO && seen_ && (uint32_t)(now_ms - last_seen_ms_) <= c_.lost_ms;
  if (mode_ == proto::HEAD_CENTER) {
    goal_ = 0;
    cap = c_.glance_speed_dps;
    glancing_ = false;
  } else if (mode_ == proto::HEAD_MANUAL) {
    goal_ = manual_;
    glancing_ = false;
  } else if (tracking_) {
    glancing_ = false;
    const double want = clamp_yaw(filtered_);
    if (fabs(want - goal_) > c_.deadband_deg) goal_ = want;
  } else {
    const bool idle = (uint32_t)(now_ms - idle_since_ms_) >= c_.idle_ms;
    if (idle && !glancing_ && reached(now_ms, next_glance_ms_)) {
      const double amp = rand_range((uint32_t)(c_.glance_max_deg / 3), (uint32_t)c_.glance_max_deg);
      glance_yaw_ = clamp_yaw((rand_range(0, 1) ? amp : -amp));
      const double travel_s = fabs(glance_yaw_ - yaw_) / (c_.glance_speed_dps > 0 ? c_.glance_speed_dps : 1);
      glance_back_ms_ = now_ms + (uint32_t)(travel_s * 1500.0) + c_.glance_hold_ms;  // the soft arrival is slower
      glancing_ = true;
    }
    if (glancing_ && reached(now_ms, glance_back_ms_)) {
      glancing_ = false;
      next_glance_ms_ = now_ms + rand_range(c_.glance_every_min_ms, c_.glance_every_max_ms);
    }
    goal_ = glancing_ ? glance_yaw_ : 0.0;
    cap = glancing_ ? c_.glance_speed_dps : c_.return_speed_dps;
  }

  const double err = goal_ - yaw_;
  if (fabs(err) <= SNAP_DEG) {
    yaw_ = goal_;
    speed_ = 0;
    moving_ = false;
  } else {
    speed_ = clampd(err * c_.gain_per_s, -cap, cap);
    double d = speed_ * dt;
    if (fabs(d) > fabs(err)) d = err;
    yaw_ += d;
    moving_ = true;
  }
  return servo_deg();
}

// ---- Presence ---------------------------------------------------------------------------------

PresenceEvent Presence::on_frame(bool target, uint32_t now_ms) {
  if (!started_) {
    started_ = true;
    absent_since_ms_ = now_ms;
  }
  if (!target) {
    streak_ = 0;
    return step(now_ms);
  }
  if (streak_ < ARRIVE_FRAMES) streak_++;
  if (present_) {
    last_seen_ms_ = now_ms;
    return PresenceEvent::NONE;
  }
  if (streak_ < ARRIVE_FRAMES) return PresenceEvent::NONE;
  present_ = true;
  last_seen_ms_ = now_ms;
  return (uint32_t)(now_ms - absent_since_ms_) >= ABSENT_MS ? PresenceEvent::HELLO : PresenceEvent::NONE;
}

PresenceEvent Presence::step(uint32_t now_ms) {
  if (!started_) {
    started_ = true;
    absent_since_ms_ = now_ms;
  }
  if (!present_ || (uint32_t)(now_ms - last_seen_ms_) < ABSENT_MS) return PresenceEvent::NONE;
  present_ = false;
  absent_since_ms_ = last_seen_ms_;
  return PresenceEvent::BYE;
}

// ---- Payloads ---------------------------------------------------------------------------------

size_t payload(uint8_t *p, const Tracker &t, bool servo_on) {
  proto::Head h;
  h.mode = t.mode();
  h.flags = (t.has_target() ? proto::HEAD_TARGET : 0) | (t.tracking() ? proto::HEAD_TRACKING : 0) |
            (t.glancing() ? proto::HEAD_GLANCING : 0) | (servo_on ? proto::HEAD_SERVO_ON : 0);
  h.yaw_dd = deci(t.yaw());
  h.goal_dd = deci(t.goal());
  if (t.has_target()) {
    h.target_yaw_dd = deci(t.target_yaw());
    h.target_distance_mm = t.target_distance_mm();
  }
  return proto::head(p, h);
}

void face_point(const Target &t, double head_yaw_deg, double z_mm, double out[3]) {
  const double r = hypot((double)t.x_mm, (double)t.y_mm);
  const double a = atan2((double)t.x_mm, (double)t.y_mm) - head_yaw_deg * PI / 180.0;
  out[0] = r * sin(a);
  out[1] = -r * cos(a);
  out[2] = z_mm;
}

}  // namespace head
