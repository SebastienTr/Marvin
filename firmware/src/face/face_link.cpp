// SPDX-License-Identifier: MIT
#include "face_link.h"

#include "../protocol.h"

namespace face {

namespace {

inline double i16(const uint8_t *p) { return (double)(int16_t)proto::get16(p); }

}  // namespace

bool decode_state(const uint8_t *p, size_t n, Presence &out) {
  if (p == nullptr || n < proto::FACE_STATE_SIZE) return false;
  const uint8_t flags = p[0];
  Presence s;
  s.present = flags & proto::FACE_PRESENT;
  s.seated = flags & proto::FACE_SEATED;
  s.has_head = flags & proto::FACE_HEAD;
  s.has_position = flags & proto::FACE_POSITION;
  s.has_distance = flags & proto::FACE_DISTANCE;
  for (int k = 0; k < 3; k++) {
    s.head[k] = s.has_head ? i16(p + 1 + 2 * k) : 0.0;
    s.position[k] = s.has_position ? i16(p + 7 + 2 * k) : 0.0;
  }
  s.distance_m = s.has_distance ? proto::get16(p + 13) / 1000.0 : 0.0;
  s.heart_rate = (flags & proto::FACE_HEART_RATE) ? proto::get16(p + 15) / 100.0 : 0.0;
  out = s;
  return true;
}

bool decode_event(const uint8_t *p, size_t n, Event &out) {
  if (p == nullptr || n < 1 || p[0] == 0 || p[0] > EVENT_MAX) return false;
  out = (Event)p[0];
  return true;
}

bool FaceLink::on_message(uint8_t type, const uint8_t *payload, size_t n, uint32_t now_ms) {
  if (type == proto::FACE_STATE) {
    if (!decode_state(payload, n, state_)) return false;
    has_state_ = true;
    last_state_ms_ = now_ms;
    return true;
  }
  if (type == proto::FACE_EVENT) {
    Event ev;
    if (!decode_event(payload, n, ev)) return false;
    face_.on_event(ev);
    return true;
  }
  return false;
}

Rect FaceLink::frame(uint32_t now_ms, uint16_t *fb) {
  if (has_clock_) elapsed_ms_ += (uint32_t)(now_ms - last_ms_);
  has_clock_ = true;
  last_ms_ = now_ms;
  static const Presence nobody;
  const Presence &s = host_alive(now_ms) ? state_ : nobody;
  return face_.update_and_render(s, elapsed_ms_ / 1000.0, fb);
}

}  // namespace face
