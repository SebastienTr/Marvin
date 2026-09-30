// Tests of the room sensors' portable parts: `pio test -e native`.
// The PIR's warm-up and debounced motion, the DHT11's bits and frames and its read schedule, the
// light sensor's smoothing, and the ENVIRONMENT payload.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include <string.h>

#include "protocol.h"
#include "room/room_logic.h"

using room::Climate;
using room::DhtStatus;
using room::Light;
using room::Pir;

void setUp() {}
void tearDown() {}

// ---- PIR --------------------------------------------------------------------------------------

// Feeds `level` every 10 ms from `from` to `to` (excluded); returns how many changes were reported.
static int feed(Pir &p, bool level, uint32_t from, uint32_t to) {
  int changes = 0;
  for (uint32_t t = from; t != to; t += 10) changes += p.update(level, t);
  return changes;
}

static void test_pir_reports_nothing_while_warming_up() {
  Pir p(60000);
  TEST_ASSERT_TRUE(p.warming_up());
  TEST_ASSERT_EQUAL_UINT32(60000, p.warmup_left_ms(0));
  // it fires on its own while settling: not motion
  TEST_ASSERT_EQUAL(0, feed(p, true, 0, 5000));
  TEST_ASSERT_EQUAL(0, feed(p, false, 5000, 30000));
  TEST_ASSERT_FALSE(p.motion());
  TEST_ASSERT_EQUAL_UINT16(proto::ENV_NO_MOTION, p.motion_age_s(30000));
  TEST_ASSERT_EQUAL_UINT32(30000, p.warmup_left_ms(30000));
  TEST_ASSERT_EQUAL(0, feed(p, false, 30000, 59990));
  TEST_ASSERT_TRUE(p.warming_up());
  TEST_ASSERT_TRUE(p.update(false, 60000));  // warm: reported once
  TEST_ASSERT_FALSE(p.warming_up());
  TEST_ASSERT_FALSE(p.motion());
  TEST_ASSERT_EQUAL_UINT16(proto::ENV_NO_MOTION, p.motion_age_s(60000));
  TEST_ASSERT_EQUAL_UINT32(0, p.warmup_left_ms(60000));
}

static void test_pir_warm_with_motion_already_there() {
  Pir p(1000);
  feed(p, true, 0, 1000);
  TEST_ASSERT_TRUE(p.update(true, 1000));
  TEST_ASSERT_TRUE(p.motion());
  TEST_ASSERT_EQUAL_UINT16(0, p.motion_age_s(1000));
}

static void test_pir_motion_starts_and_ends_after_debounce() {
  Pir p(0);
  TEST_ASSERT_TRUE(p.update(false, 0));  // no warm-up: warm at the first sample
  TEST_ASSERT_FALSE(p.update(true, 1000));
  TEST_ASSERT_FALSE(p.update(true, 1099));
  TEST_ASSERT_FALSE(p.motion());
  TEST_ASSERT_TRUE(p.update(true, 1100));  // 100 ms high: motion
  TEST_ASSERT_TRUE(p.motion());
  TEST_ASSERT_EQUAL_UINT16(0, p.motion_age_s(1100));
  TEST_ASSERT_EQUAL(0, feed(p, true, 1110, 4000));  // held: nothing new, still 0 s ago
  TEST_ASSERT_EQUAL_UINT16(0, p.motion_age_s(4000));
  TEST_ASSERT_FALSE(p.update(false, 4000));
  TEST_ASSERT_TRUE(p.update(false, 4100));  // ended
  TEST_ASSERT_FALSE(p.motion());
  TEST_ASSERT_EQUAL_UINT16(0, p.motion_age_s(4100));
  TEST_ASSERT_EQUAL_UINT16(12, p.motion_age_s(16100));
  TEST_ASSERT_EQUAL_UINT16(0xFFFE, p.motion_age_s(4100 + 70000000u));  // saturates, never "none"
}

static void test_pir_ignores_spikes() {
  Pir p(0);
  p.update(false, 0);
  int changes = 0;
  for (uint32_t t = 10; t < 5000; t += 10) changes += p.update(t % 500 < 40, t);  // 40 ms spikes
  TEST_ASSERT_EQUAL(0, changes);
  TEST_ASSERT_FALSE(p.motion());
  TEST_ASSERT_EQUAL_UINT16(proto::ENV_NO_MOTION, p.motion_age_s(5000));
}

static void test_pir_across_millis_wrap() {
  Pir p(1000);
  const uint32_t t0 = 0xFFFFF000u;
  feed(p, false, t0, t0 + 1000);
  TEST_ASSERT_TRUE(p.update(false, t0 + 1000));  // warm, across the wrap
  TEST_ASSERT_FALSE(p.update(true, t0 + 2000));
  TEST_ASSERT_TRUE(p.update(true, t0 + 2100));
  TEST_ASSERT_TRUE(p.motion());
}

// ---- DHT11 ------------------------------------------------------------------------------------

// The pulses for a frame: each bit a 50 us low, then 26 us (0) or 70 us (1) high.
static void pulses(const uint8_t b[5], uint8_t low[40], uint8_t high[40]) {
  for (int i = 0; i < 40; i++) {
    low[i] = 50;
    high[i] = (b[i / 8] & (0x80 >> (i % 8))) ? 70 : 26;
  }
}

static void test_dht_bits_round_trip() {
  const uint8_t frame[5] = {48, 0, 23, 4, 75};
  uint8_t low[40], high[40], out[5];
  pulses(frame, low, high);
  room::dht_bits(low, high, out);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(frame, out, 5);
  // a slower clock or a slow edge: the high against its own low still decides
  for (int i = 0; i < 40; i++) {
    low[i] = (uint8_t)(low[i] + 20);
    high[i] = (uint8_t)(high[i] + (high[i] > 50 ? 20 : 30));
  }
  room::dht_bits(low, high, out);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(frame, out, 5);
}

static void test_dht11_decode() {
  int16_t t = 0;
  uint16_t h = 0;
  const uint8_t ok[5] = {48, 0, 23, 4, 75};
  TEST_ASSERT_EQUAL(DhtStatus::OK, room::dht11_decode(ok, &t, &h));
  TEST_ASSERT_EQUAL_INT16(234, t);
  TEST_ASSERT_EQUAL_UINT16(480, h);
  const uint8_t below_zero[5] = {60, 0, 3, 0x82, 60 + 3 + 0x82};  // recent DHT11s: bit 7 is the sign
  TEST_ASSERT_EQUAL(DhtStatus::OK, room::dht11_decode(below_zero, &t, &h));
  TEST_ASSERT_EQUAL_INT16(-32, t);
  const uint8_t bad_sum[5] = {48, 0, 23, 4, 76};
  TEST_ASSERT_EQUAL(DhtStatus::CHECKSUM, room::dht11_decode(bad_sum, &t, &h));
  const uint8_t zeros[5] = {0, 0, 0, 0, 0};  // a line held low reads as zeros with a good checksum
  TEST_ASSERT_EQUAL(DhtStatus::OUT_OF_RANGE, room::dht11_decode(zeros, &t, &h));
  const uint8_t hot[5] = {40, 0, 120, 0, 160};
  TEST_ASSERT_EQUAL(DhtStatus::OUT_OF_RANGE, room::dht11_decode(hot, &t, &h));
  TEST_ASSERT_EQUAL_STRING("bad checksum", room::dht_status_name(DhtStatus::CHECKSUM));
}

static void test_climate_schedule_and_staleness() {
  Climate c;
  TEST_ASSERT_FALSE(c.due(0));
  TEST_ASSERT_TRUE(c.due(Climate::FIRST_MS));
  TEST_ASSERT_FALSE(c.valid(1500));
  c.result(DhtStatus::OK, 234, 480, 1500);
  TEST_ASSERT_TRUE(c.valid(1500));
  TEST_ASSERT_EQUAL_INT16(234, c.temperature_dc());
  TEST_ASSERT_FALSE(c.due(6499));
  TEST_ASSERT_TRUE(c.due(6500));  // every 5 s
  // unplugged: two quick retries, then the period again
  c.result(DhtStatus::NO_RESPONSE, 0, 0, 6500);
  TEST_ASSERT_TRUE(c.due(6500 + Climate::RETRY_MS));
  c.result(DhtStatus::CHECKSUM, 0, 0, 7700);
  TEST_ASSERT_TRUE(c.due(8900));
  c.result(DhtStatus::NO_RESPONSE, 0, 0, 8900);
  TEST_ASSERT_FALSE(c.due(8900 + Climate::RETRY_MS));
  TEST_ASSERT_TRUE(c.due(8900 + Climate::PERIOD_MS));
  TEST_ASSERT_TRUE(c.valid(13499));  // the last good reading still counts...
  TEST_ASSERT_FALSE(c.valid(13500));  // ...for 12 s
  TEST_ASSERT_EQUAL_INT16(234, c.temperature_dc());
  TEST_ASSERT_EQUAL_UINT32(3, c.failures());
  TEST_ASSERT_EQUAL_UINT32(4, c.reads());
  TEST_ASSERT_EQUAL(DhtStatus::NO_RESPONSE, c.last_status());
  c.result(DhtStatus::OK, 240, 470, 13900);  // plugged back in
  TEST_ASSERT_TRUE(c.valid(13900));
  TEST_ASSERT_TRUE(c.due(18900));
}

// ---- Light ------------------------------------------------------------------------------------

static void test_light_smooths_and_normalises() {
  Light l;  // the common module: the voltage falls with the light
  TEST_ASSERT_FALSE(l.valid());
  TEST_ASSERT_EQUAL_UINT16(0, l.level());
  l.add(4095, 0);
  TEST_ASSERT_EQUAL_UINT16(0, l.level());  // dark
  for (uint32_t t = 50; t <= 20000; t += 50) l.add(0, t);
  TEST_ASSERT_EQUAL_UINT16(1000, l.level());  // bright, after 10 time constants
  TEST_ASSERT_EQUAL_UINT16(0, l.raw());
  Light m;
  m.add(1000, 0);
  m.add(3000, 2000);  // one time constant: 63 % of the way
  TEST_ASSERT_UINT16_WITHIN(2, 2264, m.raw());
  Light up(true);
  up.add(4095, 0);
  TEST_ASSERT_EQUAL_UINT16(1000, up.level());
  up.add(9999, 50);  // clamped
  TEST_ASSERT_EQUAL_UINT16(4095, up.raw());
}

// ---- ENVIRONMENT ------------------------------------------------------------------------------

static void test_environment_payload() {
  uint8_t p[16];
  memset(p, 0xAA, sizeof(p));
  proto::Environment e;
  e.sensors = proto::ENV_PIR | proto::ENV_CLIMATE;
  e.flags = proto::ENV_MOTION | proto::ENV_TEMPERATURE_VALID | proto::ENV_HUMIDITY_VALID;
  e.temperature_dc = -52;
  e.humidity_dpct = 480;
  e.motion_age_s = 0;
  TEST_ASSERT_EQUAL(12, proto::environment(p, e));
  const uint8_t want[12] = {0x03, 0x0D, 0xCC, 0xFF, 0xE0, 0x01, 0, 0, 0, 0, 0, 0};
  TEST_ASSERT_EQUAL_UINT8_ARRAY(want, p, 12);
  TEST_ASSERT_EQUAL_UINT8(0xAA, p[12]);
  TEST_ASSERT_EQUAL_UINT8(0x08, proto::ENVIRONMENT);
  TEST_ASSERT_EQUAL_UINT8(0x40, proto::FLAG_ENVIRONMENT);
}

static void test_environment_from_the_sensors() {
  // nothing fitted: nothing said
  proto::Environment none = room::environment(room::Readings(), 0);
  TEST_ASSERT_EQUAL_UINT8(0, none.sensors);
  TEST_ASSERT_EQUAL_UINT8(0, none.flags);
  TEST_ASSERT_EQUAL_UINT16(proto::ENV_NO_MOTION, none.motion_age_s);

  Pir pir(60000);
  pir.update(true, 0);
  Climate climate;  // no reading yet
  Light light;
  room::Readings r;
  r.pir = &pir;
  r.climate = &climate;
  r.light = &light;
  proto::Environment e = room::environment(r, 1000);
  TEST_ASSERT_EQUAL_UINT8(proto::ENV_PIR | proto::ENV_CLIMATE | proto::ENV_LIGHT, e.sensors);
  TEST_ASSERT_EQUAL_UINT8(proto::ENV_WARMING_UP, e.flags);  // no motion while warming, nothing valid
  TEST_ASSERT_EQUAL_INT16(0, e.temperature_dc);
  TEST_ASSERT_EQUAL_UINT16(proto::ENV_NO_MOTION, e.motion_age_s);

  feed(pir, true, 10, 60000);
  pir.update(true, 60000);
  climate.result(DhtStatus::OK, 215, 550, 59000);
  light.add(1024, 59000);
  e = room::environment(r, 60000);
  TEST_ASSERT_EQUAL_UINT8(proto::ENV_MOTION | proto::ENV_TEMPERATURE_VALID | proto::ENV_HUMIDITY_VALID |
                              proto::ENV_LIGHT_VALID, e.flags);
  TEST_ASSERT_EQUAL_INT16(215, e.temperature_dc);
  TEST_ASSERT_EQUAL_UINT16(550, e.humidity_dpct);
  TEST_ASSERT_EQUAL_UINT16(750, e.light);
  TEST_ASSERT_EQUAL_UINT16(1024, e.light_raw);
  TEST_ASSERT_EQUAL_UINT16(0, e.motion_age_s);
  // the DHT11 unplugged: its values go after 12 s, the sensor stays announced
  e = room::environment(r, 59000 + Climate::STALE_MS);
  TEST_ASSERT_TRUE(e.sensors & proto::ENV_CLIMATE);
  TEST_ASSERT_FALSE(e.flags & proto::ENV_TEMPERATURE_VALID);
  TEST_ASSERT_EQUAL_INT16(0, e.temperature_dc);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_pir_reports_nothing_while_warming_up);
  RUN_TEST(test_pir_warm_with_motion_already_there);
  RUN_TEST(test_pir_motion_starts_and_ends_after_debounce);
  RUN_TEST(test_pir_ignores_spikes);
  RUN_TEST(test_pir_across_millis_wrap);
  RUN_TEST(test_dht_bits_round_trip);
  RUN_TEST(test_dht11_decode);
  RUN_TEST(test_climate_schedule_and_staleness);
  RUN_TEST(test_light_smooths_and_normalises);
  RUN_TEST(test_environment_payload);
  RUN_TEST(test_environment_from_the_sensors);
  return UNITY_END();
}
