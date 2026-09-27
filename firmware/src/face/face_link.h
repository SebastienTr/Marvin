// SPDX-License-Identifier: MIT
//
// The face's side of the host link: decodes FACE_STATE and FACE_EVENT (docs/protocol.md) into
// face inputs, and runs the face on its own when the host goes quiet.
//
// Autonomy: without a FACE_STATE for HOST_TIMEOUT_MS (5 s), or before the first one, the face is
// told that nobody is present. It then behaves as when someone leaves: it looks around, gets
// sleepy after 4 s and falls asleep after 20 s, breathing slowly. It never freezes.
//
// Portable C++ (no Arduino), unit-tested natively.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "face.h"

namespace face {

// FACE_STATE payload -> Presence. False (and `out` untouched) if the payload is too short.
bool decode_state(const uint8_t *payload, size_t n, Presence &out);
// FACE_EVENT payload -> Event. False for an empty payload or an unknown code.
bool decode_event(const uint8_t *payload, size_t n, Event &out);

class FaceLink {
 public:
  static constexpr uint32_t HOST_TIMEOUT_MS = 5000;

  explicit FaceLink(uint32_t seed = 0) : face_(seed) {}

  // A host -> robot message (type from protocol.h, payload after the 16-byte header).
  // Returns true if it was a face message and was understood.
  bool on_message(uint8_t type, const uint8_t *payload, size_t n, uint32_t now_ms);

  // Advance the face to now_ms (a millisecond clock that may wrap) and draw into `fb`
  // (SCREEN_W x SCREEN_H RGB565). Returns the rectangle that changed.
  Rect frame(uint32_t now_ms, uint16_t *fb);

  // True while FACE_STATE messages arrive (the last one is less than HOST_TIMEOUT_MS old).
  bool host_alive(uint32_t now_ms) const {
    return has_state_ && (uint32_t)(now_ms - last_state_ms_) <= HOST_TIMEOUT_MS;
  }
  const Presence &state() const { return state_; }
  const Face &face() const { return face_; }

 private:
  Face face_;
  Presence state_;
  bool has_state_ = false;
  uint32_t last_state_ms_ = 0;
  bool has_clock_ = false;
  uint32_t last_ms_ = 0;
  uint64_t elapsed_ms_ = 0;  // wrap-free clock for the face
};

}  // namespace face
