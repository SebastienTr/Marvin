// Tests of the Wi-Fi reconnection state machine (wifi_reconnect.h). `pio test -e native`.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include "wifi_reconnect.h"

void setUp() {}
void tearDown() {}

using wifi::Reconnect;
using Step = wifi::Reconnect::Step;

// Runs the machine every 10 ms from t to t_end with a constant link state; records the BEGIN times.
struct Trace {
  int begins = 0, ups = 0, downs = 0;
  uint32_t begin_at[32] = {};
};

static Trace run(Reconnect &r, bool connected, uint32_t t, uint32_t t_end, Trace tr = Trace()) {
  for (; (int32_t)(t_end - t) > 0; t += 10) {
    switch (r.update(connected, t)) {
      case Step::BEGIN:
        if (tr.begins < 32) tr.begin_at[tr.begins] = t;
        tr.begins++;
        break;
      case Step::UP: tr.ups++; break;
      case Step::DOWN: tr.downs++; break;
      case Step::NONE: break;
    }
  }
  return tr;
}

static void test_backoff_doubles_then_caps() {
  TEST_ASSERT_EQUAL_UINT32(1000, Reconnect::backoff_ms(1));
  TEST_ASSERT_EQUAL_UINT32(2000, Reconnect::backoff_ms(2));
  TEST_ASSERT_EQUAL_UINT32(4000, Reconnect::backoff_ms(3));
  TEST_ASSERT_EQUAL_UINT32(16000, Reconnect::backoff_ms(5));
  TEST_ASSERT_EQUAL_UINT32(30000, Reconnect::backoff_ms(6));
  TEST_ASSERT_EQUAL_UINT32(30000, Reconnect::backoff_ms(1000000));  // no overflow
  TEST_ASSERT_EQUAL_UINT32(1000, Reconnect::backoff_ms(0));
}

static void test_boot_begins_at_once() {
  Reconnect r;
  TEST_ASSERT_EQUAL(Step::BEGIN, r.update(false, 0));
  TEST_ASSERT_EQUAL_UINT32(1, r.attempt());
  TEST_ASSERT_FALSE(r.up());
  TEST_ASSERT_EQUAL(Step::NONE, r.update(false, 10));
}

static void test_boot_already_connected() {
  Reconnect r;
  TEST_ASSERT_EQUAL(Step::UP, r.update(true, 0));
  TEST_ASSERT_TRUE(r.up());
  TEST_ASSERT_EQUAL(Step::NONE, r.update(true, 10));
}

static void test_connects_during_the_first_attempt() {
  Reconnect r;
  Trace tr = run(r, false, 0, 3000);
  TEST_ASSERT_EQUAL(1, tr.begins);
  TEST_ASSERT_EQUAL(Step::UP, r.update(true, 3000));
  TEST_ASSERT_EQUAL_UINT32(0, r.attempt());
  TEST_ASSERT_EQUAL_UINT32(0, r.drops());
}

static void test_failed_attempts_back_off() {
  Reconnect r;
  // never connects: attempt 1 at 0, 10 s to try, 1 s pause; attempt 2 at 11 s, 2 s pause; ...
  Trace tr = run(r, false, 0, 200000);
  const uint32_t expected[] = {0, 11000, 23000, 37000, 55000, 81000, 121000, 161000};
  for (int i = 0; i < 8; i++) TEST_ASSERT_UINT32_WITHIN(20, expected[i], tr.begin_at[i]);
  TEST_ASSERT_EQUAL(0, tr.ups + tr.downs);
  TEST_ASSERT_EQUAL_UINT32((uint32_t)tr.begins, r.attempt());
}

static void test_never_gives_up() {
  Reconnect r;
  Trace tr = run(r, false, 0, 3600000);  // an hour without a network
  // after the first minute or so, one attempt every 40 s (10 s trying + 30 s pause)
  TEST_ASSERT_INT_WITHIN(3, 3600 / 40 + 3, tr.begins);
}

static void test_drop_then_reconnect() {
  Reconnect r;
  r.update(true, 0);
  Trace tr = run(r, true, 0, 60000);
  TEST_ASSERT_EQUAL(0, tr.begins + tr.ups + tr.downs);
  TEST_ASSERT_EQUAL(Step::DOWN, r.update(false, 60000));
  TEST_ASSERT_EQUAL_UINT32(1, r.drops());
  TEST_ASSERT_EQUAL_UINT32(60000, r.down_since());
  TEST_ASSERT_FALSE(r.up());
  // the first attempt after a drop comes quickly, then the backoff starts again from 1 s
  tr = run(r, false, 60010, 80000);
  TEST_ASSERT_EQUAL(2, tr.begins);
  TEST_ASSERT_UINT32_WITHIN(20, 60000 + Reconnect::RETRY_AFTER_DROP_MS, tr.begin_at[0]);
  TEST_ASSERT_UINT32_WITHIN(20, tr.begin_at[0] + Reconnect::ATTEMPT_MS + 1000, tr.begin_at[1]);
  TEST_ASSERT_EQUAL(Step::UP, r.update(true, 80000));
  TEST_ASSERT_EQUAL_UINT32(0, r.attempt());
  // a second drop: counted, and the attempts start from 1 again
  TEST_ASSERT_EQUAL(Step::DOWN, r.update(false, 100000));
  TEST_ASSERT_EQUAL_UINT32(2, r.drops());
  tr = run(r, false, 100010, 101000);
  TEST_ASSERT_EQUAL(1, tr.begins);
  TEST_ASSERT_EQUAL_UINT32(1, r.attempt());
}

static void test_up_during_a_pause() {
  Reconnect r;
  run(r, false, 0, 10500);  // attempt 1 failed, pausing
  TEST_ASSERT_EQUAL(Step::UP, r.update(true, 10500));  // the driver made it anyway
  TEST_ASSERT_TRUE(r.up());
}

static void test_clock_wrap() {
  Reconnect r;
  const uint32_t t0 = 0xFFFFFFFFu - 5000;
  TEST_ASSERT_EQUAL(Step::BEGIN, r.update(false, t0));
  Trace tr = run(r, false, t0 + 10, t0 + 12000);  // crosses the wrap
  TEST_ASSERT_EQUAL(1, tr.begins);
  TEST_ASSERT_UINT32_WITHIN(20, t0 + 11000, tr.begin_at[0]);
}

static void test_step_names() {
  TEST_ASSERT_EQUAL_STRING("begin", wifi::step_name(Step::BEGIN));
  TEST_ASSERT_EQUAL_STRING("up", wifi::step_name(Step::UP));
  TEST_ASSERT_EQUAL_STRING("down", wifi::step_name(Step::DOWN));
  TEST_ASSERT_EQUAL_STRING("none", wifi::step_name(Step::NONE));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_backoff_doubles_then_caps);
  RUN_TEST(test_boot_begins_at_once);
  RUN_TEST(test_boot_already_connected);
  RUN_TEST(test_connects_during_the_first_attempt);
  RUN_TEST(test_failed_attempts_back_off);
  RUN_TEST(test_never_gives_up);
  RUN_TEST(test_drop_then_reconnect);
  RUN_TEST(test_up_during_a_pause);
  RUN_TEST(test_clock_wrap);
  RUN_TEST(test_step_names);
  return UNITY_END();
}
