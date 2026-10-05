// Tests of the head's portable parts: `pio test -e native`.
// The nearest LD2450 target, the tracker (smoothing, deadband, slew, return, glances, host modes),
// the presence chirps, the HEAD payload and the eyes' point.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include <math.h>
#include <string.h>

#include "head/head_logic.h"
#include "protocol.h"

using head::Presence;
using head::PresenceEvent;
using head::Target;
using head::Tracker;

void setUp() {}
void tearDown() {}

// An LD2450 frame with up to 3 targets (x, y in mm, radar frame; 0, 0 = an empty slot).
static void frame(uint8_t f[30], const int xy[][2], int n) {
  memset(f, 0, 30);
  f[0] = 0xAA; f[1] = 0xFF; f[2] = 0x03; f[3] = 0x00; f[28] = 0x55; f[29] = 0xCC;
  auto enc = [](int v) { return (uint16_t)(v >= 0 ? 0x8000 | v : -v); };
  for (int i = 0; i < n; i++) {
    if (xy[i][0] == 0 && xy[i][1] == 0) continue;
    uint8_t *t = f + 4 + 8 * i;
    proto::put16(t, enc(xy[i][0]));
    proto::put16(t + 2, enc(xy[i][1]));
    proto::put16(t + 4, enc(0));
    proto::put16(t + 6, 360);
  }
}

static Target target_at(double yaw_deg, double dist_mm = 1500) {
  Target t;
  t.valid = true;
  t.yaw_deg = yaw_deg;
  t.x_mm = (int16_t)lround(dist_mm * sin(yaw_deg * M_PI / 180));
  t.y_mm = (int16_t)lround(dist_mm * cos(yaw_deg * M_PI / 180));
  t.distance_mm = (uint16_t)dist_mm;
  return t;
}

// Runs the tracker from t0 to t1 in 10 ms steps, feeding `t` every 100 ms (a radar frame).
static uint32_t run(Tracker &tr, uint32_t t0, uint32_t t1, const Target *t) {
  for (uint32_t ms = t0; ms < t1; ms += 10) {
    if (t && ms % 100 == 0) tr.on_target(*t, ms);
    tr.step(ms);
  }
  return t1;
}

// ---- Nearest target ---------------------------------------------------------------------------

static void test_nearest_target_picks_the_closest() {
  uint8_t f[30];
  const int xy[3][2] = {{-1000, 2000}, {500, 800}, {0, 0}};
  frame(f, xy, 3);
  Target t;
  TEST_ASSERT_TRUE(head::nearest_target(f, 30, 1, t));
  TEST_ASSERT_EQUAL_INT16(500, t.x_mm);
  TEST_ASSERT_EQUAL_INT16(800, t.y_mm);
  TEST_ASSERT_EQUAL_UINT16(943, t.distance_mm);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 32.005, t.yaw_deg);
  TEST_ASSERT_TRUE(head::nearest_target(f, 30, -1, t));  // radar mounted the other way round
  TEST_ASSERT_EQUAL_INT16(-500, t.x_mm);
  TEST_ASSERT_FLOAT_WITHIN(0.01, -32.005, t.yaw_deg);
}

static void test_nearest_target_ignores_empty_far_and_bad_frames() {
  uint8_t f[30];
  const int none[3][2] = {{0, 0}, {0, 0}, {0, 0}};
  frame(f, none, 3);
  Target t;
  TEST_ASSERT_FALSE(head::nearest_target(f, 30, 1, t));
  TEST_ASSERT_FALSE(t.valid);
  const int far[1][2] = {{0, 7000}};
  frame(f, far, 1);
  TEST_ASSERT_FALSE(head::nearest_target(f, 30, 1, t));
  const int ok[1][2] = {{100, 1000}};
  frame(f, ok, 1);
  TEST_ASSERT_FALSE(head::nearest_target(f, 29, 1, t));  // short
  f[29] = 0x00;
  TEST_ASSERT_FALSE(head::nearest_target(f, 30, 1, t));  // bad tail
}

// ---- Tracker ----------------------------------------------------------------------------------

static void test_tracker_follows_a_target_softly() {
  Tracker tr;
  const Target t = target_at(40);
  run(tr, 0, 300, &t);
  TEST_ASSERT_TRUE(tr.tracking());
  TEST_ASSERT_TRUE(tr.moving());
  TEST_ASSERT_TRUE(tr.yaw() > 0 && tr.yaw() < 40);
  // never faster than the slew limit
  double last = tr.yaw();
  for (uint32_t ms = 300; ms < 3000; ms += 10) {
    if (ms % 100 == 0) tr.on_target(t, ms);
    tr.step(ms);
    TEST_ASSERT_TRUE(fabs(tr.yaw() - last) <= 120 * 0.010 + 1e-9);
    last = tr.yaw();
  }
  TEST_ASSERT_FLOAT_WITHIN(0.1, 40, tr.yaw());
  TEST_ASSERT_FLOAT_WITHIN(0.1, 130, tr.servo_deg());
}

static void test_tracker_deadband_ignores_jitter() {
  Tracker tr;
  const Target a = target_at(20), b = target_at(22);
  run(tr, 0, 3000, &a);
  const double g = tr.goal();
  for (uint32_t ms = 3000; ms < 5000; ms += 10) {
    if (ms % 100 == 0) tr.on_target(ms % 200 ? a : b, ms);
    tr.step(ms);
  }
  TEST_ASSERT_TRUE(g == tr.goal());
}

static void test_tracker_limits_and_mirror() {
  head::Config c;
  c.mirror = true;
  Tracker tr(c);
  const Target t = target_at(80);  // beyond the 70 degree limit
  run(tr, 0, 5000, &t);
  TEST_ASSERT_FLOAT_WITHIN(0.1, 70, tr.yaw());
  TEST_ASSERT_FLOAT_WITHIN(0.1, 20, tr.servo_deg());  // mirrored: the other way
}

static void test_tracker_returns_slowly_then_glances() {
  Tracker tr(head::Config(), 1234);
  const Target t = target_at(-30);
  uint32_t ms = run(tr, 0, 3000, &t);
  TEST_ASSERT_FLOAT_WITHIN(0.5, -30, tr.yaw());
  ms = run(tr, ms, ms + 2800, nullptr);  // lost (last seen at 2900): still looking there for 3 s
  TEST_ASSERT_FLOAT_WITHIN(0.5, -30, tr.yaw());
  TEST_ASSERT_FALSE(tr.glancing());
  double last = tr.yaw();
  for (; ms < 9000; ms += 10) {
    tr.step(ms);
    TEST_ASSERT_TRUE(fabs(tr.yaw() - last) <= 30 * 0.010 + 1e-9);  // the slow return
    last = tr.yaw();
  }
  ms = run(tr, ms, 22000, nullptr);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 0, tr.yaw());
  TEST_ASSERT_FALSE(tr.glancing());
  // nobody for 20 s since the target: glances, now and then, within the amplitude, back to 0
  int glances = 0;
  bool was = false;
  double widest = 0;
  for (; ms < 90000; ms += 10) {
    tr.step(ms);
    if (tr.glancing() && !was) glances++;
    was = tr.glancing();
    if (fabs(tr.yaw()) > widest) widest = fabs(tr.yaw());
  }
  TEST_ASSERT_TRUE(glances >= 4 && glances <= 12);
  TEST_ASSERT_TRUE(widest >= 10 && widest <= 30.01);
  // someone comes back: glancing stops at once
  const Target u = target_at(10);
  tr.on_target(u, ms);
  tr.step(ms);
  TEST_ASSERT_FALSE(tr.glancing());
  TEST_ASSERT_TRUE(tr.tracking());
}

static void test_tracker_host_modes() {
  Tracker tr;
  const Target t = target_at(30);
  run(tr, 0, 3000, &t);
  TEST_ASSERT_TRUE(tr.set_mode(proto::HEAD_MANUAL, -45, 3000));
  run(tr, 3000, 6000, &t);
  TEST_ASSERT_FLOAT_WITHIN(0.1, -45, tr.yaw());
  TEST_ASSERT_FALSE(tr.tracking());
  TEST_ASSERT_TRUE(tr.set_mode(proto::HEAD_MANUAL, -200, 6000));  // clamped to the limit
  run(tr, 6000, 9000, &t);
  TEST_ASSERT_FLOAT_WITHIN(0.1, -70, tr.yaw());
  TEST_ASSERT_TRUE(tr.set_mode(proto::HEAD_CENTER, 0, 9000));
  run(tr, 9000, 14000, &t);
  TEST_ASSERT_FLOAT_WITHIN(0.1, 0, tr.yaw());
  TEST_ASSERT_FALSE(tr.set_mode(9, 0, 14000));
  TEST_ASSERT_EQUAL_UINT8(proto::HEAD_CENTER, tr.mode());
  tr.set_mode(proto::HEAD_MANUAL, 10, 14000);
  tr.host_lost(14000);
  TEST_ASSERT_EQUAL_UINT8(proto::HEAD_AUTO, tr.mode());
  run(tr, 14000, 17000, &t);
  TEST_ASSERT_FLOAT_WITHIN(0.5, 30, tr.yaw());
}

static void test_tracker_radar_on_head() {
  head::Config c;
  c.radar_on_head = true;
  Tracker tr(c);
  // a person at 30 degrees: the radar, turning with the head, sees them at 30 - yaw
  for (uint32_t ms = 0; ms < 5000; ms += 10) {
    if (ms % 100 == 0) tr.on_target(target_at(30 - tr.yaw()), ms);
    tr.step(ms);
  }
  TEST_ASSERT_FLOAT_WITHIN(1.0, 30, tr.yaw());
}

static void test_tracker_across_millis_wrap() {
  Tracker tr;
  const Target t = target_at(25);
  const uint32_t t0 = 0xFFFFFFFFu - 1500;
  uint32_t ms = t0;
  for (int i = 0; i < 400; i++, ms += 10) {
    if (i % 10 == 0) tr.on_target(t, ms);
    tr.step(ms);
  }
  TEST_ASSERT_FLOAT_WITHIN(0.5, 25, tr.yaw());
}

// ---- Presence ---------------------------------------------------------------------------------

static void test_presence_hello_and_bye() {
  Presence p;
  uint32_t ms = 0;
  // someone there at boot: no hello (nobody for less than 10 s), but they arrive
  for (; ms < 1000; ms += 100) TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.on_frame(true, ms));
  TEST_ASSERT_TRUE(p.present());
  // they leave: bye 10 s after the last frame with them
  int byes = 0;
  for (; ms < 12000; ms += 100) byes += p.on_frame(false, ms) == PresenceEvent::BYE;
  TEST_ASSERT_EQUAL(1, byes);
  TEST_ASSERT_FALSE(p.present());
  // a single ghost frame, twice: nothing
  TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.on_frame(true, 20000));
  TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.on_frame(false, 20100));
  TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.on_frame(true, 20200));
  TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.on_frame(true, 20300));
  TEST_ASSERT_EQUAL(PresenceEvent::HELLO, p.on_frame(true, 20400));  // the third in a row
  // gone for 5 s and back: no bye, no new hello
  for (ms = 20500; ms < 25500; ms += 100) TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.on_frame(false, ms));
  for (; ms < 26000; ms += 100) TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.on_frame(true, ms));
  TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.step(35000));
  TEST_ASSERT_EQUAL(PresenceEvent::BYE, p.step(36000));
  TEST_ASSERT_EQUAL(PresenceEvent::NONE, p.step(37000));
}

// ---- Payloads ---------------------------------------------------------------------------------

static void test_head_payload() {
  Tracker tr;
  const Target t = target_at(-12.34, 1234);
  run(tr, 0, 3000, &t);
  uint8_t p[16];
  TEST_ASSERT_EQUAL(proto::HEAD_SIZE, head::payload(p, tr, true));
  TEST_ASSERT_EQUAL_UINT8(proto::HEAD_AUTO, p[0]);
  TEST_ASSERT_EQUAL_UINT8(proto::HEAD_TARGET | proto::HEAD_TRACKING | proto::HEAD_SERVO_ON, p[1]);
  TEST_ASSERT_INT16_WITHIN(2, -123, (int16_t)proto::get16(p + 2));
  TEST_ASSERT_EQUAL_INT16((int16_t)lround(tr.goal() * 10), (int16_t)proto::get16(p + 4));
  TEST_ASSERT_INT16_WITHIN(1, -123, (int16_t)proto::get16(p + 6));
  TEST_ASSERT_EQUAL_UINT16(1234, proto::get16(p + 8));
  Target none;
  tr.on_target(none, 3000);
  head::payload(p, tr, false);
  TEST_ASSERT_EQUAL_UINT8(proto::HEAD_TRACKING, p[1]);  // still following for 3 s
  TEST_ASSERT_EQUAL_UINT16(0, proto::get16(p + 6));
  TEST_ASSERT_EQUAL_UINT16(0, proto::get16(p + 8));
}

static void test_face_point_turns_with_the_head() {
  const Target t = target_at(30, 1000);
  double p[3];
  head::face_point(t, 0, 300, p);
  TEST_ASSERT_FLOAT_WITHIN(1, 500, p[0]);
  TEST_ASSERT_FLOAT_WITHIN(1, -866, p[1]);  // forward is -y
  TEST_ASSERT_TRUE(300 == p[2]);
  head::face_point(t, 30, 300, p);           // the head faces them: straight ahead
  TEST_ASSERT_FLOAT_WITHIN(1, 0, p[0]);
  TEST_ASSERT_FLOAT_WITHIN(1, -1000, p[1]);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_nearest_target_picks_the_closest);
  RUN_TEST(test_nearest_target_ignores_empty_far_and_bad_frames);
  RUN_TEST(test_tracker_follows_a_target_softly);
  RUN_TEST(test_tracker_deadband_ignores_jitter);
  RUN_TEST(test_tracker_limits_and_mirror);
  RUN_TEST(test_tracker_returns_slowly_then_glances);
  RUN_TEST(test_tracker_host_modes);
  RUN_TEST(test_tracker_radar_on_head);
  RUN_TEST(test_tracker_across_millis_wrap);
  RUN_TEST(test_presence_hello_and_bye);
  RUN_TEST(test_head_payload);
  RUN_TEST(test_face_point_turns_with_the_head);
  return UNITY_END();
}
