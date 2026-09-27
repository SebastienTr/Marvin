// SPDX-License-Identifier: MIT
//
// The camera of the XIAO ESP32S3 Sense, served as MJPEG over HTTP. Built only with
// -DMARVIN_HAS_CAMERA (platformio.ini) on an ESP32-S3 with Arduino and PSRAM.
//
//   setup(), after Wi-Fi:  camera_app::begin();
//
//   http://<robot ip>:81/stream    multipart/x-mixed-replace MJPEG (boundary "marvinframe"), every
//                                  part with Content-Length and X-Marvin-Time-Us (robot clock,
//                                  the same as the UDP headers' t_us)
//   http://<robot ip>:81/capture   the latest frame as one JPEG
//   http://<robot ip>:81/          a page showing the stream
//
// Sensor: OV3660 or OV2640 on the Sense board's DVP connector, detected by esp32-camera. VGA
// (640 x 480) JPEG, quality 12, two frame buffers in PSRAM, always the latest frame.
//
// The server is a small socket loop in one task (core 0, priority 1, below Wi-Fi and lwIP, same
// as the face), not esp_http_server: a single frame grab is shared by every client, several
// clients can stream at once (the viewer and the web UI), and /capture works while a stream is
// open. Frames go out at most MARVIN_CAMERA_FPS times a second (default 10), and not at all while
// nobody is connected, so the camera never competes with the UDP sensor streams for air time
// more than it has to (VGA at quality 12 is ~25-40 kB a frame: ~3 Mbit/s at 10 fps).
//
// Build flags (all optional): MARVIN_CAMERA_FPS (10), MARVIN_CAMERA_QUALITY (12, 0-63, lower is
// better), MARVIN_CAMERA_VFLIP / MARVIN_CAMERA_HMIRROR (0 or 1, for how the module is mounted).
#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(MARVIN_HAS_CAMERA) && defined(ARDUINO) && defined(ESP32)

namespace camera_app {

// Initialises the camera and starts the server task. False without PSRAM or camera.
bool begin();
bool ready();

// Detected sensor ("OV3660", "OV2640", ...), or "none".
const char *sensor_name();

// One-line summary since the previous call (for LOG); true if anyone was served meanwhile.
bool stats_line(char *out, size_t cap);

}  // namespace camera_app

#endif
