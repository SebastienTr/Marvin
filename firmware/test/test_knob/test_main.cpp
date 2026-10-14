// Tests of the robot's knob (knob.h): debouncing, tap, long press. `pio test -e native`.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include "knob.h"

void setUp() {}
void tearDown() {}

using knob::Action;
using knob::Knob;

// Feeds `pressed` every millisecond from t (inclusive) to t_end (exclusive); returns the actions
// seen, as a count of each and the time of the first one.
struct Seen {
  int taps = 0;
  int longs = 0;
  uint32_t first_at = 0;
};

static Seen run(Knob &k, bool pressed, uint32_t t, uint32_t t_end) {
  Seen s;
  for (; t != t_end; t++) {
    Action a = k.update(pressed, t);
    if (a == Action::NONE) continue;
    if (s.taps + s.longs == 0) s.first_at = t;
    if (a == Action::TAP) s.taps++;
    if (a == Action::LONG_PRESS) s.longs++;
  }
  return s;
}

static void test_actions_are_the_wire_values() {
  TEST_ASSERT_EQUAL_UINT8(1, (uint8_t)Action::TAP);
  TEST_ASSERT_EQUAL_UINT8(2, (uint8_t)Action::LONG_PRESS);
  TEST_ASSERT_EQUAL_STRING("tap", knob::action_name(Action::TAP));
  TEST_ASSERT_EQUAL_STRING("long press", knob::action_name(Action::LONG_PRESS));
  TEST_ASSERT_EQUAL_STRING("none", knob::action_name(Action::NONE));
}

static void test_tap_is_sent_on_release() {
  Knob k;
  run(k, false, 0, 100);
  Seen held = run(k, true, 100, 400);  // 300 ms
  TEST_ASSERT_EQUAL(0, held.taps + held.longs);
  TEST_ASSERT_TRUE(k.pressed());
  Seen released = run(k, false, 400, 500);
  TEST_ASSERT_EQUAL(1, released.taps);
  TEST_ASSERT_EQUAL(0, released.longs);
  TEST_ASSERT_EQUAL_UINT32(400 + Knob::DEBOUNCE_MS, released.first_at);
  TEST_ASSERT_FALSE(k.pressed());
}

static void test_long_press_once_while_held_and_nothing_on_release() {
  Knob k;
  run(k, false, 0, 100);
  Seen held = run(k, true, 100, 5000);
  TEST_ASSERT_EQUAL(1, held.longs);
  TEST_ASSERT_EQUAL(0, held.taps);
  TEST_ASSERT_EQUAL_UINT32(100 + proto::BUTTON_LONG_PRESS_MS, held.first_at);  // timed from the press itself
  Seen released = run(k, false, 5000, 5200);
  TEST_ASSERT_EQUAL(0, released.taps + released.longs);
}

static void test_the_limit() {
  // held 1499 ms: a tap; 1500 ms: a long press
  Knob a;
  run(a, false, 0, 10);
  Seen held = run(a, true, 10, 10 + 1499);
  TEST_ASSERT_EQUAL(0, held.longs);
  Seen rel = run(a, false, 10 + 1499, 2000);
  TEST_ASSERT_EQUAL(1, rel.taps);
  TEST_ASSERT_EQUAL(0, rel.longs);

  Knob b;
  run(b, false, 0, 10);
  held = run(b, true, 10, 10 + 1501);
  TEST_ASSERT_EQUAL(1, held.longs);
  rel = run(b, false, 10 + 1501, 2000);
  TEST_ASSERT_EQUAL(0, rel.taps + rel.longs);
}

static void test_bounce_is_ignored() {
  Knob k;
  run(k, false, 0, 100);
  // 10 ms of contact bounce, then held 200 ms
  uint32_t t = 100;
  int actions = 0;
  for (int i = 0; i < 10; i++, t++) actions += k.update(i % 2 == 0, t) != Action::NONE;
  Seen held = run(k, true, t, t + 200);
  t += 200;
  // bounce on release too
  for (int i = 0; i < 10; i++, t++) actions += k.update(i % 2 == 1, t) != Action::NONE;
  Seen rel = run(k, false, t, t + 100);
  TEST_ASSERT_EQUAL(0, actions + held.taps + held.longs);
  TEST_ASSERT_EQUAL(1, rel.taps);
  TEST_ASSERT_EQUAL(0, rel.longs);
}

static void test_glitch_shorter_than_debounce_is_not_a_press() {
  Knob k;
  run(k, false, 0, 100);
  Seen glitch = run(k, true, 100, 100 + Knob::DEBOUNCE_MS - 1);
  Seen after = run(k, false, 100 + Knob::DEBOUNCE_MS - 1, 400);
  TEST_ASSERT_EQUAL(0, glitch.taps + glitch.longs + after.taps + after.longs);
  TEST_ASSERT_FALSE(k.pressed());
}

static void test_held_at_boot_sends_nothing() {
  Knob k;
  Seen boot = run(k, true, 0, 3000);  // held (or stuck) since boot: no long press
  Seen rel = run(k, false, 3000, 3100);  // and no tap on its release
  TEST_ASSERT_EQUAL(0, boot.taps + boot.longs + rel.taps + rel.longs);
  Seen tap = run(k, true, 3100, 3300);
  tap = run(k, false, 3300, 3400);
  TEST_ASSERT_EQUAL(1, tap.taps);  // the next press counts
}

static void test_let_go_just_before_the_time_is_a_tap() {
  // released at 1490 ms: the release is still debouncing at 1500 ms, it is a tap all the same
  Knob k;
  run(k, false, 0, 10);
  Seen held = run(k, true, 10, 10 + 1490);
  Seen rel = run(k, false, 10 + 1490, 2000);
  TEST_ASSERT_EQUAL(0, held.longs + rel.longs);
  TEST_ASSERT_EQUAL(1, rel.taps);
}

static void test_clock_wrap() {
  Knob k;
  const uint32_t t0 = 0xFFFFFFFFu - 700;
  run(k, false, t0, t0 + 50);
  Seen held = run(k, true, t0 + 50, t0 + 50 + 2000);  // crosses the wrap
  TEST_ASSERT_EQUAL(1, held.longs);
  TEST_ASSERT_EQUAL_UINT32(t0 + 50 + 1500, held.first_at);
}

static void test_two_taps_in_a_row() {
  Knob k;
  run(k, false, 0, 50);
  int taps = 0;
  uint32_t t = 50;
  for (int i = 0; i < 2; i++) {
    taps += run(k, true, t, t + 120).taps;
    t += 120;
    taps += run(k, false, t, t + 120).taps;
    t += 120;
  }
  TEST_ASSERT_EQUAL(2, taps);
}

static void test_custom_long_press_time() {
  Knob k(500);
  run(k, false, 0, 10);
  Seen held = run(k, true, 10, 1000);
  TEST_ASSERT_EQUAL(1, held.longs);
  TEST_ASSERT_EQUAL_UINT32(510, held.first_at);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_actions_are_the_wire_values);
  RUN_TEST(test_tap_is_sent_on_release);
  RUN_TEST(test_long_press_once_while_held_and_nothing_on_release);
  RUN_TEST(test_the_limit);
  RUN_TEST(test_bounce_is_ignored);
  RUN_TEST(test_glitch_shorter_than_debounce_is_not_a_press);
  RUN_TEST(test_held_at_boot_sends_nothing);
  RUN_TEST(test_let_go_just_before_the_time_is_a_tap);
  RUN_TEST(test_clock_wrap);
  RUN_TEST(test_two_taps_in_a_row);
  RUN_TEST(test_custom_long_press_time);
  return UNITY_END();
}
