// Tests of the boot self-test's console lines (self_test.h): `pio test -e native`.
// docs/xiao-bench.md quotes these lines: change both together.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include <math.h>
#include <string.h>

#include "self_test.h"

void setUp() {}
void tearDown() {}

static char line[256];

static void test_screen() {
  TEST_ASSERT_TRUE(selftest::screen_answered(0x858552));
  TEST_ASSERT_FALSE(selftest::screen_answered(0xFFFFFF));
  TEST_ASSERT_FALSE(selftest::screen_answered(0x000000));
  TEST_ASSERT_FALSE(selftest::screen_answered(0xFFFFFFFF));  // only the 24 bits count
  selftest::screen_line(line, sizeof(line), 0x858552);
  TEST_ASSERT_EQUAL_STRING("screen: ok (ST7789 id 85 85 52)", line);
  selftest::screen_line(line, sizeof(line), 0xFFFFFF);
  TEST_ASSERT_EQUAL_STRING(
      "screen: no answer - check DIN->D10, CLK->D8, CS->D0, DC->D1, RST->D3, VCC and BL->3V3, GND "
      "(if the eyes show anyway, your module cannot answer: ignore this)",
      line);
}

static void test_speaker() {
  selftest::speaker_line(line, sizeof(line), true);
  TEST_ASSERT_EQUAL_STRING("speaker: chime played", line);
  selftest::speaker_line(line, sizeof(line), false);
  TEST_ASSERT_EQUAL_STRING("speaker: I2S did not start (a firmware problem, not the wiring)", line);
}

static void test_dbfs() {
  TEST_ASSERT_EQUAL_FLOAT(-120.0f, selftest::dbfs(0, 0));
  TEST_ASSERT_EQUAL_FLOAT(-120.0f, selftest::dbfs(0, 320));
  // a full-scale square wave is 0 dBFS, half of it -6 dBFS
  TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, selftest::dbfs(320.0 * 32768.0 * 32768.0, 320));
  TEST_ASSERT_FLOAT_WITHIN(0.01f, -6.02f, selftest::dbfs(320.0 * 16384.0 * 16384.0, 320));
  // one LSB of noise floors near -90 dBFS; anything quieter stops at -120
  TEST_ASSERT_FLOAT_WITHIN(0.01f, -90.31f, selftest::dbfs(320.0, 320));
  TEST_ASSERT_EQUAL_FLOAT(-120.0f, selftest::dbfs(1e-12, 320));
}

static void test_mic() {
  selftest::mic_line(line, sizeof(line), true, -38.4f, 900);
  TEST_ASSERT_EQUAL_STRING("mic: level -38 dBFS", line);
  selftest::mic_line(line, sizeof(line), false, 0, 0);
  TEST_ASSERT_EQUAL_STRING("mic: no data - the PDM microphone did not start (is the Sense board clipped on?)", line);
  selftest::mic_line(line, sizeof(line), true, -120.0f, 0);
  TEST_ASSERT_EQUAL_STRING("mic: silent (only zeros) - is the Sense board (camera + mic) clipped on?", line);
}

static void test_camera() {
  selftest::camera_line(line, sizeof(line), true, 640, 480, "OV3660", "192.168.1.42", 81);
  TEST_ASSERT_EQUAL_STRING("camera: ok 640x480 (OV3660), http://192.168.1.42:81/stream", line);
  selftest::camera_line(line, sizeof(line), false, 0, 0, "none", "192.168.1.42", 81);
  TEST_ASSERT_EQUAL_STRING(
      "camera: not found - check the Sense board (camera + mic) is clipped onto the XIAO and the camera's ribbon is "
      "in its connector",
      line);
  selftest::camera_line(line, sizeof(line), false, 0, 0, "none", "", 81, selftest::CAMERA_NO_PSRAM);
  TEST_ASSERT_EQUAL_STRING("camera: no PSRAM - this build needs the XIAO ESP32S3 (Sense) board settings", line);
}

static void test_radar() {
  selftest::radar_line(line, sizeof(line), 101, 10.0f, 0, 0);
  TEST_ASSERT_EQUAL_STRING("radar: 10.1 frame/s", line);
  selftest::radar_line(line, sizeof(line), 50, 5.0f, 2, 1);
  TEST_ASSERT_EQUAL_STRING("radar: 10.0 frame/s (2 bad frames, 1 UART overflows)", line);
  selftest::radar_line(line, sizeof(line), 0, 5.0f, 3, 0);
  TEST_ASSERT_EQUAL_STRING("radar: no frames - check TX->D4, RX->D5, 5V/GND", line);
}

static void test_wifi_and_host() {
  selftest::wifi_line(line, sizeof(line), "192.168.1.42", -55);
  TEST_ASSERT_EQUAL_STRING("wifi: 192.168.1.42 (RSSI -55 dBm)", line);
  selftest::wifi_waiting_line(line, sizeof(line), "home", 20);
  TEST_ASSERT_EQUAL_STRING(
      "wifi: still joining \"home\" after 20 s - check WIFI_SSID and WIFI_PASSWORD in include/secrets.h (2.4 GHz "
      "networks only)",
      line);
  selftest::host_linked_line(line, sizeof(line), "192.168.1.10");
  TEST_ASSERT_EQUAL_STRING("host: linked (192.168.1.10)", line);
  selftest::host_waiting_line(line, sizeof(line), 10);
  TEST_ASSERT_EQUAL_STRING(
      "host: not found after 10 s - start the host on your computer (./marvin up), on the same Wi-Fi; the robot "
      "keeps calling",
      line);
}

static void test_truncation() {
  char small[12];
  size_t n = selftest::radar_line(small, sizeof(small), 101, 10.0f, 0, 0);
  TEST_ASSERT_EQUAL(11, n);
  TEST_ASSERT_EQUAL(11, strlen(small));
}

static void test_camera_waiting() {
  selftest::camera_waiting_line(line, sizeof(line));
  TEST_ASSERT_EQUAL_STRING("camera: waits for the Wi-Fi (its server starts once it is up)", line);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_screen);
  RUN_TEST(test_speaker);
  RUN_TEST(test_dbfs);
  RUN_TEST(test_mic);
  RUN_TEST(test_camera);
  RUN_TEST(test_radar);
  RUN_TEST(test_wifi_and_host);
  RUN_TEST(test_truncation);
  RUN_TEST(test_camera_waiting);
  return UNITY_END();
}
