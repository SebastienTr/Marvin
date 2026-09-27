// Simulated lidar and LD2450, producing byte-exact sensor frames.
// Same room and person as host/marvin_host/scene.py (via the generated scene_data.h).
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace sim {

constexpr size_t LIDAR_PACKET = 47;
constexpr size_t LD2450_FRAME = 30;

// 1 = D500, 2 = D800. Precomputes the room (a few seconds on an ESP8266): `idle` is called
// regularly so the watchdog stays fed.
void begin(uint8_t lidar_model, void (*idle)() = nullptr);
uint32_t lidar_points_per_second();
// Writes one 47-byte LDROBOT packet for time t (seconds) and advances the scan angle.
void lidar_packet(float t, uint8_t *out);
// Writes one 30-byte LD2450 frame for time t (seconds).
void ld2450_frame(float t, uint8_t *out);
// Writes the MR60BHA2 VITALS payload for time t (seconds), returns its size.
size_t vitals(float t, uint8_t *out);

}  // namespace sim
