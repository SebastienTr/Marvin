// SPDX-License-Identifier: MIT
#include "mood.h"

#include <math.h>

#include "../audio/earcons.h"

namespace face {

namespace {

constexpr double FOREVER = 1e30;  // speaking holds until the stream ends
constexpr double PI = 3.14159265358979323846;

bool timed(Mood m) { return m != Mood::NONE && m != Mood::SPEAKING; }

}  // namespace

const char *mood_name(Mood m) {
  static const char *const names[] = {"none", "listening", "thinking", "speaking", "concerned", "happy"};
  return (int)m <= (int)Mood::HAPPY ? names[(int)m] : "?";
}

void Moods::set(Mood m, double t, double hold) {
  if (m != mood_ || t > until_) since_ = t;  // the same mood again only extends it
  mood_ = m;
  until_ = t + hold;
}

void Moods::on_sound(uint8_t id, double t) {
  settle(t);
  switch (id) {
    case audio::SOUND_WAKE: set(Mood::LISTENING, t, LISTENING_S); break;
    case audio::SOUND_DONE:
      if (!speaking_) set(Mood::THINKING, t, THINKING_S);  // the answer already plays: stay speaking
      break;
    case audio::SOUND_ERROR: set(Mood::CONCERNED, t, CONCERNED_S); break;
    case audio::SOUND_HELLO: set(Mood::HAPPY, t, HAPPY_S); break;
    default: break;
  }
}

void Moods::set_speaking(bool on, double t) {
  if (on == speaking_) return;
  settle(t);
  speaking_ = on;
  if (on) {
    // the answer starts: it ends listening and thinking; a happy or concerned look finishes first
    const bool showing = (mood_ == Mood::CONCERNED || mood_ == Mood::HAPPY) && t <= until_;
    if (!showing) set(Mood::SPEAKING, t, FOREVER);
  } else if (mood_ == Mood::SPEAKING) {
    until_ = t;  // fades out
  }
}

void Moods::clear(double t) {
  speaking_ = false;
  if (mood_ != Mood::NONE && until_ > t) until_ = t;
}

void Moods::settle(double t) {
  if (speaking_ && timed(mood_) && t > until_) set(Mood::SPEAKING, t, FOREVER);
}

double Moods::weight(double t) {
  settle(t);
  if (mood_ == Mood::NONE) return 0.0;
  if (t > until_ + FADE_OUT_S) {
    mood_ = Mood::NONE;
    return 0.0;
  }
  const double in = smoothstep((t - since_) / FADE_IN_S);
  const double out = t > until_ ? 1.0 - smoothstep((t - until_) / FADE_OUT_S) : 1.0;
  return in < out ? in : out;
}

Mood Moods::mood(double t) {
  weight(t);
  return mood_;
}

double Moods::apply(double t, FaceParams &p, double *gx, double *gy, double *blink) {
  const double w = weight(t);
  if (w <= 0.0) return 0.0;
  switch (mood_) {
    case Mood::LISTENING:  // wide awake, whatever the face was doing (asleep included)
      p.height *= 1.0 + 0.18 * w;
      p.width *= 1.0 + 0.06 * w;
      p.open += (1.0 - p.open) * w;
      p.lid_top *= 1.0 - w;
      p.lid_bottom *= 1.0 - w;
      p.dy *= 1.0 - w;
      *blink *= 1.0 - w;  // no blink while listening
      break;
    case Mood::THINKING:  // a glance up and to the side
      *gx += 16.0 * w;
      *gy -= 16.0 * w;
      p.open += (1.0 - p.open) * w;
      p.lid_top = p.lid_top > 0.2 * w ? p.lid_top : 0.2 * w;
      break;
    case Mood::SPEAKING: {  // awake, the eyes bobbing gently with the voice
      const double bob = sin(2.0 * PI * SPEAKING_HZ * (t - since_));
      p.open += (1.0 - p.open) * w;
      p.lid_top *= 1.0 - 0.5 * w;
      p.height *= 1.0 + 0.04 * w * bob;
      p.dy -= 3.0 * w * bob;
      break;
    }
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
  return w;
}

}  // namespace face
