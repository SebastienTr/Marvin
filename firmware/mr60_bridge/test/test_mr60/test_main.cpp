// Unit tests of the MR60BHA2 tiny-frame parser, the vitals tracker and the VITALS encoder.
// Run on the host: `pio test -e native`.
// SPDX-License-Identifier: MIT
#include <math.h>
#include <string.h>
#include <unity.h>

#include <vector>

#include "mr60.h"

using Bytes = std::vector<uint8_t>;

void setUp() {}
void tearDown() {}

// Builds a radar frame the way the MR60BHA2 does.
static Bytes frame(uint16_t type, const Bytes &payload, uint16_t id = 0x1234) {
  Bytes f = {mr60::SOF, (uint8_t)(id >> 8), (uint8_t)id, (uint8_t)(payload.size() >> 8), (uint8_t)payload.size(),
             (uint8_t)(type >> 8), (uint8_t)type};
  f.push_back(mr60::checksum(f.data(), f.size()));
  if (!payload.empty()) {
    f.insert(f.end(), payload.begin(), payload.end());
    f.push_back(mr60::checksum(payload.data(), payload.size()));
  }
  return f;
}

static Bytes f32(float v) {
  uint8_t b[4];
  memcpy(b, &v, 4);   // host tests run on little-endian machines, like the radar's payload
  return Bytes(b, b + 4);
}

static Bytes cat(std::initializer_list<Bytes> parts) {
  Bytes out;
  for (const Bytes &p : parts) out.insert(out.end(), p.begin(), p.end());
  return out;
}

// Feeds bytes, returns the types of the frames completed.
static std::vector<uint16_t> feed(mr60::Parser &p, const Bytes &bytes) {
  std::vector<uint16_t> types;
  for (uint8_t b : bytes)
    if (p.feed(b)) types.push_back(p.frame().type);
  return types;
}

static void test_checksum() {
  const uint8_t d[] = {0x01, 0x02, 0x04};
  TEST_ASSERT_EQUAL_HEX8((uint8_t)~0x07, mr60::checksum(d, 3));
}

static void test_parses_a_frame() {
  mr60::Parser p;
  Bytes f = frame(mr60::BREATH_RATE, f32(14.5f), 0xBEEF);
  std::vector<uint16_t> types = feed(p, f);
  TEST_ASSERT_EQUAL(1, types.size());
  TEST_ASSERT_EQUAL_HEX16(mr60::BREATH_RATE, p.frame().type);
  TEST_ASSERT_EQUAL_HEX16(0xBEEF, p.frame().id);
  TEST_ASSERT_EQUAL(4, p.frame().len);
  float v;
  memcpy(&v, p.frame().payload, 4);
  TEST_ASSERT_EQUAL_FLOAT(14.5f, v);
}

static void test_skips_noise_and_splits() {
  mr60::Parser p;
  Bytes stream = cat({{0xFF, 0x00, 0x42}, frame(mr60::HEART_RATE, f32(70)), {0x13}, frame(mr60::BREATH_RATE, f32(15)),
                      frame(mr60::PHASES, cat({f32(1), f32(2), f32(3)}))});
  std::vector<uint16_t> types = feed(p, stream);
  TEST_ASSERT_EQUAL(3, types.size());
  TEST_ASSERT_EQUAL_HEX16(mr60::HEART_RATE, types[0]);
  TEST_ASSERT_EQUAL_HEX16(mr60::BREATH_RATE, types[1]);
  TEST_ASSERT_EQUAL_HEX16(mr60::PHASES, types[2]);
  TEST_ASSERT_EQUAL(0, p.errors());
}

static void test_resyncs_after_false_start() {
  // A stray 0x01 right before a real frame: the bad header is dropped and the real frame found.
  mr60::Parser p;
  Bytes stream = cat({{0x01, 0x07}, frame(mr60::HEART_RATE, f32(66))});
  std::vector<uint16_t> types = feed(p, stream);
  TEST_ASSERT_EQUAL(1, types.size());
  TEST_ASSERT_EQUAL_HEX16(mr60::HEART_RATE, types[0]);
  TEST_ASSERT_EQUAL(1, p.errors());
}

static void test_drops_bad_payload() {
  mr60::Parser p;
  Bytes bad = frame(mr60::BREATH_RATE, f32(12));
  bad[9] ^= 0x10;   // corrupt the payload
  std::vector<uint16_t> types = feed(p, cat({bad, frame(mr60::HEART_RATE, f32(80))}));
  TEST_ASSERT_EQUAL(1, types.size());
  TEST_ASSERT_EQUAL_HEX16(mr60::HEART_RATE, types[0]);
  TEST_ASSERT_EQUAL(1, p.errors());
}

static void test_rejects_oversized_length() {
  mr60::Parser p;
  Bytes h = {mr60::SOF, 0, 2, 0x7F, 0xFF, 0x0A, 0x14};
  h.push_back(mr60::checksum(h.data(), h.size()));   // valid header, absurd length
  std::vector<uint16_t> types = feed(p, cat({h, frame(mr60::HEART_RATE, f32(60))}));
  TEST_ASSERT_EQUAL(1, types.size());
  TEST_ASSERT_EQUAL(1, p.errors());
}

static void test_empty_payload_frame() {
  mr60::Parser p;
  std::vector<uint16_t> types = feed(p, cat({frame(0x0100, {}), frame(mr60::HEART_RATE, f32(60))}));
  TEST_ASSERT_EQUAL(2, types.size());
  TEST_ASSERT_EQUAL_HEX16(0x0100, types[0]);
  TEST_ASSERT_EQUAL_HEX16(mr60::HEART_RATE, types[1]);
}

static void apply(mr60::Tracker &t, const Bytes &f, uint32_t ms) {
  mr60::Parser p;
  for (uint8_t b : f)
    if (p.feed(b)) t.apply(p.frame().type, p.frame().payload, p.frame().len, ms);
}

static Bytes u32(uint32_t v) { return {(uint8_t)v, (uint8_t)(v >> 8), (uint8_t)(v >> 16), (uint8_t)(v >> 24)}; }

static void test_tracker_valid_only_with_person_and_fresh_rates() {
  mr60::Tracker t;
  TEST_ASSERT_FALSE(t.vitals(0).valid);
  apply(t, frame(mr60::BREATH_RATE, f32(14.2f)), 100);
  apply(t, frame(mr60::HEART_RATE, f32(71.3f)), 150);
  TEST_ASSERT_FALSE(t.vitals(200).valid);            // nobody reported yet
  apply(t, frame(mr60::PRESENCE, {1, 0}), 200);
  apply(t, frame(mr60::DISTANCE, cat({u32(1), f32(82.5f)})), 250);
  mr60::Vitals v = t.vitals(300);
  TEST_ASSERT_TRUE(v.valid);
  TEST_ASSERT_EQUAL_FLOAT(14.2f, v.breath_rate);
  TEST_ASSERT_EQUAL_FLOAT(71.3f, v.heart_rate);
  TEST_ASSERT_EQUAL(825, v.distance_mm);              // cm -> mm
  // keep the radar talking, but no new rates: stale after RATE_STALE_MS
  for (uint32_t ms = 1000; ms <= 6000; ms += 1000) apply(t, frame(mr60::DISTANCE, cat({u32(1), f32(80)})), ms);
  TEST_ASSERT_FALSE(t.vitals(6000).valid);
  TEST_ASSERT_EQUAL(800, t.vitals(6000).distance_mm);
}

static void test_tracker_absence_clears() {
  mr60::Tracker t;
  apply(t, frame(mr60::PRESENCE, {1, 0}), 0);
  apply(t, frame(mr60::BREATH_RATE, f32(14)), 0);
  apply(t, frame(mr60::HEART_RATE, f32(70)), 0);
  TEST_ASSERT_TRUE(t.vitals(10).valid);
  apply(t, frame(mr60::PRESENCE, {0, 0}), 20);
  mr60::Vitals v = t.vitals(30);
  TEST_ASSERT_FALSE(v.valid);
  TEST_ASSERT_EQUAL_FLOAT(0, v.heart_rate);
  apply(t, frame(mr60::PRESENCE, {1, 0}), 40);
  TEST_ASSERT_FALSE(t.vitals(50).valid);              // needs new rates after an absence
}

static void test_tracker_zero_rate_is_no_estimate() {
  mr60::Tracker t;
  apply(t, frame(mr60::PRESENCE, {1}), 0);
  apply(t, frame(mr60::BREATH_RATE, f32(0)), 0);
  apply(t, frame(mr60::HEART_RATE, f32(70)), 0);
  TEST_ASSERT_FALSE(t.vitals(10).valid);
}

static void test_tracker_presence_from_range_on_older_radar_firmware() {
  mr60::Tracker t;
  apply(t, frame(mr60::BREATH_RATE, f32(14)), 0);
  apply(t, frame(mr60::HEART_RATE, f32(70)), 0);
  apply(t, frame(mr60::DISTANCE, cat({u32(0), f32(0)})), 0);
  TEST_ASSERT_FALSE(t.present(10));
  apply(t, frame(mr60::DISTANCE, cat({u32(1), f32(60)})), 20);
  TEST_ASSERT_TRUE(t.present(30));
  TEST_ASSERT_TRUE(t.vitals(30).valid);
}

static void test_tracker_radar_silent() {
  mr60::Tracker t;
  apply(t, frame(mr60::PRESENCE, {1}), 0);
  apply(t, frame(mr60::BREATH_RATE, f32(14)), 0);
  apply(t, frame(mr60::HEART_RATE, f32(70)), 0);
  TEST_ASSERT_FALSE(t.vitals(mr60::Tracker::SILENT_MS + 1).valid);
}

static void test_tracker_firmware() {
  mr60::Tracker t;
  TEST_ASSERT_FALSE(t.has_firmware());
  apply(t, frame(mr60::FIRMWARE, {2, 1, 3, 7}), 0);
  mr60::FirmwareVersion fw;
  TEST_ASSERT_TRUE(t.firmware(fw));
  TEST_ASSERT_EQUAL(1, fw.major);
  TEST_ASSERT_EQUAL(3, fw.minor);
  TEST_ASSERT_EQUAL(7, fw.patch);
}

static void test_short_payload_ignored() {
  mr60::Tracker t;
  const uint8_t d[2] = {0, 0};
  TEST_ASSERT_FALSE(t.apply(mr60::PHASES, d, 2, 0));
  TEST_ASSERT_FALSE(t.apply(mr60::DISTANCE, d, 2, 0));
  TEST_ASSERT_FALSE(t.apply(0x1234, d, 2, 0));
}

static void test_wave_scaler() {
  // A tiny breathing phase (amplitude 0.004) on a large offset comes out as a -1..1 wave.
  mr60::WaveScaler s(10.0f, 8.0f, 1e-4f);
  float lo = 0, hi = 0;
  for (uint32_t ms = 0; ms <= 60000; ms += 50) {
    float y = s.update(3.0f + 0.004f * sinf(2 * (float)M_PI * 0.25f * ms / 1000.0f), ms);
    TEST_ASSERT_TRUE(y >= -1 && y <= 1);
    if (ms > 40000) {
      if (y < lo) lo = y;
      if (y > hi) hi = y;
    }
  }
  TEST_ASSERT_FLOAT_WITHIN(0.15f, 1.0f, hi);
  TEST_ASSERT_FLOAT_WITHIN(0.15f, -1.0f, lo);
}

static void test_encode_vitals() {
  mr60::Vitals v{true, 14.25f, 68.5f, 0.5f, -2.0f, 812};
  uint8_t out[mr60::VITALS_SIZE];
  TEST_ASSERT_EQUAL(11, mr60::encode_vitals(v, out));
  const uint8_t want[11] = {1,    0x91, 0x05,   // 1425
                            0xC2, 0x1A,         // 6850
                            0x00, 0x40,         // round(0.5 * 32767) = 16384
                            0x01, 0x80,         // clamped to -1 -> -32767
                            0x2C, 0x03};        // 812
  TEST_ASSERT_EQUAL_HEX8_ARRAY(want, out, 11);
  mr60::Vitals z{false, NAN, -3, NAN, 0, 0};
  mr60::encode_vitals(z, out);
  for (uint8_t b : out) TEST_ASSERT_EQUAL_HEX8(0, b);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_checksum);
  RUN_TEST(test_parses_a_frame);
  RUN_TEST(test_skips_noise_and_splits);
  RUN_TEST(test_resyncs_after_false_start);
  RUN_TEST(test_drops_bad_payload);
  RUN_TEST(test_rejects_oversized_length);
  RUN_TEST(test_empty_payload_frame);
  RUN_TEST(test_tracker_valid_only_with_person_and_fresh_rates);
  RUN_TEST(test_tracker_absence_clears);
  RUN_TEST(test_tracker_zero_rate_is_no_estimate);
  RUN_TEST(test_tracker_presence_from_range_on_older_radar_firmware);
  RUN_TEST(test_tracker_radar_silent);
  RUN_TEST(test_tracker_firmware);
  RUN_TEST(test_short_payload_ignored);
  RUN_TEST(test_wave_scaler);
  RUN_TEST(test_encode_vitals);
  return UNITY_END();
}
