// SPDX-License-Identifier: MIT
//
// The robot's knob: the orange cap on the head pressing a 6 x 6 mm tact switch on D7 (GPIO44) to
// ground, with the pin's pull-up. One button, two actions (protocol v1 BUTTON, docs/protocol.md):
//
//   tap         released before BUTTON_LONG_PRESS_MS: BUTTON Talk, action 1, sent on release
//   long press  held BUTTON_LONG_PRESS_MS (1.5 s): BUTTON Talk, action 2, sent once while held;
//               the release then sends nothing
//
// What they do is the host's business (tap = Talk now, long press = mute or unmute).
//
// A change of the pin counts once it has kept its new level for DEBOUNCE_MS; the press is timed
// from the first sample at the new level, so the bounce does not shorten or lengthen it. The first
// sample sets the state without an event, so a knob held at boot (or stuck) sends nothing until it
// has been released and pressed again.
//
// Portable C++ (no Arduino), unit-tested with `pio test -e native`. Time is supplied by the caller
// in milliseconds and may wrap.
#pragma once

#include <stdint.h>

#include "protocol.h"

namespace knob {

// The values are protocol v1's ButtonAction, so an action goes on the wire as it is.
enum class Action : uint8_t {
  NONE = 0,
  TAP = proto::BUTTON_PRESS,
  LONG_PRESS = proto::BUTTON_LONG_PRESS,
};

const char *action_name(Action a);

class Knob {
 public:
  static constexpr uint32_t DEBOUNCE_MS = 30;

  explicit Knob(uint32_t long_press_ms = proto::BUTTON_LONG_PRESS_MS) : long_press_ms_(long_press_ms) {}

  // One sample of the pin (`raw_pressed`: read low). Call it every loop; returns the action that
  // completed with this sample, if any.
  Action update(bool raw_pressed, uint32_t now_ms);

  // The debounced state.
  bool pressed() const { return stable_; }

 private:
  uint32_t long_press_ms_;
  bool started_ = false;
  bool stable_ = false;
  bool raw_ = false;
  uint32_t raw_since_ = 0;
  bool armed_ = false;      // a press that began after boot: it may send an action
  bool long_sent_ = false;  // the long press went out: the release sends nothing
  uint32_t pressed_at_ = 0;
};

}  // namespace knob
