// SPDX-License-Identifier: MIT
#include "face_link.h"

#include <math.h>

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

size_t encode_state(const Presence &s, uint8_t *p) {
  auto mm = [](double v) {
    long r = lround(v);
    return (uint16_t)(int16_t)(r < -32767 ? -32767 : (r > 32767 ? 32767 : r));
  };
  uint8_t flags = 0;
  if (s.present) flags |= proto::FACE_PRESENT;
  if (s.seated) flags |= proto::FACE_SEATED;
  if (s.has_head) flags |= proto::FACE_HEAD;
  if (s.has_position) flags |= proto::FACE_POSITION;
  if (s.has_distance) flags |= proto::FACE_DISTANCE;
  if (s.heart_rate > 0) flags |= proto::FACE_HEART_RATE;
  p[0] = flags;
  for (int k = 0; k < 3; k++) {
    proto::put16(p + 1 + 2 * k, s.has_head ? mm(s.head[k]) : 0);
    proto::put16(p + 7 + 2 * k, s.has_position ? mm(s.position[k]) : 0);
  }
  proto::put16(p + 13, s.has_distance ? (uint16_t)lround(s.distance_m * 1000.0) : 0);
  proto::put16(p + 15, s.heart_rate > 0 ? (uint16_t)lround(s.heart_rate * 100.0) : 0);
  return proto::FACE_STATE_SIZE;
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
  if (type == proto::SOUND) {
    if (payload == nullptr || n < 1) return false;
    on_sound(payload[0], now_ms);
    return true;
  }
  return false;
}

void FaceLink::host_lost() {
  has_state_ = false;
  moods_.clear(elapsed_ms_ / 1000.0);  // no "speaking" or "listening" left over from the host
}

void FaceLink::on_sound(uint8_t id, uint32_t now_ms) { moods_.on_sound(id, seconds(now_ms)); }

void FaceLink::set_speaking(bool on, uint32_t now_ms) { moods_.set_speaking(on, seconds(now_ms)); }

void FaceLink::set_local(const Presence &s, uint32_t now_ms) {
  local_ = s;
  has_local_ = true;
  last_local_ms_ = now_ms;
}

Rect FaceLink::frame(uint32_t now_ms, uint16_t *fb) {
  advance(now_ms);
  FaceParams p = face_.params();
  double gx = face_.gaze_x(), gy = face_.gaze_y(), blink = face_.blink();
  if (moods_.apply(elapsed_ms_ / 1000.0, p, &gx, &gy, &blink) > 0.0)
    draw(p, gx, gy, blink, face_.accent(), face_.raster());  // the same frame, with the mood on top
  return face_.raster().render(fb);
}

const Face &FaceLink::advance(uint32_t now_ms) {
  if (has_clock_) elapsed_ms_ += (uint32_t)(now_ms - last_ms_);
  has_clock_ = true;
  last_ms_ = now_ms;
  static const Presence nobody;
  const bool local = has_local_ && (uint32_t)(now_ms - last_local_ms_) <= LOCAL_TIMEOUT_MS;
  const Presence &s = host_alive(now_ms) ? state_ : (local ? local_ : nobody);
  face_.update(s, elapsed_ms_ / 1000.0);
  return face_;
}

}  // namespace face
