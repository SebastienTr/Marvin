// SPDX-License-Identifier: MIT
//
// The bench board's eyes on a 128 x 64 monochrome OLED: the robot's face (face/face.h, the same
// behaviour, blinks and expressions, fed by FACE_STATE and FACE_EVENT through face/face_link.h),
// laid out at half scale in white on black, plus a few moods taken from the voice's sounds:
//
//   SOUND wake   listening: wide open eyes, until the sound done or error (at most 8 s)
//   SOUND done   thinking: a glance up and to the side, lids a little lower (3 s)
//   SOUND error  concerned: tilted lids (1.5 s)
//   SOUND hello  happy: lower lids raised (1.5 s)
//
// Other sound ids and unknown messages are ignored. With no FACE_STATE (no host, or before the
// first one), the face follows the board's own radar when it has one (on_local_state, the "looks"
// bench's head); otherwise nobody is there: it looks around, gets sleepy and falls asleep, as on the robot.
//
// Portable C++ (no Arduino), unit-tested natively; bench/bench_io.cpp draws the layout with U8g2.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "../face/face_link.h"

namespace bench {

// One eye, in OLED pixels (x right, y down), drawn in this order: the eye in white, then the
// lids in black over it.
struct EyeShape {
  int16_t x, y, w, h, r;        // the eye: a filled rounded box (top-left corner, size, corner radius)
  bool top_lid;                 // a black quad over the top of the eye (corners in order)
  int16_t lid_x[4], lid_y[4];
  bool bottom_lid;              // a black ellipse over the bottom of the eye
  int16_t ell_x, ell_y, ell_rx, ell_ry;
};

struct EyesLayout {
  EyeShape eye[2];  // left of the screen, right of the screen
};

enum class Mood : uint8_t { NONE, LISTENING, THINKING, CONCERNED, HAPPY };

const char *mood_name(Mood m);

class Eyes {
 public:
  static constexpr int WIDTH = 128;
  static constexpr int HEIGHT = 64;
  static constexpr double SCALE = 0.5;    // OLED pixels per face pixel (face/face.h: 240 x 280)
  static constexpr double GAZE_Y = 0.35;  // vertical gaze, OLED pixels per face pixel: keeps the eyes on screen

  explicit Eyes(uint32_t seed = 0) : link_(seed) {}

  // A host -> robot message: FACE_STATE and FACE_EVENT go to the face, SOUND sets a mood.
  // Returns true if it was understood.
  bool on_message(uint8_t type, const uint8_t *payload, size_t n, uint32_t now_ms);
  // The board's own presence as a FACE_STATE payload (face::FaceLink::set_local). False if too short.
  bool on_local_state(const uint8_t *payload, size_t n, uint32_t now_ms);
  // A sound played (by the host's SOUND, or locally): sets the matching mood, if any.
  void on_sound(uint8_t id, uint32_t now_ms);

  // Advance the face to now_ms and lay the eyes out around row `center_y`.
  void frame(uint32_t now_ms, int center_y, EyesLayout &out);

  // The mood at the last frame (NONE once it has faded out).
  Mood mood() const { return mood_; }
  const face::FaceLink &link() const { return link_; }

  // The layout of explicit face parameters (face.draw at OLED scale): for tests and previews.
  static void layout(const face::FaceParams &p, double gaze_x, double gaze_y, double blink, int center_y,
                     EyesLayout &out);

 private:
  // How strongly the mood shows at time t (0..1); clears it once faded out.
  double mood_weight(double t);
  void apply_mood(double w, face::FaceParams &p, double *gx, double *gy) const;

  face::FaceLink link_;
  Mood mood_ = Mood::NONE;
  double mood_since_ = 0;
  double mood_until_ = 0;
  bool has_clock_ = false;
  uint32_t last_ms_ = 0;
  uint64_t elapsed_ms_ = 0;
};

}  // namespace bench
