// SPDX-License-Identifier: MIT
//
// The face's side of the host link: decodes FACE_STATE and FACE_EVENT (docs/protocol.md) into
// face inputs, and runs the face on its own when the host goes quiet.
//
// Autonomy: without a FACE_STATE for HOST_TIMEOUT_MS (5 s), or before the first one, the face is
// told what the board itself sees (set_local, from its own radar) or, without that, that nobody is present. It then behaves as when someone leaves: it looks around, gets
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
// Presence -> FACE_STATE payload (proto::FACE_STATE_SIZE bytes; the heart rate's flag is set when it
// is not 0). Returns its size. For a board that feeds its own face (set_local).
size_t encode_state(const Presence &s, uint8_t *payload);
// FACE_EVENT payload -> Event. False for an empty payload or an unknown code.
bool decode_event(const uint8_t *payload, size_t n, Event &out);

class FaceLink {
 public:
  static constexpr uint32_t HOST_TIMEOUT_MS = 5000;

  explicit FaceLink(uint32_t seed = 0) : face_(seed) {}

  // A host -> robot message (type from protocol.h, payload after the 16-byte header).
  // Returns true if it was a face message and was understood.
  bool on_message(uint8_t type, const uint8_t *payload, size_t n, uint32_t now_ms);

  // The board's own presence (its radar), used while the host sends no FACE_STATE, for
  // LOCAL_TIMEOUT_MS after the last call. The host's FACE_STATE always wins.
  void set_local(const Presence &s, uint32_t now_ms);
  static constexpr uint32_t LOCAL_TIMEOUT_MS = 1000;

  // Advance the face to now_ms (a millisecond clock that may wrap) and draw into `fb`
  // (SCREEN_W x SCREEN_H RGB565). Returns the rectangle that changed.
  Rect frame(uint32_t now_ms, uint16_t *fb);

  // Advance the face to now_ms without drawing it, for renderers of their own (the bench board's
  // OLED, bench/eyes.h): they read the face's parameters, gaze and blink.
  const Face &advance(uint32_t now_ms);

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
  Presence local_;
  bool has_local_ = false;
  uint32_t last_local_ms_ = 0;
  bool has_clock_ = false;
  uint32_t last_ms_ = 0;
  uint64_t elapsed_ms_ = 0;  // wrap-free clock for the face
};

}  // namespace face
