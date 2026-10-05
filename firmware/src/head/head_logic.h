// SPDX-License-Identifier: MIT
//
// The head's logic, without the hardware: the nearest LD2450 target, where the head should look
// (tracking, a slow return to the centre, an occasional glance around when nobody has been there for a
// while, or the host's command), how fast it gets there, and when someone arrives or leaves for the
// presence chirps. Used by head/head_io.h on the board.
//
// Angles: the head's yaw in degrees, 0 straight ahead, positive towards the device frame's +x (the
// LD2450's +x, docs/protocol.md). The servo angle is centre + yaw (centre - yaw when mirrored).
//
// Portable C++ (no Arduino), unit-tested with `pio test -e native`. Time is supplied by the caller in
// milliseconds and may wrap.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "../protocol.h"

namespace head {

// ---- LD2450 targets ---------------------------------------------------------------------------

struct Target {
  bool valid = false;
  int16_t x_mm = 0;       // right (the device frame's +x: the radar's x times x_sign)
  int16_t y_mm = 0;       // straight ahead, radar frame
  uint16_t distance_mm = 0;
  double yaw_deg = 0;     // atan2(x, y)
};

// Targets further than this, or behind the radar, are ignored.
constexpr uint16_t MAX_RANGE_MM = 6000;

// The nearest valid target of a raw 30-byte LD2450 frame (AA FF 03 00 ... 55 CC, as sent in LD2450
// datagrams). x_sign is -1 for a radar mounted the other way round. Returns false (and out.valid
// false) when the frame has no usable target or is not a target frame.
bool nearest_target(const uint8_t *frame, size_t n, int x_sign, Target &out);

// ---- Where the head looks ---------------------------------------------------------------------

struct Config {
  double center_deg = 90;         // servo angle looking straight ahead
  double min_deg = 20;            // servo limits
  double max_deg = 160;
  bool mirror = false;            // servo turns the other way (mounting)
  bool radar_on_head = false;     // the LD2450 turns with the head: its targets are relative to it
  double smoothing_s = 0.25;      // time constant of the target's exponential smoothing
  double deadband_deg = 3;        // the goal moves only when the smoothed target leaves this band
  double max_speed_dps = 120;     // slew limit while tracking
  double gain_per_s = 5;          // speed = error * gain, capped: a soft arrival
  uint32_t lost_ms = 3000;        // no target this long: back to the centre...
  double return_speed_dps = 30;   // ...slowly
  uint32_t idle_ms = 20000;       // nobody this long: an occasional glance around
  double glance_max_deg = 30;     // glance amplitude (random, at least a third of it)
  double glance_speed_dps = 45;
  uint32_t glance_hold_ms = 1200;
  uint32_t glance_every_min_ms = 6000;
  uint32_t glance_every_max_ms = 15000;
};

class Tracker {
 public:
  explicit Tracker(const Config &config = Config(), uint32_t seed = 1);

  // One LD2450 frame's nearest target (valid false when there is none), at now_ms.
  void on_target(const Target &t, uint32_t now_ms);
  // A mode from the host (proto::HeadMode); manual_deg is the yaw for HEAD_MANUAL. Unknown modes are
  // ignored (false).
  bool set_mode(uint8_t mode, double manual_deg, uint32_t now_ms);
  // The link to the host was lost: a manual head goes back to auto.
  void host_lost(uint32_t now_ms);

  // Advance to now_ms. Returns the servo angle, degrees, within the limits.
  double step(uint32_t now_ms);

  double yaw() const { return yaw_; }                 // where the head points now
  double goal() const { return goal_; }               // where it is going
  uint8_t mode() const { return mode_; }
  bool tracking() const { return tracking_; }         // following a target seen less than lost_ms ago
  bool glancing() const { return glancing_; }
  bool moving() const { return moving_; }
  bool has_target() const { return has_target_; }
  double target_yaw() const { return target_yaw_; }   // the last target, relative to the centre
  uint16_t target_distance_mm() const { return target_distance_mm_; }
  double servo_deg() const;

  // Yaw limits from the servo limits (the mirror swaps them).
  double min_yaw() const;
  double max_yaw() const;

 private:
  uint32_t rand_range(uint32_t lo, uint32_t hi);
  double clamp_yaw(double y) const;

  Config c_;
  uint32_t rng_;
  bool has_clock_ = false;
  uint32_t last_ms_ = 0;
  uint8_t mode_ = proto::HEAD_AUTO;
  double manual_ = 0;
  double yaw_ = 0;
  double goal_ = 0;
  double speed_ = 0;
  bool moving_ = false;

  bool has_target_ = false;      // the last frame had a target
  bool seen_ = false;            // a target was seen since boot
  uint32_t last_seen_ms_ = 0;
  double target_yaw_ = 0;        // raw, relative to the centre
  double filtered_ = 0;          // smoothed
  bool has_filtered_ = false;
  uint16_t target_distance_mm_ = 0;
  bool tracking_ = false;

  uint32_t idle_since_ms_ = 0;   // nobody since (boot, or the last target)
  bool glancing_ = false;
  uint32_t next_glance_ms_ = 0;
  uint32_t glance_back_ms_ = 0;
  double glance_yaw_ = 0;
};

// ---- Presence chirps --------------------------------------------------------------------------

// Someone arriving after nobody for ABSENT_MS, and leaving for ABSENT_MS. A target counts once seen
// in ARRIVE_FRAMES frames in a row, so a single ghost frame does not say hello. Nobody counts from
// boot: no hello in the first ABSENT_MS.
enum class PresenceEvent : uint8_t { NONE, HELLO, BYE };

class Presence {
 public:
  static constexpr uint32_t ABSENT_MS = 10000;
  static constexpr int ARRIVE_FRAMES = 3;

  // One LD2450 frame: was there a target.
  PresenceEvent on_frame(bool target, uint32_t now_ms);
  // Call often: says BYE once nobody has been seen for ABSENT_MS.
  PresenceEvent step(uint32_t now_ms);
  bool present() const { return present_; }

 private:
  bool started_ = false;
  uint32_t absent_since_ms_ = 0;
  bool present_ = false;
  int streak_ = 0;
  uint32_t last_seen_ms_ = 0;
};

// ---- HEAD payload -----------------------------------------------------------------------------

// The HEAD payload (proto::HEAD_SIZE bytes) for the tracker's state. Returns its size.
size_t payload(uint8_t *p, const Tracker &t, bool servo_on);

// A target point for the eyes, in the device frame (x right, forward is -y, z up, mm), turned by
// -head_yaw_deg: the target as seen from a screen that turns with the head (pass 0 when the screen
// does not turn, or when the radar turns with the head). z_mm: the height the eyes look at.
void face_point(const Target &t, double head_yaw_deg, double z_mm, double out[3]);

}  // namespace head
