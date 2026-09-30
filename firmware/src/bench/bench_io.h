// SPDX-License-Identifier: MIT
//
// The bench board's parts, as main.cpp sees them: an SSD1306 OLED with the eyes, a Talk button and
// a Mute button, a passive buzzer and the status LED. Built only with -DMARVIN_HAS_BENCH_IO
// (platformio.ini: esp32dev and esp32s3) on an ESP32 with Arduino. Wiring: docs/wiring-bench.md.
//
//   setup(), first:        bench_io::begin();
//   while joining Wi-Fi:   bench_io::step(millis());   (the LED blinks; the eyes run on their own)
//   loop():                bench_io::set_phase(...); bench_io::step(ms);
//                          while (bench_io::next_press(&button)) send BUTTON
//   UDP receive:           bench_io::on_message(type, payload, payload_len);
//   HELLO flags:           bench_io::hello_flags()
//
// - OLED (I2C, 400 kHz): found at 0x3C or 0x3D. The eyes (bench/eyes.h) run in their own FreeRTOS
//   task (core 0, low priority, MARVIN_OLED_FPS frames/s), so a frame's ~25 ms of I2C never blocks
//   the loop. No screen, or a screen that stops answering (a loose wire): the task probes again
//   every 5 s and starts it again when it answers; I2C calls time out after 20 ms, never hang.
//   HELLO carries FLAG_FACE while the screen answers, so the host sends FACE_STATE and FACE_EVENT.
// - Buttons: to ground, internal pull-ups, debounced (30 ms). A press clicks the buzzer and queues
//   a BUTTON for the host (1 = talk, 2 = mute).
// - Buzzer: SOUND ids played as tone sequences with the LEDC (bench/bench_logic.h); non-blocking.
// - Status LED: slow blink joining Wi-Fi, fast blink looking for the host, steady once linked;
//   on the ESP32-S3 DevKitC-1, the same in colour on its RGB LED (blue, amber, green), dim.
//
// Pins (platformio.ini sets them per board; -1 leaves a part out): MARVIN_OLED_SDA, MARVIN_OLED_SCL,
// MARVIN_BUTTON_TALK, MARVIN_BUTTON_MUTE, MARVIN_BUZZER_PIN, and either MARVIN_STATUS_RGB (and
// MARVIN_STATUS_RGB_ALT: the RGB LED's data pin, GPIO48 on v1.0 boards, GPIO38 on v1.1; both are
// driven by default) or LED_BUILTIN for a plain LED. Options: MARVIN_OLED_FPS (25),
// MARVIN_BUZZER_LEDC_CHANNEL (6).
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bench_logic.h"

#if defined(MARVIN_HAS_BENCH_IO) && defined(ARDUINO) && defined(ESP32)

namespace bench_io {

// Pins, buzzer and LED, and the OLED task. False if the task could not start (the rest works).
bool begin();

// Where the link is: drives the status LED and the OLED's status line.
void set_phase(bench::LinkPhase phase);

// Buttons, buzzer, status LED. Call often (every loop); never blocks.
void step(uint32_t now_ms);

// A host -> robot message: FACE_STATE and FACE_EVENT go to the eyes, SOUND to the buzzer and the
// eyes. Anything else is ignored. Cheap: the eyes' messages are queued for their task.
void on_message(uint8_t type, const uint8_t *payload, size_t n);

// Plays a SOUND id locally (and shows its mood in the eyes). False for an unknown id or without a buzzer.
bool play_sound(uint8_t id);

// Takes the next button press waiting to go to the host (proto::ButtonId). False when none.
bool next_press(uint8_t *button);

// FLAG_BUTTONS, FLAG_BUZZER and, while the OLED answers, FLAG_FACE (protocol.h).
uint8_t hello_flags();

}  // namespace bench_io

#endif
