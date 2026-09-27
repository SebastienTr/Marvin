// SPDX-License-Identifier: MIT
//
// The face on the robot's screen, as main.cpp sees it. Built only for boards with the screen
// (-DMARVIN_HAS_SCREEN in platformio.ini, ESP32 + Arduino).
//
//   setup():                  face_app::begin();
//   UDP receive, per message: face_app::on_message(type, payload, payload_len);
//
// The face runs in its own FreeRTOS task (core 0, low priority, ~30 fps): main.cpp's loop is
// never blocked by rendering or SPI. on_message() only copies the message into a queue, so it is
// cheap and safe to call from the loop. With no FACE_STATE for 5 s (or none yet), the face acts
// on its own: nobody there, so it gets sleepy and falls asleep (face_link.h).
//
// Pins and panel settings default to docs/wiring.md and can be overridden with -D flags:
// MARVIN_TFT_SCK (7), MARVIN_TFT_MOSI (9), MARVIN_TFT_CS (1), MARVIN_TFT_DC (2), MARVIN_TFT_RST (4),
// MARVIN_TFT_SPI_HZ (40 MHz), MARVIN_TFT_COL_OFFSET (0), MARVIN_TFT_ROW_OFFSET (20),
// MARVIN_TFT_MADCTL (0x00), MARVIN_TFT_INVERT (1), MARVIN_FACE_FPS (30).
#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(MARVIN_HAS_SCREEN) && defined(ARDUINO) && defined(ESP32)

namespace face_app {

// Initialise the screen and start the face task. False if the framebuffer could not be allocated.
bool begin();

// Hand over a host -> robot message: type (header byte 3) and the payload after the 16-byte
// header. Face messages (FACE_STATE, FACE_EVENT) are queued, anything else is ignored.
void on_message(uint8_t type, const uint8_t *payload, size_t n);

// Frames drawn and pushed so far, and the last frame's render + push time (for logs).
uint32_t frames();
uint32_t last_frame_us();

}  // namespace face_app

#endif
