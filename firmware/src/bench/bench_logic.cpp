// SPDX-License-Identifier: MIT
#include "bench_logic.h"

#include "../audio/earcons.h"

namespace bench {

bool Button::update(bool raw_pressed, uint32_t now_ms) {
  if (!started_) {
    started_ = true;
    stable_ = raw_ = raw_pressed;
    raw_since_ = now_ms;
    return false;
  }
  if (raw_pressed != raw_) {
    raw_ = raw_pressed;
    raw_since_ = now_ms;
    return false;
  }
  if (raw_ == stable_ || (uint32_t)(now_ms - raw_since_) < DEBOUNCE_MS) return false;
  stable_ = raw_;
  return stable_;
}

bool Chime::start(uint8_t id, uint32_t now_ms) {
  Tone t[MAX_TONES];
  size_t n = 0;
  while (n < MAX_TONES && audio::Earcon::tone(id, n, &t[n].f0, &t[n].f1, &t[n].ms)) n++;
  if (n == 0) return false;
  for (size_t i = 0; i < n; i++) tones_[i] = t[i];
  count_ = n;
  start_ms_ = now_ms;
  return true;
}

void Chime::click(uint32_t now_ms) {
  if (active()) return;
  tones_[0] = Tone{CLICK_HZ, CLICK_HZ, CLICK_MS};
  count_ = 1;
  start_ms_ = now_ms;
}

uint16_t Chime::freq(uint32_t now_ms) {
  uint32_t u = now_ms - start_ms_;
  for (size_t i = 0; i < count_; i++) {
    const Tone &t = tones_[i];
    if (u < t.ms) {
      if (t.f0 == 0) return 0;
      int32_t f = t.f0 + (int32_t)(t.f1 - t.f0) * (int32_t)u / (int32_t)t.ms;
      if (t.f1 != t.f0) f = (f + SWEEP_STEP_HZ / 2) / SWEEP_STEP_HZ * SWEEP_STEP_HZ;
      return (uint16_t)f;
    }
    u -= t.ms;
  }
  count_ = 0;
  return 0;
}

bool status_led_on(LinkPhase phase, uint32_t now_ms) {
  switch (phase) {
    case LinkPhase::WIFI: return (now_ms % 1000) < 500;
    case LinkPhase::SEARCHING: return (now_ms % 250) < 125;
    case LinkPhase::LINKED: return true;
  }
  return false;
}

Rgb8 status_color(LinkPhase phase, uint32_t now_ms) {
  static constexpr Rgb8 OFF = {0, 0, 0};
  switch (phase) {
    case LinkPhase::WIFI: return status_led_on(phase, now_ms) ? Rgb8{0, 0, 24} : OFF;
    case LinkPhase::SEARCHING: return status_led_on(phase, now_ms) ? Rgb8{24, 10, 0} : OFF;
    case LinkPhase::LINKED: return Rgb8{0, 12, 0};
  }
  return OFF;
}

}  // namespace bench
