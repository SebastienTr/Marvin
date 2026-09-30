// SPDX-License-Identifier: MIT
#include "room_logic.h"

#include <math.h>

namespace room {

// ---- PIR --------------------------------------------------------------------------------------

bool Pir::update(bool raw_high, uint32_t now_ms) {
  if (!started_) {
    started_ = true;
    start_ms_ = now_ms;
    stable_ = raw_ = raw_high;
    raw_since_ = now_ms;
  } else if (raw_high != raw_) {
    raw_ = raw_high;
    raw_since_ = now_ms;
  }
  const bool level_changed = raw_ != stable_ && now_ms - raw_since_ >= DEBOUNCE_MS;
  if (level_changed) stable_ = raw_;

  if (!warm_) {
    if (now_ms - start_ms_ < warmup_ms_) return false;  // settling: nothing is reported
    warm_ = true;
    if (stable_) {
      seen_motion_ = true;
      last_motion_ms_ = now_ms;
    }
    return true;
  }
  if (stable_) {
    seen_motion_ = true;
    last_motion_ms_ = now_ms;  // while it lasts, the motion is now
  }
  return level_changed;
}

uint16_t Pir::motion_age_s(uint32_t now_ms) const {
  if (!warm_ || !seen_motion_) return proto::ENV_NO_MOTION;
  if (stable_) return 0;
  const uint32_t s = (now_ms - last_motion_ms_) / 1000;
  return s < 0xFFFE ? (uint16_t)s : 0xFFFE;
}

uint32_t Pir::warmup_left_ms(uint32_t now_ms) const {
  if (warm_) return 0;
  if (!started_) return warmup_ms_;
  const uint32_t gone = now_ms - start_ms_;
  return gone < warmup_ms_ ? warmup_ms_ - gone : 0;
}

// ---- DHT11 ------------------------------------------------------------------------------------

void dht_bits(const uint8_t low_us[40], const uint8_t high_us[40], uint8_t out[5]) {
  for (int i = 0; i < 5; i++) out[i] = 0;
  for (int i = 0; i < 40; i++)
    if (high_us[i] > low_us[i]) out[i / 8] |= (uint8_t)(0x80 >> (i % 8));
}

DhtStatus dht11_decode(const uint8_t b[5], int16_t *temperature_dc, uint16_t *humidity_dpct) {
  if ((uint8_t)(b[0] + b[1] + b[2] + b[3]) != b[4]) return DhtStatus::CHECKSUM;
  // DHT11: humidity 20..90 %RH, 0..50 degC specified; anything outside a wide margin is a bad frame
  const uint16_t h = (uint16_t)(b[0] * 10 + (b[1] < 10 ? b[1] : 9));
  int16_t t = (int16_t)(b[2] * 10 + ((b[3] & 0x7F) < 10 ? (b[3] & 0x7F) : 9));
  if (b[3] & 0x80) t = (int16_t)-t;
  if (h == 0 || h > 1000 || t < -400 || t > 800) return DhtStatus::OUT_OF_RANGE;
  *temperature_dc = t;
  *humidity_dpct = h;
  return DhtStatus::OK;
}

const char *dht_status_name(DhtStatus s) {
  switch (s) {
    case DhtStatus::OK: return "ok";
    case DhtStatus::NO_RESPONSE: return "no answer";
    case DhtStatus::CHECKSUM: return "bad checksum";
    case DhtStatus::OUT_OF_RANGE: return "impossible values";
  }
  return "?";
}

void Climate::result(DhtStatus status, int16_t temperature_dc, uint16_t humidity_dpct, uint32_t now_ms) {
  reads_++;
  last_ = status;
  if (status == DhtStatus::OK) {
    ok_once_ = true;
    ok_ms_ = now_ms;
    t_dc_ = temperature_dc;
    h_dpct_ = humidity_dpct;
    failed_in_a_row_ = 0;
    next_ms_ = now_ms + PERIOD_MS;
    return;
  }
  failures_++;
  if (failed_in_a_row_ < RETRIES) {
    failed_in_a_row_++;
    next_ms_ = now_ms + RETRY_MS;
  } else {
    failed_in_a_row_ = 0;
    next_ms_ = now_ms + PERIOD_MS;
  }
}

// ---- Light ------------------------------------------------------------------------------------

void Light::add(uint16_t raw, uint32_t now_ms) {
  if (raw > 4095) raw = 4095;
  if (!started_) {
    started_ = true;
    avg_ = raw;
  } else {
    const float dt = (float)(now_ms - last_ms_);
    const float a = 1.0f - expf(-dt / (float)TAU_MS);
    avg_ += a * ((float)raw - avg_);
  }
  last_ms_ = now_ms;
}

uint16_t Light::raw() const { return started_ ? (uint16_t)lroundf(avg_) : 0; }

uint16_t Light::level() const {
  if (!started_) return 0;
  const float f = avg_ / 4095.0f;
  const long l = lroundf((bright_high_ ? f : 1.0f - f) * 1000.0f);
  return (uint16_t)(l < 0 ? 0 : l > 1000 ? 1000 : l);
}

// ---- ENVIRONMENT ------------------------------------------------------------------------------

proto::Environment environment(const Readings &r, uint32_t now_ms) {
  proto::Environment e;
  if (r.pir) {
    e.sensors |= proto::ENV_PIR;
    if (r.pir->warming_up()) e.flags |= proto::ENV_WARMING_UP;
    if (r.pir->motion()) e.flags |= proto::ENV_MOTION;
    e.motion_age_s = r.pir->motion_age_s(now_ms);
  }
  if (r.climate) {
    e.sensors |= proto::ENV_CLIMATE;
    if (r.climate->valid(now_ms)) {
      e.flags |= proto::ENV_TEMPERATURE_VALID | proto::ENV_HUMIDITY_VALID;
      e.temperature_dc = r.climate->temperature_dc();
      e.humidity_dpct = r.climate->humidity_dpct();
    }
  }
  if (r.light) {
    e.sensors |= proto::ENV_LIGHT;
    if (r.light->valid()) {
      e.flags |= proto::ENV_LIGHT_VALID;
      e.light = r.light->level();
      e.light_raw = r.light->raw();
    }
  }
  return e;
}

}  // namespace room
