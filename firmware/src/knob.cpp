// SPDX-License-Identifier: MIT
#include "knob.h"

namespace knob {

const char *action_name(Action a) {
  switch (a) {
    case Action::TAP: return "tap";
    case Action::LONG_PRESS: return "long press";
    case Action::NONE: break;
  }
  return "none";
}

Action Knob::update(bool raw_pressed, uint32_t now_ms) {
  if (!started_) {
    started_ = true;
    stable_ = raw_ = raw_pressed;
    raw_since_ = now_ms;
    return Action::NONE;
  }
  if (raw_pressed != raw_) {
    raw_ = raw_pressed;
    raw_since_ = now_ms;
  }
  if (raw_ != stable_ && (uint32_t)(now_ms - raw_since_) >= DEBOUNCE_MS) {
    stable_ = raw_;
    if (stable_) {  // pressed
      armed_ = true;
      long_sent_ = false;
      pressed_at_ = raw_since_;
    } else {  // released
      const bool tap = armed_ && !long_sent_;
      armed_ = false;
      if (tap) return Action::TAP;
    }
  }
  // raw_ too: a knob let go just before the time (its release still debouncing) is a tap
  if (stable_ && raw_ && armed_ && !long_sent_ && (uint32_t)(now_ms - pressed_at_) >= long_press_ms_) {
    long_sent_ = true;
    return Action::LONG_PRESS;
  }
  return Action::NONE;
}

}  // namespace knob
