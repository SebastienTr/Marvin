// SPDX-License-Identifier: MIT
//
// The voice's moods on the robot's face, from messages that already exist (no new protocol):
//
//   SOUND wake            listening: wide open eyes, no blinks, until SOUND done or error (at most 8 s)
//   SOUND done            thinking: a glance up and to the side (3 s, or until the answer starts)
//   speaker stream plays  speaking: open eyes that bob gently, while AUDIO_OUT is being played
//   SOUND error           concerned: tilted lids (1.5 s)
//   SOUND hello           happy: lower lids raised (1.5 s)
//
// The host plays wake when it starts listening (the Talk button, the knob), done when it stops,
// then streams the answer: listening -> thinking -> speaking -> back to the face's own expression.
// A sound during the answer shows over it, then the face goes back to speaking. Moods fade in and
// out (0.15 s, 0.25 s) on top of whatever the face is doing (asleep included), and change nothing
// in face.h, which stays byte-exact with the Python reference.
//
// The same moods and numbers as the bench board's OLED eyes (bench/eyes.h), plus speaking.
// Portable C++ (no Arduino), unit-tested natively. Time in seconds, monotonic.
#pragma once

#include <stdint.h>

#include "face.h"

namespace face {

enum class Mood : uint8_t { NONE, LISTENING, THINKING, SPEAKING, CONCERNED, HAPPY };

const char *mood_name(Mood m);

class Moods {
 public:
  static constexpr double FADE_IN_S = 0.15;
  static constexpr double FADE_OUT_S = 0.25;
  static constexpr double LISTENING_S = 8.0;
  static constexpr double THINKING_S = 3.0;
  static constexpr double CONCERNED_S = 1.5;
  static constexpr double HAPPY_S = 1.5;
  static constexpr double SPEAKING_HZ = 2.5;  // the eyes' bob while speaking

  // A sound played (the host's SOUND, or one the robot plays itself). Unknown ids are ignored.
  void on_sound(uint8_t id, double t);
  // The speaker plays the host's stream (true) or has nothing left to play (false).
  void set_speaking(bool on, double t);
  // The host is gone: no mood survives it (fades out from t).
  void clear(double t);

  // The mood showing at t (NONE once faded out), and how strongly (0..1).
  Mood mood(double t);
  double weight(double t);

  // Puts the mood at t on top of the face's parameters, gaze (pixels) and blink (0..1).
  // Returns its weight: 0 means nothing changed.
  double apply(double t, FaceParams &p, double *gx, double *gy, double *blink);

 private:
  void set(Mood m, double t, double hold);
  void settle(double t);  // a timed mood is over: back to speaking, if the answer is still playing

  Mood mood_ = Mood::NONE;
  double since_ = 0;
  double until_ = 0;  // the mood holds until then, then fades out
  bool speaking_ = false;
};

}  // namespace face
