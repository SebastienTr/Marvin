// SPDX-License-Identifier: MIT
#include "eyes.h"

#include <math.h>

#include "../audio/earcons.h"
#include "../protocol.h"

namespace bench {

namespace {

// face.cpp's layout constants (face.draw), in face pixels.
constexpr double CLOSED_H = 5.0;
constexpr double CLOSE_DROP = 0.35;
constexpr double PERSPECTIVE = 0.06;
constexpr double LID_MARGIN = 3.0;
constexpr double GAZE_X_PX = 28.0;
constexpr double MIN_OPEN_PX = 2.0;  // a closed eye stays a visible line on the OLED

constexpr double FADE_IN_S = 0.15;
constexpr double FADE_OUT_S = 0.25;
constexpr double LISTENING_S = 8.0;
constexpr double THINKING_S = 3.0;
constexpr double CONCERNED_S = 1.5;
constexpr double HAPPY_S = 1.5;

inline double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline int16_t px(double v) { return (int16_t)lround(v); }

}  // namespace

const char *mood_name(Mood m) {
  static const char *const names[] = {"none", "listening", "thinking", "concerned", "happy"};
  return (int)m <= (int)Mood::HAPPY ? names[(int)m] : "?";
}

bool Eyes::on_message(uint8_t type, const uint8_t *payload, size_t n, uint32_t now_ms) {
  if (type == proto::SOUND) {
    if (payload == nullptr || n < 1) return false;
    on_sound(payload[0], now_ms);
    return true;
  }
  return link_.on_message(type, payload, n, now_ms);
}

void Eyes::on_sound(uint8_t id, uint32_t now_ms) {
  if (has_clock_) elapsed_ms_ += (uint32_t)(now_ms - last_ms_);
  has_clock_ = true;
  last_ms_ = now_ms;
  const double t = elapsed_ms_ / 1000.0;
  Mood m;
  double hold;
  switch (id) {
    case audio::SOUND_WAKE: m = Mood::LISTENING; hold = LISTENING_S; break;
    case audio::SOUND_DONE: m = Mood::THINKING; hold = THINKING_S; break;
    case audio::SOUND_ERROR: m = Mood::CONCERNED; hold = CONCERNED_S; break;
    case audio::SOUND_HELLO: m = Mood::HAPPY; hold = HAPPY_S; break;
    default: return;
  }
  if (m != mood_ || t > mood_until_) mood_since_ = t;  // the same mood again only extends it
  mood_ = m;
  mood_until_ = t + hold;
}

double Eyes::mood_weight(double t) {
  if (mood_ == Mood::NONE) return 0.0;
  if (t > mood_until_ + FADE_OUT_S) {
    mood_ = Mood::NONE;
    return 0.0;
  }
  double in = face::smoothstep((t - mood_since_) / FADE_IN_S);
  double out = t > mood_until_ ? 1.0 - face::smoothstep((t - mood_until_) / FADE_OUT_S) : 1.0;
  return in < out ? in : out;
}

void Eyes::apply_mood(double w, face::FaceParams &p, double *gx, double *gy) const {
  if (w <= 0.0) return;
  switch (mood_) {
    case Mood::LISTENING:  // wide awake, whatever the face was doing (asleep included)
      p.height *= 1.0 + 0.18 * w;
      p.width *= 1.0 + 0.06 * w;
      p.open += (1.0 - p.open) * w;
      p.lid_top *= 1.0 - w;
      p.lid_bottom *= 1.0 - w;
      p.dy *= 1.0 - w;
      break;
    case Mood::THINKING:  // a glance up and to the side
      *gx += 16.0 * w;
      *gy -= 16.0 * w;
      p.open += (1.0 - p.open) * w;
      p.lid_top = p.lid_top > 0.2 * w ? p.lid_top : 0.2 * w;
      break;
    case Mood::CONCERNED:
      p.open += (1.0 - p.open) * w;
      p.lid_top = p.lid_top > 0.4 * w ? p.lid_top : 0.4 * w;
      p.lid_tilt = p.lid_tilt > 0.4 * w ? p.lid_tilt : 0.4 * w;
      break;
    case Mood::HAPPY:
      p.open += (1.0 - p.open) * w;
      p.lid_bottom = p.lid_bottom > 0.36 * w ? p.lid_bottom : 0.36 * w;
      break;
    case Mood::NONE:
      break;
  }
}

void Eyes::frame(uint32_t now_ms, int center_y, EyesLayout &out) {
  const face::Face &f = link_.advance(now_ms);
  if (has_clock_) elapsed_ms_ += (uint32_t)(now_ms - last_ms_);
  has_clock_ = true;
  last_ms_ = now_ms;
  face::FaceParams p = f.params();
  double gx = f.gaze_x(), gy = f.gaze_y();
  double blink = f.blink();
  const double w = mood_weight(elapsed_ms_ / 1000.0);
  apply_mood(w, p, &gx, &gy);
  if (mood_ == Mood::LISTENING) blink *= 1.0 - w;  // no blink while listening
  layout(p, gx, gy, blink, center_y, out);
}

void Eyes::layout(const face::FaceParams &p, double gaze_x, double gaze_y, double blink, int center_y,
                  EyesLayout &out) {
  const double s = SCALE;
  const double lean = clamp(gaze_x / GAZE_X_PX, -1.0, 1.0);
  const double openness = clamp(p.open * (1.0 - blink), 0.0, 1.0);
  static const double SIDES[2] = {-1.0, 1.0};
  for (int i = 0; i < 2; i++) {
    const double side = SIDES[i];
    EyeShape &e = out.eye[i];
    const double k = 1.0 + side * PERSPECTIVE * lean;
    const double w = p.width * k * s, h = p.height * k * s;
    const double cx = WIDTH / 2.0 + (side * p.spacing / 2 + gaze_x) * s;
    double h_eff = h * openness;
    if (h_eff < CLOSED_H * s) h_eff = CLOSED_H * s;
    if (h_eff < MIN_OPEN_PX) h_eff = MIN_OPEN_PX;
    const double cy = center_y + p.dy * s + gaze_y * GAZE_Y + (h - h_eff) * CLOSE_DROP;
    const double top = cy - h_eff / 2, bottom = cy + h_eff / 2;

    e.x = px(cx - w / 2);
    e.y = px(top);
    e.w = px(w);
    e.h = px(h_eff);
    if (e.h < 1) e.h = 1;
    // U8g2's drawRBox needs w and h >= 2 (r + 1)
    double r = p.radius * k * s;
    double r_max = (e.w < e.h ? e.w : e.h) / 2.0 - 1.0;
    e.r = r_max < 0 ? 0 : px(r < r_max ? r : r_max);

    const bool open = h_eff > CLOSED_H * s + 0.5;
    e.top_lid = p.lid_top > 1e-3 && open;
    if (e.top_lid) {
      const double margin = LID_MARGIN * s;
      const double y_mid = top + p.lid_top * h_eff;
      const double slope = p.lid_tilt * h_eff / 2;
      const double x_in = cx - side * (w / 2 + margin), x_out = cx + side * (w / 2 + margin);
      const double y_cap = top - margin;
      const double y_in = y_mid - slope > y_cap + 1 ? y_mid - slope : y_cap + 1;
      const double y_out = y_mid + slope > y_cap + 1 ? y_mid + slope : y_cap + 1;
      const double xs[4] = {x_in, x_out, x_out, x_in}, ys[4] = {y_cap, y_cap, y_out, y_in};
      for (int c = 0; c < 4; c++) {
        e.lid_x[c] = px(xs[c]);
        e.lid_y[c] = px(ys[c]);
      }
    }
    e.bottom_lid = p.lid_bottom > 1e-3 && open;
    if (e.bottom_lid) {
      const double rx = w * 0.56, ry = h_eff * 0.5;
      e.ell_x = px(cx);
      e.ell_y = px(bottom - p.lid_bottom * h_eff + ry);
      e.ell_rx = px(rx);
      e.ell_ry = px(ry);
    }
  }
}

}  // namespace bench
