// SPDX-License-Identifier: MIT
#include "wifi_reconnect.h"

namespace wifi {

uint32_t Reconnect::backoff_ms(uint32_t n) {
  if (n == 0) n = 1;
  uint32_t ms = BACKOFF_FIRST_MS;
  for (uint32_t i = 1; i < n && ms < BACKOFF_MAX_MS; i++) ms *= 2;
  return ms < BACKOFF_MAX_MS ? ms : BACKOFF_MAX_MS;
}

Reconnect::Step Reconnect::begin_attempt(uint32_t now_ms) {
  attempt_++;
  state_ = State::CONNECTING;
  since_ = now_ms;
  return Step::BEGIN;
}

Reconnect::Step Reconnect::update(bool connected, uint32_t now_ms) {
  if (state_ == State::START) {
    down_since_ = now_ms;
    if (!connected) return begin_attempt(now_ms);
  }
  if (state_ == State::UP) {
    if (connected) return Step::NONE;
    drops_++;
    down_since_ = now_ms;
    attempt_ = 0;
    state_ = State::WAITING;
    since_ = now_ms;
    pause_ms_ = RETRY_AFTER_DROP_MS;
    return Step::DOWN;
  }
  if (connected) {  // START, CONNECTING or WAITING
    state_ = State::UP;
    attempt_ = 0;
    return Step::UP;
  }
  if (state_ == State::CONNECTING) {
    if ((uint32_t)(now_ms - since_) < ATTEMPT_MS) return Step::NONE;
    state_ = State::WAITING;
    since_ = now_ms;
    pause_ms_ = backoff_ms(attempt_);
    return Step::NONE;
  }
  // WAITING
  if ((uint32_t)(now_ms - since_) < pause_ms_) return Step::NONE;
  return begin_attempt(now_ms);
}

const char *step_name(Reconnect::Step s) {
  switch (s) {
    case Reconnect::Step::BEGIN: return "begin";
    case Reconnect::Step::UP: return "up";
    case Reconnect::Step::DOWN: return "down";
    case Reconnect::Step::NONE: break;
  }
  return "none";
}

}  // namespace wifi
