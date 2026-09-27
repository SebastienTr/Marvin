// SPDX-License-Identifier: MIT
#include "face.h"

#include <math.h>

namespace face {

namespace {

constexpr double PI = 3.141592653589793;

// Layout and behaviour constants: the values of face.py, one for one.
constexpr double SCREEN_POS_MM[3] = {0.0, -38.0, 92.0};
constexpr double EYE_CX = 120.0;
constexpr double EYE_CY = 146.0;
constexpr double CLOSED_H = 5.0;
constexpr double CLOSE_DROP = 0.35;
constexpr double PERSPECTIVE = 0.06;
constexpr double LID_MARGIN = 3.0;
constexpr double ACCENT_POS[2] = {120.0, 228.0};
constexpr double ACCENT_R = 4.5;

constexpr double GAZE_X_PX = 28.0;
constexpr double GAZE_Y_PX = 22.0;
constexpr double GAZE_YAW_FULL = 50.0 * (PI / 180.0);    // math.radians(50)
constexpr double GAZE_PITCH_FULL = 60.0 * (PI / 180.0);  // math.radians(60)
constexpr double HEAD_Z_SEATED = 400.0;
constexpr double HEAD_Z_STANDING = 950.0;
constexpr double FAR_M = 2.2;

constexpr double GAZE_OMEGA = 16.0;
constexpr double SACCADE_OMEGA = 24.0;
constexpr double EXPR_OMEGA = 7.0;
constexpr double SLEEP_OMEGA = 1.6;
constexpr double WAKE_OMEGA = 9.0;

constexpr double BLINK_EVERY_S[2] = {2.0, 6.0};
constexpr double BLINK_DOUBLE_P = 0.15;
constexpr double MICRO_EVERY_S[2] = {0.8, 2.8};
constexpr double MICRO_PX = 3.0;
constexpr double WANDER_EVERY_S[2] = {1.2, 3.5};
constexpr double WANDER_HOLD_S[2] = {0.5, 1.4};

constexpr double LOOK_AROUND_S = 4.0;
constexpr double SLEEPY_AFTER_S = 4.0;
constexpr double ASLEEP_AFTER_S = 20.0;
constexpr double BREATH_PERIOD_S = 5.5;
constexpr double BREATH_DEPTH = 0.25;

constexpr double ACCENT_SHOW_S = 5.0;
constexpr double ACCENT_FADE_S = 0.4;

constexpr int OPEN = 5;  // index of `open` in FaceParams

// face.TRANSIENTS: expression held for this long after the event.
struct Transient {
  Event event;
  Expr expr;
  double hold_s;
};
constexpr Transient TRANSIENTS[] = {
    {Event::ARRIVED, Expr::AWAKE, 1.6},       {Event::APPROACHED, Expr::SURPRISED, 1.0},
    {Event::SAT_DOWN, Expr::CONTENT, 3.5},    {Event::STOOD_UP, Expr::ATTENTIVE, 2.0},
    {Event::STILL_LONG, Expr::CONCERNED, 7.0},
};

const Transient *find_transient(Event ev) {
  for (const Transient &tr : TRANSIENTS)
    if (tr.event == ev) return &tr;
  return nullptr;
}

inline double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
// Python's max(a, b): a unless b is greater.
inline double pymax(double a, double b) { return b > a ? b : a; }

}  // namespace

// Order and values: face.EXPRESSIONS.
//                                    width height radius spacing dy  open lid_top tilt  bottom bright warmth
const FaceParams EXPRESSIONS[(int)Expr::COUNT] = {
    /* neutral   */ {64, 82, 22, 104, 0, 1, 0.0, 0.0, 0.0, 1.0, 0.1},
    /* awake     */ {66, 88, 24, 106, 0, 1, 0.0, 0.0, 0.0, 1.0, 0.1},
    /* surprised */ {72, 94, 32, 110, -4, 1, 0.0, 0.0, 0.0, 1.0, 0.1},
    /* attentive */ {60, 88, 20, 104, 0, 1, 0.0, 0.0, 0.0, 1.0, 0.1},
    /* content   */ {68, 76, 26, 106, 0, 1, 0.0, 0.0, 0.36, 1.0, 0.2},
    /* calm      */ {68, 68, 22, 104, 2, 1, 0.0, 0.0, 0.0, 1.0, 0.15},
    /* concerned */ {64, 80, 16, 104, 0, 1, 0.40, 0.40, 0.0, 1.0, 0.2},
    /* sleepy    */ {66, 74, 22, 104, 4, 1, 0.55, 0.0, 0.0, 0.9, 0.45},
    /* asleep    */ {60, 74, 22, 104, 14, 0, 0.0, 0.0, 0.0, 0.7, 0.9},
};

const char *expr_name(Expr e) {
  static const char *const names[] = {"neutral", "awake",     "surprised", "attentive", "content",
                                      "calm",    "concerned", "sleepy",    "asleep"};
  return (int)e < (int)Expr::COUNT ? names[(int)e] : "?";
}

void FaceParams::to_array(double a[N]) const {
  const double v[N] = {width, height, radius, spacing, dy, open, lid_top, lid_tilt, lid_bottom, brightness, warmth};
  for (int i = 0; i < N; i++) a[i] = v[i];
}

FaceParams FaceParams::from_array(const double a[N]) {
  return FaceParams{a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10]};
}

// -- pure helpers -------------------------------------------------------------------------------

XorShift32::XorShift32(uint32_t seed) {
  uint32_t s = seed * 2654435761u + 0x9E3779B9u;
  state_ = s ? s : 0x6D2B79F5u;
}

uint32_t XorShift32::next_u32() {
  uint32_t x = state_;
  x ^= x << 13;
  x ^= x >> 17;
  x ^= x << 5;
  state_ = x;
  return x;
}

double XorShift32::uniform(double lo, double hi) { return lo + (hi - lo) * (next_u32() / 4294967296.0); }

void spring_step(double *x, double *v, const double *target, double omega, double dt, int n) {
  const double e = exp(-omega * dt);
  for (int i = 0; i < n; i++) {
    double x0 = x[i] - target[i];
    double c = v[i] + omega * x0;
    x[i] = target[i] + (x0 + c * dt) * e;
    v[i] = (v[i] - omega * c * dt) * e;
  }
}

double smoothstep(double u) {
  u = u > 1.0 ? 1.0 : u;
  u = u < 0.0 ? 0.0 : u;
  return u * u * (3.0 - 2.0 * u);
}

double blink_closure(double t, double start, const BlinkTiming &tm) {
  double u = t - start;
  if (u < 0 || u > tm.close + tm.hold + tm.open) return 0.0;
  if (u < tm.close) return smoothstep(u / tm.close);
  if (u < tm.close + tm.hold) return 1.0;
  return 1.0 - smoothstep((u - tm.close - tm.hold) / tm.open);
}

void gaze_from_point(const double p[3], double *gx, double *gy) {
  double dx = p[0] - SCREEN_POS_MM[0];
  double dy = p[1] - SCREEN_POS_MM[1];
  double dz = p[2] - SCREEN_POS_MM[2];
  double yaw = atan2(dx, pymax(-dy, 1.0));
  double pitch = atan2(dz, hypot(dx, dy));
  *gx = GAZE_X_PX * clamp(yaw / GAZE_YAW_FULL, -1.0, 1.0);
  *gy = -GAZE_Y_PX * clamp(pitch / GAZE_PITCH_FULL, -1.0, 1.0);
}

Rgb eye_color(const FaceParams &p) {
  double w = clamp(p.warmth, 0.0, 1.0);
  double b = clamp(p.brightness, 0.0, 1.0);
  auto mix = [&](double bg, double c0, double c1) { return (float)(bg + b * ((1 - w) * c0 + w * c1 - bg)); };
  return Rgb{mix(BACKGROUND.r, EYE_WHITE.r, EYE_AMBER.r), mix(BACKGROUND.g, EYE_WHITE.g, EYE_AMBER.g),
             mix(BACKGROUND.b, EYE_WHITE.b, EYE_AMBER.b)};
}

void draw(const FaceParams &p, double gx, double gy, double blink, double accent, Raster &out) {
  out.begin(BACKGROUND);
  const Rgb color = eye_color(p);
  const double lean = clamp(gx / GAZE_X_PX, -1.0, 1.0);
  const double openness = clamp(p.open * (1.0 - blink), 0.0, 1.0);
  static const double SIDES[2] = {-1.0, 1.0};  // left of the screen, right
  for (double side : SIDES) {
    double k = 1.0 + side * PERSPECTIVE * lean;
    double w = p.width * k, h = p.height * k;
    double cx = EYE_CX + side * p.spacing / 2 + gx;
    double h_eff = pymax(CLOSED_H, h * openness);
    double cy = EYE_CY + p.dy + gy + (h - h_eff) * CLOSE_DROP;
    double top = cy - h_eff / 2, bottom = cy + h_eff / 2;
    out.fill_round_rect(cx, cy, w, h_eff, p.radius * k, color);

    if (p.lid_top > 1e-3 && h_eff > CLOSED_H) {
      double y_mid = top + p.lid_top * h_eff;
      double slope = p.lid_tilt * h_eff / 2;
      double x_in = cx - side * (w / 2 + LID_MARGIN);
      double x_out = cx + side * (w / 2 + LID_MARGIN);
      double y_cap = top - LID_MARGIN;
      double y_in = pymax(y_mid - slope, y_cap + 1);
      double y_out = pymax(y_mid + slope, y_cap + 1);
      const double quad[8] = {x_in, y_cap, x_out, y_cap, x_out, y_out, x_in, y_in};
      out.fill_quad(quad, BACKGROUND);
    }
    if (p.lid_bottom > 1e-3 && h_eff > CLOSED_H) {
      double rx = w * 0.56, ry = h_eff * 0.5;
      out.fill_ellipse(cx, bottom - p.lid_bottom * h_eff + ry, rx, ry, BACKGROUND);
    }
  }
  if (accent > 1e-3) out.fill_circle(ACCENT_POS[0], ACCENT_POS[1], ACCENT_R, ACCENT, accent < 1.0 ? accent : 1.0);
}

// -- behaviour ----------------------------------------------------------------------------------

Face::Face(uint32_t seed) : rng_(seed), absent_since_(-INFINITY), accent_until_(-INFINITY) {
  EXPRESSIONS[(int)Expr::ASLEEP].to_array(p_);
  for (double &v : pv_) v = 0.0;
}

void Face::on_event(Event ev) {
  if (ev == Event::NONE || (uint8_t)ev > EVENT_MAX || n_pending_ >= MAX_PENDING) return;
  pending_[n_pending_++] = ev;
}

Rect Face::update_and_render(const Presence &state, double t, uint16_t *fb) {
  update(state, t);
  return raster_.render(fb);
}

void Face::update(const Presence &state, double t) {
  double dt = has_t_ ? clamp(t - t_, 0.0, 0.25) : 0.0;
  has_t_ = true;
  t_ = t;
  if (!has_next_blink_) {
    next_blink_ = t + rng_.uniform(BLINK_EVERY_S[0], BLINK_EVERY_S[1]);
    has_next_blink_ = true;
  }

  track_presence(state, t);
  for (int i = 0; i < n_pending_; i++) handle(pending_[i], state, t);
  n_pending_ = 0;

  Expr name = choose_expression(state, t);
  expression_ = name;
  double target[FaceParams::N];
  EXPRESSIONS[(int)name].to_array(target);
  double omega = EXPR_OMEGA;
  if (name == Expr::ASLEEP || name == Expr::SLEEPY)
    omega = SLEEP_OMEGA;
  else if (p_[OPEN] < 0.5)  // eyes (nearly) closed and opening: wake up briskly
    omega = WAKE_OMEGA;
  spring_step(p_, pv_, target, omega, dt, FaceParams::N);

  double gaze_t[2];
  double gaze_omega = gaze_target(state, t, name, gaze_t);
  spring_step(gaze_, gaze_v_, gaze_t, gaze_omega, dt, 2);

  blink_level_ = blink_at(t, name);
  FaceParams params = FaceParams::from_array(p_);
  if (name == Expr::ASLEEP) {
    double breath = 0.5 - 0.5 * cos(2 * PI * t / BREATH_PERIOD_S);
    params.brightness = params.brightness * (1 - BREATH_DEPTH * breath);
  }
  accent_out_ = accent_at(t, dt, state);
  draw(params, gaze_[0], gaze_[1], blink_level_, accent_out_, raster_);
}

void Face::track_presence(const Presence &s, double t) {
  if (s.present) {
    has_absent_since_ = false;
    bool arriving = false;
    for (int i = 0; i < n_pending_; i++) arriving |= pending_[i] == Event::ARRIVED;
    if (!awake_ && !arriving) wake(t);  // present without ARRIVED: wake anyway
    const double *p = s.has_head ? s.head : (s.has_position ? s.position : nullptr);
    if (p) last_side_ = fabs(p[0]) > 50 ? copysign(1.0, p[0]) : 0.0;
  } else if (!has_absent_since_) {
    has_absent_since_ = true;
    absent_since_ = t;
  }
}

void Face::wake(double t) {
  awake_ = true;
  has_transient_ = true;
  transient_ = Expr::AWAKE;
  transient_until_ = t + find_transient(Event::ARRIVED)->hold_s;
  add_blink(t + 0.7, BLINK);
}

void Face::handle(Event ev, const Presence &s, double t) {
  if (ev == Event::ARRIVED) {
    wake(t);
  } else if (ev == Event::LEFT) {
    if (!has_absent_since_ && !s.present) {
      has_absent_since_ = true;
      absent_since_ = t;
    }
    has_transient_ = false;
  } else if (const Transient *tr = find_transient(ev)) {
    has_transient_ = true;
    transient_ = tr->expr;
    transient_until_ = t + tr->hold_s;
    if (ev == Event::STILL_LONG) {
      add_blink(t + 0.4, SLOW_BLINK);
      add_blink(t + 1.9, SLOW_BLINK);
    } else if (ev == Event::APPROACHED) {
      add_blink(t + 0.9, BLINK);
    }
  } else if (ev == Event::VITALS_ACQUIRED) {
    accent_until_ = t + ACCENT_SHOW_S;
    accent_t0_ = t;
    add_blink(t + 0.2, SLOW_BLINK);
  } else if (ev == Event::VITALS_LOST) {
    accent_until_ = accent_until_ < t ? accent_until_ : t;
  }
}

Expr Face::choose_expression(const Presence &s, double t) {
  if (has_absent_since_) {
    has_transient_ = false;
    double gone = t - absent_since_;
    if (gone >= ASLEEP_AFTER_S) {
      awake_ = false;
      return Expr::ASLEEP;
    }
    if (gone >= SLEEPY_AFTER_S) return Expr::SLEEPY;
    return Expr::NEUTRAL;
  }
  if (has_transient_) {
    if (t < transient_until_) return transient_;
    has_transient_ = false;
  }
  return s.seated ? Expr::CALM : Expr::NEUTRAL;
}

double Face::gaze_target(const Presence &s, double t, Expr name, double target[2]) {
  if (name == Expr::ASLEEP) {
    target[0] = 0.0;
    target[1] = 4.0;
    return SLEEP_OMEGA;
  }
  if (has_absent_since_) return search(t, target);

  double p[3];
  bool has_p = false;
  if (s.has_head) {
    p[0] = s.head[0], p[1] = s.head[1], p[2] = s.head[2];
    has_p = true;
  } else if (s.has_position) {
    p[0] = s.position[0], p[1] = s.position[1], p[2] = s.seated ? HEAD_Z_SEATED : HEAD_Z_STANDING;
    has_p = true;
  }
  bool far = s.has_distance && s.distance_m > FAR_M;
  if (!has_p || far) {
    double base[2] = {0.0, 0.0};
    if (has_p) {
      gaze_from_point(p, &base[0], &base[1]);
      base[0] *= 0.6;
      base[1] *= 0.6;
    }
    double g[2];
    idle_glance(t, g);
    target[0] = base[0] + g[0];
    target[1] = base[1] + g[1];
    return SACCADE_OMEGA;
  }
  double m[2];
  gaze_from_point(p, &target[0], &target[1]);
  micro_saccade(t, m);
  target[0] += m[0];
  target[1] += m[1];
  return GAZE_OMEGA;
}

void Face::micro_saccade(double t, double out[2]) {
  if (t >= next_micro_) {
    double mx = rng_.uniform(-MICRO_PX, MICRO_PX);
    double my = rng_.uniform(-MICRO_PX, MICRO_PX) * 0.6;
    micro_[0] = mx;
    micro_[1] = my;
    next_micro_ = t + rng_.uniform(MICRO_EVERY_S[0], MICRO_EVERY_S[1]);
  }
  out[0] = micro_[0];
  out[1] = micro_[1];
}

void Face::idle_glance(double t, double out[2]) {
  if (t >= next_wander_) {
    double wx = rng_.uniform(-0.8, 0.8) * GAZE_X_PX;
    double wy = rng_.uniform(-0.4, 0.3) * GAZE_Y_PX;
    wander_[0] = wx;
    wander_[1] = wy;
    wander_until_ = t + rng_.uniform(WANDER_HOLD_S[0], WANDER_HOLD_S[1]);
    next_wander_ = t + rng_.uniform(WANDER_EVERY_S[0], WANDER_EVERY_S[1]);
  }
  bool on = t < wander_until_;
  out[0] = on ? wander_[0] : 0.0;
  out[1] = on ? wander_[1] : 0.0;
}

double Face::search(double t, double target[2]) {
  // After someone left: glance where they went, the other way, then settle down.
  double gone = t - absent_since_;
  if (gone >= LOOK_AROUND_S) {
    target[0] = 0.0;
    target[1] = 2.0;
    return EXPR_OMEGA;
  }
  double side = last_side_ != 0.0 ? last_side_ : 1.0;
  const double steps[3] = {side * 0.9, -side * 0.8, side * 0.3};
  int i = (int)(gone / (LOOK_AROUND_S / 3));
  if (i > 2) i = 2;
  target[0] = steps[i] * GAZE_X_PX;
  target[1] = -0.1 * GAZE_Y_PX;
  return SACCADE_OMEGA;
}

void Face::add_blink(double start, const BlinkTiming &timing) {
  if (n_blinks_ < MAX_BLINKS) blinks_[n_blinks_++] = Blink{start, timing};
}

double Face::blink_at(double t, Expr name) {
  if (name == Expr::ASLEEP) {
    n_blinks_ = 0;
    next_blink_ = t + rng_.uniform(BLINK_EVERY_S[0], BLINK_EVERY_S[1]);
    return 0.0;
  }
  if (t >= next_blink_) {
    const BlinkTiming &timing = name == Expr::SLEEPY ? SLOW_BLINK : BLINK;
    add_blink(t, timing);
    if (rng_.uniform(0.0, 1.0) < BLINK_DOUBLE_P) add_blink(t + timing.total() + 0.08, timing);
    next_blink_ = t + rng_.uniform(BLINK_EVERY_S[0], BLINK_EVERY_S[1]);
  }
  int kept = 0;
  double closure = 0.0;
  for (int i = 0; i < n_blinks_; i++) {
    if (!(t <= blinks_[i].start + blinks_[i].timing.total())) continue;
    blinks_[kept++] = blinks_[i];
    closure = pymax(closure, blink_closure(t, blinks_[i].start, blinks_[i].timing));
  }
  n_blinks_ = kept;
  return closure;
}

double Face::accent_at(double t, double dt, const Presence &s) {
  double target = (t < accent_until_ && s.present) ? 1.0 : 0.0;
  // First-order fade (time constant ACCENT_FADE_S), then a gentle pulse at the heart rate.
  accent_level_ += (target - accent_level_) * (1.0 - exp(-dt / ACCENT_FADE_S));
  if (accent_level_ < 1e-3) return 0.0;
  double bpm = s.heart_rate != 0.0 ? s.heart_rate : 60.0;
  double pulse = 0.5 + 0.5 * cos(2 * PI * (t - accent_t0_) * bpm / 60.0);
  return accent_level_ * (0.45 + 0.55 * pulse);
}

}  // namespace face
