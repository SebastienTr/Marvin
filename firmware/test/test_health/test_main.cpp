// Tests of the health and boot lines (health.h). `pio test -e native`.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include <string.h>

#include "health.h"

void setUp() {}
void tearDown() {}

static char out[256];

static health::Snapshot robot() {
  health::Snapshot s;
  s.uptime_s = 3600;
  s.heap_free = 182 * 1024;
  s.heap_min = 151 * 1024;
  s.heap_largest = 110 * 1024;
  s.psram_free = 7445000;
  s.psram_total = 8388608;
  s.loop_max_ms = 9;
  s.rssi = -55;
  s.wifi_drops = 0;
  return s;
}

static void test_health_line() {
  health::Snapshot s = robot();
  size_t n = health::line(out, sizeof(out), s);
  TEST_ASSERT_EQUAL_STRING(
      "health: up 3600 s, heap 182 kB free (min 151 kB, largest block 110 kB), PSRAM 7.1 of 8.0 MB free, "
      "loop max 9 ms, RSSI -55 dBm, 0 wifi drops",
      out);
  TEST_ASSERT_EQUAL(strlen(out), n);
}

static void test_health_line_without_psram_or_wifi() {
  health::Snapshot s = robot();
  s.psram_total = s.psram_free = 0;
  s.rssi = 0;
  s.wifi_drops = 3;
  health::line(out, sizeof(out), s);
  TEST_ASSERT_EQUAL_STRING(
      "health: up 3600 s, heap 182 kB free (min 151 kB, largest block 110 kB), loop max 9 ms, no wifi, 3 wifi drops",
      out);
}

static void test_health_line_clipped() {
  char small[40];
  size_t n = health::line(small, sizeof(small), robot());
  TEST_ASSERT_EQUAL(sizeof(small) - 1, n);
  TEST_ASSERT_EQUAL(sizeof(small) - 1, strlen(small));
  TEST_ASSERT_EQUAL(0, strncmp(small, "health: up 3600 s, heap 182 kB free", 35));
}

static void test_reset_reasons() {
  TEST_ASSERT_EQUAL_STRING("power-on", health::reset_reason_name(health::RESET_POWERON));
  TEST_ASSERT_EQUAL_STRING("task watchdog", health::reset_reason_name(health::RESET_TASK_WDT));
  TEST_ASSERT_EQUAL_STRING("unknown", health::reset_reason_name(42));
  TEST_ASSERT_FALSE(health::reset_was_trouble(health::RESET_POWERON));
  TEST_ASSERT_FALSE(health::reset_was_trouble(health::RESET_SW));
  TEST_ASSERT_TRUE(health::reset_was_trouble(health::RESET_PANIC));
  TEST_ASSERT_TRUE(health::reset_was_trouble(health::RESET_TASK_WDT));
  TEST_ASSERT_TRUE(health::reset_was_trouble(health::RESET_BROWNOUT));
}

static void test_boot_line() {
  health::boot_line(out, sizeof(out), health::RESET_POWERON);
  TEST_ASSERT_EQUAL_STRING("boot: last reset power-on", out);
  health::boot_line(out, sizeof(out), health::RESET_TASK_WDT);
  TEST_ASSERT_EQUAL_STRING(
      "boot: last reset task watchdog - the robot restarted itself; please report it with ./marvin report", out);
  health::boot_line(out, sizeof(out), health::RESET_BROWNOUT);
  TEST_ASSERT_EQUAL_STRING(
      "boot: last reset brownout - the 5 V supply sagged: use a 5 V / 2 A adapter and a short USB-C cable", out);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_health_line);
  RUN_TEST(test_health_line_without_psram_or_wifi);
  RUN_TEST(test_health_line_clipped);
  RUN_TEST(test_reset_reasons);
  RUN_TEST(test_boot_line);
  return UNITY_END();
}
