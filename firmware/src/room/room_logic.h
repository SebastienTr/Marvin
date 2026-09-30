// SPDX-License-Identifier: MIT
//
// The room sensors' logic, without the hardware: the PIR's warm-up and debounced motion, the DHT11's
// frame decoding and read schedule, the light sensor's smoothing, and the ENVIRONMENT payload they
// make together. Used by room/room_io.h on the board.
//
// Portable C++ (no Arduino), unit-tested with `pio test -e native`. Time is supplied by the caller
// in milliseconds and may wrap.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "../protocol.h"

namespace room {

// An HC-SR501 PIR: its output is high while it sees motion (at least its hold time, about 3 s at the
// minimum setting). For about a minute after power-on it settles and may fire on its own: nothing is
// reported until WARMUP has passed since the first sample. A level counts once it has held for
// DEBOUNCE_MS, so a short spike (Wi-Fi bursts coupling into a long wire) is not motion.
class Pir {
 public:
  static constexpr uint32_t DEBOUNCE_MS = 100;
  static constexpr uint32_t DEFAULT_WARMUP_MS = 60000;

  explicit Pir(uint32_t warmup_ms = DEFAULT_WARMUP_MS) : warmup_ms_(warmup_ms) {}

  // One sample of the output. True when what it reports changed: motion started or ended, or the
  // warm-up is over (motion() then says whether it sees motion right away).
  bool update(bool raw_high, uint32_t now_ms);

  bool warming_up() const { return !warm_; }
  bool motion() const { return warm_ && stable_; }
  // Seconds since the motion last ended: 0 while there is motion, proto::ENV_NO_MOTION when there has
  // been none since the warm-up (or while warming up). Saturates at 0xFFFE.
  uint16_t motion_age_s(uint32_t now_ms) const;
  // Warm-up milliseconds left, 0 once warm.
  uint32_t warmup_left_ms(uint32_t now_ms) const;

 private:
  uint32_t warmup_ms_;
  bool started_ = false;
  bool warm_ = false;
  uint32_t start_ms_ = 0;
  bool stable_ = false;
  bool raw_ = false;
  uint32_t raw_since_ = 0;
  bool seen_motion_ = false;
  uint32_t last_motion_ms_ = 0;
};

// A DHT11 frame: 5 bytes, humidity (integer, tenths), temperature (integer, tenths with bit 7 as the
// sign on recent parts), checksum (the low byte of the sum of the first four).
enum class DhtStatus : uint8_t {
  OK,
  NO_RESPONSE,   // the sensor did not answer (unplugged, no power) or the line stuck
  CHECKSUM,      // a bit was misread
  OUT_OF_RANGE,  // a frame with a good checksum but impossible values (all zeros: a line held low)
};

// Bits from the 40 pulses the sensor sends after its response: each bit is a ~50 us low then a high
// of ~26 us (0) or ~70 us (1). A bit is 1 when its high lasted longer than its low, so it does not
// depend on the exact timings or on the clock the durations were measured with.
void dht_bits(const uint8_t low_us[40], const uint8_t high_us[40], uint8_t out[5]);

// Checks and decodes a frame into 0.1 degC and 0.1 %RH.
DhtStatus dht11_decode(const uint8_t b[5], int16_t *temperature_dc, uint16_t *humidity_dpct);

const char *dht_status_name(DhtStatus s);

// When to read the DHT11 and whether its last reading still counts. It needs at least 1 s between
// reads; a failed read is tried again after RETRY_MS, twice at most, then at the next period. A
// reading counts for STALE_MS: an unplugged sensor is reported invalid within about 12 s. The first
// read is at FIRST_MS (milliseconds since boot): the sensor wants a second after power-on.
class Climate {
 public:
  static constexpr uint32_t FIRST_MS = 1500;
  static constexpr uint32_t PERIOD_MS = 5000;
  static constexpr uint32_t RETRY_MS = 1200;
  static constexpr uint8_t RETRIES = 2;
  static constexpr uint32_t STALE_MS = 12000;

  bool due(uint32_t now_ms) const { return (int32_t)(now_ms - next_ms_) >= 0; }
  void result(DhtStatus status, int16_t temperature_dc, uint16_t humidity_dpct, uint32_t now_ms);

  bool valid(uint32_t now_ms) const { return ok_once_ && now_ms - ok_ms_ < STALE_MS; }
  int16_t temperature_dc() const { return t_dc_; }
  uint16_t humidity_dpct() const { return h_dpct_; }
  uint32_t reads() const { return reads_; }
  uint32_t failures() const { return failures_; }
  DhtStatus last_status() const { return last_; }

 private:
  uint32_t next_ms_ = FIRST_MS;
  uint8_t failed_in_a_row_ = 0;
  bool ok_once_ = false;
  uint32_t ok_ms_ = 0;
  int16_t t_dc_ = 0;
  uint16_t h_dpct_ = 0;
  uint32_t reads_ = 0;
  uint32_t failures_ = 0;
  DhtStatus last_ = DhtStatus::NO_RESPONSE;
};

// A light sensor on an ADC (0..4095), smoothed with an exponential moving average (time constant
// TAU_MS), and its level from 0 (dark) to 1000 (full scale). `bright_high`: the voltage rises with
// the light; the common LDR module (LM393 comparator board, AO pin) is the other way round.
class Light {
 public:
  static constexpr uint32_t TAU_MS = 2000;

  explicit Light(bool bright_high = false) : bright_high_(bright_high) {}

  void add(uint16_t raw, uint32_t now_ms);
  bool valid() const { return started_; }
  uint16_t raw() const;
  uint16_t level() const;

 private:
  bool bright_high_;
  bool started_ = false;
  float avg_ = 0;
  uint32_t last_ms_ = 0;
};

// What the room sensors say, for an ENVIRONMENT payload. Sensors not fitted are left out; readings
// not valid are 0 with their flag clear.
struct Readings {
  const Pir *pir = nullptr;
  const Climate *climate = nullptr;
  const Light *light = nullptr;
};

proto::Environment environment(const Readings &r, uint32_t now_ms);

}  // namespace room
