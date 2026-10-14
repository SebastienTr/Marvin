// SPDX-License-Identifier: MIT
//
// When to (re)join the Wi-Fi: the robot owns its reconnection instead of trusting the driver's
// auto-reconnect (which gives up after some failures, e.g. a router rebooting for minutes).
//
//   boot, not connected       BEGIN at once (attempt 1)
//   an attempt gets           ATTEMPT_MS to connect; then a pause before the next BEGIN:
//                             1 s, 2 s, 4 s, 8 s, 16 s, then 30 s between attempts, forever
//   connected                 UP (once); the attempts count starts again from 1 after a loss
//   connection lost           DOWN (once), then BEGIN after RETRY_AFTER_DROP_MS
//
// The caller does the Wi-Fi calls: on BEGIN, disconnect and begin(ssid, password). A connection
// that comes up on its own (during a pause) counts as UP all the same.
//
// Portable C++ (no Arduino), unit-tested with `pio test -e native`. Time is supplied by the caller
// in milliseconds and may wrap.
#pragma once

#include <stdint.h>

namespace wifi {

class Reconnect {
 public:
  static constexpr uint32_t ATTEMPT_MS = 10000;
  static constexpr uint32_t BACKOFF_FIRST_MS = 1000;
  static constexpr uint32_t BACKOFF_MAX_MS = 30000;
  static constexpr uint32_t RETRY_AFTER_DROP_MS = 500;

  enum class Step : uint8_t {
    NONE,   // nothing to do
    BEGIN,  // start an attempt now: disconnect, then begin(ssid, password)
    UP,     // just connected
    DOWN,   // just lost the connection
  };

  // Call it every loop with the driver's state (WiFi.status() == WL_CONNECTED).
  Step update(bool connected, uint32_t now_ms);

  bool up() const { return state_ == State::UP; }
  // The attempt in progress or the last one (1 for the first), 0 while connected.
  uint32_t attempt() const { return attempt_; }
  // Connections lost since boot.
  uint32_t drops() const { return drops_; }
  // When the connection was last lost or the robot started (for "back after N s"), ms.
  uint32_t down_since() const { return down_since_; }

  // The pause after failed attempt n (1-based).
  static uint32_t backoff_ms(uint32_t n);

 private:
  enum class State : uint8_t { START, CONNECTING, WAITING, UP };

  Step begin_attempt(uint32_t now_ms);

  State state_ = State::START;
  uint32_t attempt_ = 0;
  uint32_t drops_ = 0;
  uint32_t since_ = 0;      // CONNECTING: when the attempt began; WAITING: when the pause began
  uint32_t pause_ms_ = 0;   // WAITING: its length
  uint32_t down_since_ = 0;
};

const char *step_name(Reconnect::Step s);

}  // namespace wifi
