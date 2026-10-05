// SPDX-License-Identifier: MIT
//
// The head, as main.cpp sees it: a 9 g hobby servo turning the head towards the nearest LD2450
// target (head/head_logic.h), and the presence chirps' events. Built only with -DMARVIN_HAS_HEAD
// (platformio.ini: esp32dev_looks) on an ESP32 with Arduino. Wiring: docs/looks-bench.md.
// Protocol: HEAD and HEAD_CTRL in docs/protocol.md.
//
//   setup():           head_io::begin();
//   each LD2450 frame: head_io::on_ld2450(frame, n, ms);     (linked or not)
//   loop():            head_io::step(ms); head_io::set_linked(linked);
//                      if linked and (head_io::changed() or every HEAD_PERIOD_MS): send HEAD with
//                      head_io::payload(p)
//                      while (head_io::next_event(&ev)) chirp
//   UDP receive:       head_io::on_message(type, payload, n, ms);   (HEAD_CTRL)
//
// - Servo: LEDC at 50 Hz, 16-bit duty, pulse MARVIN_HEAD_MIN_US..MARVIN_HEAD_MAX_US (500..2500 us)
//   for 0..180 degrees. Silent at boot; driven from MARVIN_HEAD_ATTACH_MS (1 s) on, at the centre,
//   so its first current spike does not come with Wi-Fi's (a brownout on a USB-powered board).
//   It rests (no pulses: the servo goes limp and quiet) once the head has held still for
//   MARVIN_HEAD_REST_MS (5 s; 0 never rests), and wakes on the next move.
// - Where it looks: the nearest target (atan2(x, y)), smoothed, with a deadband and a speed limit;
//   no target for 3 s, a slow return to the centre; nobody for 20 s, an occasional glance around.
// - Presence: someone arriving after nobody for 10 s, or leaving for 10 s, as HELLO and BYE events.
//
// Options (platformio.ini): MARVIN_HEAD_PIN (13), MARVIN_HEAD_LEDC_CHANNEL (4: its own timer, the
// buzzer's channel 6 has another), MARVIN_HEAD_MIN_US, MARVIN_HEAD_MAX_US, MARVIN_HEAD_CENTER_DEG
// (90), MARVIN_HEAD_MIN_DEG (20), MARVIN_HEAD_MAX_DEG (160), MARVIN_HEAD_MIRROR (0: 1 if the head
// turns away from people), MARVIN_HEAD_RADAR_ON_HEAD (0: 1 if the LD2450 turns with the head),
// MARVIN_LD2450_X_SIGN (1: -1 for a radar mounted upside down), MARVIN_HEAD_MAX_DPS (120),
// MARVIN_OLED_ON_HEAD (1: the OLED turns with the head), MARVIN_HEAD_ATTACH_MS, MARVIN_HEAD_REST_MS.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "head_logic.h"

#if defined(MARVIN_HAS_HEAD) && defined(ARDUINO) && defined(ESP32)

namespace head_io {

// HEAD is sent this often while linked, and at once when changed() says so.
constexpr uint32_t HEAD_PERIOD_MS = 200;

void begin(uint32_t seed);

// One raw LD2450 frame (LD2450_FRAME bytes).
void on_ld2450(const uint8_t *frame, size_t n, uint32_t now_ms);

// Moves the servo. Call often (every loop); never blocks.
void step(uint32_t now_ms);

// Linked to a host or not: a manual head goes back to auto when the host is lost.
void set_linked(bool linked, uint32_t now_ms);

// HEAD_CTRL from the host. False for another type or a bad payload.
bool on_message(uint8_t type, const uint8_t *payload, size_t n, uint32_t now_ms);

// The mode, the target or the tracking changed since the last call. Clears it.
bool changed();

// The HEAD payload (proto::HEAD_SIZE bytes). Returns its size.
size_t payload(uint8_t *p);

// The next presence event (HELLO or BYE). False when none.
bool next_event(head::PresenceEvent *ev);

// The latest target as the eyes should look at it (head::face_point at height z_mm): turned into the
// head's frame when the screen turns with the head (MARVIN_OLED_ON_HEAD, 1) and the radar does not.
// False when the last frame had no target.
bool eyes_point(double z_mm, double out[3]);

}  // namespace head_io

#endif
