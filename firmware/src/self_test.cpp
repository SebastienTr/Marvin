// SPDX-License-Identifier: MIT
#include "self_test.h"

#include <math.h>
#include <stdio.h>

namespace selftest {

namespace {

size_t done(int n, size_t cap) {
  if (n < 0) return 0;
  return (size_t)n < cap ? (size_t)n : (cap ? cap - 1 : 0);
}

}  // namespace

bool screen_answered(uint32_t id) {
  id &= 0xFFFFFF;
  return id != 0 && id != 0xFFFFFF;
}

size_t screen_line(char *out, size_t cap, uint32_t id) {
  if (screen_answered(id))
    return done(snprintf(out, cap, "screen: ok (ST7789 id %02x %02x %02x)", (unsigned)(id >> 16) & 0xFF,
                         (unsigned)(id >> 8) & 0xFF, (unsigned)id & 0xFF),
                cap);
  return done(snprintf(out, cap,
                       "screen: no answer - check DIN->D10, CLK->D8, CS->D0, DC->D1, RST->D3, VCC and BL->3V3, GND "
                       "(if the eyes show anyway, your module cannot answer: ignore this)"),
              cap);
}

size_t speaker_line(char *out, size_t cap, bool i2s_ok) {
  if (i2s_ok) return done(snprintf(out, cap, "speaker: chime played"), cap);
  return done(snprintf(out, cap, "speaker: I2S did not start (a firmware problem, not the wiring)"), cap);
}

float dbfs(double sum_sq, uint32_t n) {
  if (n == 0 || sum_sq <= 0) return -120.0f;
  double rms = sqrt(sum_sq / n) / 32768.0;
  double db = 20.0 * log10(rms);
  return db < -120.0 ? -120.0f : (float)db;
}

size_t mic_line(char *out, size_t cap, bool got_data, float level_dbfs, uint16_t peak) {
  if (!got_data)
    return done(snprintf(out, cap, "mic: no data - the PDM microphone did not start (is the Sense board clipped on?)"),
                cap);
  if (peak == 0)
    return done(snprintf(out, cap, "mic: silent (only zeros) - is the Sense board (camera + mic) clipped on?"), cap);
  return done(snprintf(out, cap, "mic: level %.0f dBFS", level_dbfs), cap);
}

size_t camera_line(char *out, size_t cap, bool ok, int width, int height, const char *sensor, const char *ip,
                   uint16_t port, CameraFailure why) {
  if (ok)
    return done(snprintf(out, cap, "camera: ok %dx%d (%s), http://%s:%u/stream", width, height, sensor, ip,
                         (unsigned)port),
                cap);
  if (why == CAMERA_NO_PSRAM)
    return done(snprintf(out, cap, "camera: no PSRAM - this build needs the XIAO ESP32S3 (Sense) board settings"),
                cap);
  return done(snprintf(out, cap,
                       "camera: not found - check the Sense board (camera + mic) is clipped onto the XIAO and the "
                       "camera's ribbon is in its connector"),
              cap);
}

size_t camera_waiting_line(char *out, size_t cap) {
  return done(snprintf(out, cap, "camera: waits for the Wi-Fi (its server starts once it is up)"), cap);
}

size_t radar_line(char *out, size_t cap, uint32_t frames, float seconds, uint32_t bad_frames, uint32_t overflows) {
  if (frames == 0 || seconds <= 0) return done(snprintf(out, cap, "radar: no frames - check TX->D4, RX->D5, 5V/GND"), cap);
  int n = snprintf(out, cap, "radar: %.1f frame/s", frames / seconds);
  if (n >= 0 && (size_t)n < cap && (bad_frames || overflows))
    n += snprintf(out + n, cap - n, " (%lu bad frames, %lu UART overflows)", (unsigned long)bad_frames,
                  (unsigned long)overflows);
  return done(n, cap);
}

size_t wifi_line(char *out, size_t cap, const char *ip, int rssi) {
  return done(snprintf(out, cap, "wifi: %s (RSSI %d dBm)", ip, rssi), cap);
}

size_t wifi_waiting_line(char *out, size_t cap, const char *ssid, uint32_t seconds) {
  return done(snprintf(out, cap,
                       "wifi: still joining \"%s\" after %lu s - check WIFI_SSID and WIFI_PASSWORD in "
                       "include/secrets.h (2.4 GHz networks only)",
                       ssid, (unsigned long)seconds),
              cap);
}

size_t host_linked_line(char *out, size_t cap, const char *host_ip) {
  return done(snprintf(out, cap, "host: linked (%s)", host_ip), cap);
}

size_t host_waiting_line(char *out, size_t cap, uint32_t seconds) {
  return done(snprintf(out, cap,
                       "host: not found after %lu s - start the host on your computer (./marvin up), on the same "
                       "Wi-Fi; the robot keeps calling",
                       (unsigned long)seconds),
              cap);
}

}  // namespace selftest
