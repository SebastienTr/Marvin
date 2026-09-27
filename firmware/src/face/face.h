// SPDX-License-Identifier: MIT
//
// The robot's eyes: a C++ port of host/marvin_host/face.py (constants, FaceParams table, springs,
// xorshift32, blinks, expressions, events, gaze). See docs/face.md.
//
// Same numbers, same order of random draws and the same double-precision behaviour as the
// Python reference, so a given seed and the same (state, events, t) sequence give the same frames
// (checked against Python golden frames in firmware/test/test_face). Time is supplied by the
// caller; nothing here reads a clock.
//
// Portable C++ (no Arduino).
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "raster.h"

namespace face {

// Events from the host brain (host/marvin_host/events.py). The numeric values are the wire codes
// of the FACE_EVENT message (docs/protocol.md); 0 is not an event.
enum class Event : uint8_t {
  NONE = 0,
  ARRIVED = 1,
  LEFT = 2,
  APPROACHED = 3,
  SAT_DOWN = 4,
  STOOD_UP = 5,
  STILL_LONG = 6,
  VITALS_ACQUIRED = 7,
  VITALS_LOST = 8,
};
constexpr uint8_t EVENT_MAX = 8;

// The presence state the face needs (a subset of events.PresenceState). Millimetres, device frame.
struct Presence {
  bool present = false;
  bool seated = false;
  bool has_head = false;
  double head[3] = {0, 0, 0};
  bool has_position = false;
  double position[3] = {0, 0, 0};
  bool has_distance = false;
  double distance_m = 0;
  double heart_rate = 0;  // beats per minute, 0 = unknown
};

enum class Expr : uint8_t { NEUTRAL, AWAKE, SURPRISED, ATTENTIVE, CONTENT, CALM, CONCERNED, SLEEPY, ASLEEP, COUNT };

const char *expr_name(Expr e);

// Eye geometry for one expression (face.FaceParams). Sizes in pixels, lids as fractions of the
// visible eye height.
struct FaceParams {
  static constexpr int N = 11;
  double width, height, radius, spacing, dy, open, lid_top, lid_tilt, lid_bottom, brightness, warmth;

  void to_array(double a[N]) const;
  static FaceParams from_array(const double a[N]);
};

extern const FaceParams EXPRESSIONS[(int)Expr::COUNT];

// Colours (face.py).
constexpr Rgb BACKGROUND = {20, 18, 17};
constexpr Rgb EYE_WHITE = {236, 230, 218};
constexpr Rgb EYE_AMBER = {255, 190, 120};
constexpr Rgb ACCENT = {238, 118, 38};

// Marsaglia xorshift32, seeded like face.XorShift32.
class XorShift32 {
 public:
  explicit XorShift32(uint32_t seed);
  uint32_t next_u32();
  double uniform(double lo, double hi);
  uint32_t state() const { return state_; }

 private:
  uint32_t state_;
};

// Exact step of a critically damped spring (face.spring_step), in place.
void spring_step(double *x, double *v, const double *target, double omega, double dt, int n);
double smoothstep(double u);

struct BlinkTiming {
  double close, hold, open;
  double total() const { return 0.0 + close + hold + open; }
};
constexpr BlinkTiming BLINK = {0.07, 0.04, 0.13};
constexpr BlinkTiming SLOW_BLINK = {0.35, 0.30, 0.55};

double blink_closure(double t, double start, const BlinkTiming &timing);

// Eye offset in pixels (screen x right, y down) to look at a device-frame point (mm).
void gaze_from_point(const double p[3], double *gx, double *gy);

Rgb eye_color(const FaceParams &p);

// Build the display list of one frame from explicit parameters (face.render).
void draw(const FaceParams &p, double gx, double gy, double blink, double accent, Raster &out);

// The animated face. Feed it events with `on_event` and call `update` every frame.
class Face {
 public:
  explicit Face(uint32_t seed = 0);

  // Queue an event; it takes effect at the next update.
  void on_event(Event ev);

  // Advance the animation to time t (seconds, monotonic) and build the frame's display list.
  // Then call raster().render(fb) to draw it (update_and_render does both).
  void update(const Presence &state, double t);
  Rect update_and_render(const Presence &state, double t, uint16_t *fb);

  const Raster &raster() const { return raster_; }
  Raster &raster() { return raster_; }
  Expr expression() const { return expression_; }
  FaceParams params() const { return FaceParams::from_array(p_); }
  // The last frame's inputs, after smoothing.
  double gaze_x() const { return gaze_[0]; }
  double gaze_y() const { return gaze_[1]; }
  double blink() const { return blink_level_; }
  double accent() const { return accent_out_; }

 private:
  static constexpr int MAX_PENDING = 16;
  static constexpr int MAX_BLINKS = 16;

  struct Blink {
    double start;
    BlinkTiming timing;
  };

  void track_presence(const Presence &s, double t);
  void wake(double t);
  void handle(Event ev, const Presence &s, double t);
  Expr choose_expression(const Presence &s, double t);
  double gaze_target(const Presence &s, double t, Expr name, double target[2]);
  void micro_saccade(double t, double out[2]);
  void idle_glance(double t, double out[2]);
  double search(double t, double target[2]);
  double blink_at(double t, Expr name);
  void add_blink(double start, const BlinkTiming &timing);
  double accent_at(double t, double dt, const Presence &s);

  XorShift32 rng_;
  Raster raster_;
  Event pending_[MAX_PENDING];
  int n_pending_ = 0;
  bool has_t_ = false;
  double t_ = 0;

  double p_[FaceParams::N];
  double pv_[FaceParams::N];
  double gaze_[2] = {0, 0};
  double gaze_v_[2] = {0, 0};

  Expr expression_ = Expr::ASLEEP;
  bool has_transient_ = false;
  Expr transient_ = Expr::NEUTRAL;
  double transient_until_ = 0;
  bool has_absent_since_ = true;   // asleep until someone shows up
  double absent_since_;            // -inf at start
  bool awake_ = false;
  double last_side_ = 0;

  Blink blinks_[MAX_BLINKS];
  int n_blinks_ = 0;
  bool has_next_blink_ = false;
  double next_blink_ = 0;
  double micro_[2] = {0, 0};
  double next_micro_ = 0;
  double wander_[2] = {0, 0};
  double wander_until_ = 0;
  double next_wander_ = 0;
  double accent_until_;            // -inf at start
  double accent_level_ = 0;
  double accent_t0_ = 0;

  double blink_level_ = 0;
  double accent_out_ = 0;
};

}  // namespace face
