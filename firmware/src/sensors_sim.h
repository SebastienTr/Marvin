// Simulated lidar and LD2450, producing byte-exact sensor frames.
// Same room and walking person as host/marvin_host/sim.py, so both look alike in the viewer.
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace sim {

constexpr size_t LIDAR_PACKET = 47;
constexpr size_t LD2450_FRAME = 30;

void begin(uint8_t lidar_model);            // 1 = D500, 2 = D800
uint32_t lidar_points_per_second();
// Writes one 47-byte LDROBOT packet for time t (seconds) and advances the scan angle.
void lidar_packet(float t, uint8_t *out);
// Writes one 30-byte LD2450 frame for time t (seconds).
void ld2450_frame(float t, uint8_t *out);

}  // namespace sim
