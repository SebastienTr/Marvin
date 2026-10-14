// The boot self-test's console lines (MARVIN_SELF_TEST, the XIAO bench: docs/xiao-bench.md).
//
// One line per part, so someone wiring the robot one module at a time sees at once what answers
// and, when something does not, which wires to check:
//
//   screen: ok (ST7789 id 85 85 52)
//   speaker: chime played
//   mic: level -52 dBFS
//   radar: 10.1 frame/s
//   wifi: 192.168.1.42 (RSSI -55 dBm)
//   camera: ok 640x480 (OV3660), http://192.168.1.42:81/stream
//   host: linked (192.168.1.10)
//
// The radar and mic lines come back every 5 s. Plain ASCII, so every serial monitor shows them.
// Portable C++ (no Arduino): only the wording lives here, unit-tested with `pio test -e native`.
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace selftest {

// The ST7789's RDDID answer (3 bytes, ID1 << 16 | ID2 << 8 | ID3) means a panel is there: neither
// all ones (the data line's pull-up, nothing answered) nor all zeros (the line held low).
bool screen_answered(uint32_t id);

// "screen: ok (ST7789 id 85 85 52)", or what to check when the panel did not answer.
size_t screen_line(char *out, size_t cap, uint32_t id);

// "speaker: chime played"; i2s_ok false: the amplifier's I2S driver did not start (a firmware
// problem, not the wiring: an amplifier that is missing never makes I2S fail).
size_t speaker_line(char *out, size_t cap, bool i2s_ok);

// The level of n microphone samples whose squares add up to sum_sq, in dBFS (full scale 32768),
// floored at -120 dBFS.
float dbfs(double sum_sq, uint32_t n);

// "mic: level -52 dBFS"; got_data false: no block came from the PDM microphone; peak 0: only zeros.
size_t mic_line(char *out, size_t cap, bool got_data, float level_dbfs, uint16_t peak);

// "camera: ok 640x480 (OV3660), http://192.168.1.42:81/stream"; ok false: `why` is the reason
// (selftest::CAMERA_NO_PSRAM or CAMERA_NOT_FOUND).
enum CameraFailure : uint8_t { CAMERA_NOT_FOUND = 0, CAMERA_NO_PSRAM = 1 };
size_t camera_line(char *out, size_t cap, bool ok, int width, int height, const char *sensor, const char *ip,
                   uint16_t port, CameraFailure why = CAMERA_NOT_FOUND);

// "radar: 10.1 frame/s" (with the bad frames and UART overflows when there were any), or
// "radar: no frames - check TX->D4, RX->D5, 5V/GND" when no valid frame came in `seconds`.
size_t radar_line(char *out, size_t cap, uint32_t frames, float seconds, uint32_t bad_frames, uint32_t overflows);

// "wifi: 192.168.1.42 (RSSI -55 dBm)".
size_t wifi_line(char *out, size_t cap, const char *ip, int rssi);

// "wifi: still joining "home" after 20 s - check WIFI_SSID and WIFI_PASSWORD in include/secrets.h
// (2.4 GHz networks only)".
size_t wifi_waiting_line(char *out, size_t cap, const char *ssid, uint32_t seconds);

// "host: linked (192.168.1.10)".
size_t host_linked_line(char *out, size_t cap, const char *host_ip);

// "host: not found after 10 s - start the host on your computer (./marvin up), on the same Wi-Fi;
// the robot keeps calling".
size_t host_waiting_line(char *out, size_t cap, uint32_t seconds);

}  // namespace selftest
