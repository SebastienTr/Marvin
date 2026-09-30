// SPDX-License-Identifier: MIT
//
// The room sensors, as main.cpp sees them: a PIR motion sensor (HC-SR501), a DHT11 temperature and
// humidity sensor and, once wired, a light sensor (LDR module). Built only with
// -DMARVIN_HAS_ROOM_SENSORS (platformio.ini: esp32dev and esp32s3) on an ESP32 with Arduino.
// Wiring: docs/wiring-bench.md. Protocol: ENVIRONMENT in docs/protocol.md.
//
//   setup():      room_io::begin();
//   loop():       room_io::step(ms);
//                 if linked and (room_io::changed() or every ENVIRONMENT_PERIOD_MS): send ENVIRONMENT
//                 with room_io::payload(p, ms)
//   HELLO flags:  room_io::hello_flags()
//
// - PIR (MARVIN_PIR_PIN): its 3.3 V output read as a digital input (no pull: the PIR drives it),
//   debounced 100 ms. Nothing is reported for MARVIN_PIR_WARMUP_S (60 s) after boot, while it settles
//   (ENVIRONMENT says it is warming up). Motion starting or ending is sent at once (changed()).
// - DHT11 (MARVIN_DHT11_PIN, the module's own pull-up, the pin's as well): read every 5 s in its own
//   FreeRTOS task on core 1 (the Wi-Fi's is core 0). The 18 ms start pulse sleeps; the ~5 ms of
//   bits are timed with interrupts off on that core, so the loop pauses that long every 5 s and the
//   Wi-Fi does not notice. Checksum and range checked, a failed read tried again after 1.2 s (twice),
//   a reading kept for 12 s: an unplugged sensor is reported invalid (and logged) within 12 s.
// - Light (MARVIN_HAS_LDR, MARVIN_LDR_PIN on ADC1, 11 dB attenuation): sampled every 50 ms, smoothed
//   over 2 s. Off by default: a floating ADC pin reads noise that looks like light, and on the classic
//   ESP32 its input-only pins have no pull to tell it apart. MARVIN_LDR_BRIGHT_HIGH=1 when the voltage
//   rises with the light (the common LM393 module's AO falls).
//
// Pins (platformio.ini sets them per board; -1 leaves a sensor out): MARVIN_PIR_PIN, MARVIN_DHT11_PIN,
// MARVIN_LDR_PIN. Options: MARVIN_PIR_WARMUP_S (60), MARVIN_LDR_BRIGHT_HIGH (0).
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "room_logic.h"

#if defined(MARVIN_HAS_ROOM_SENSORS) && defined(ARDUINO) && defined(ESP32)

namespace room_io {

// ENVIRONMENT is sent this often, and at once when changed() says so.
constexpr uint32_t ENVIRONMENT_PERIOD_MS = 5000;

// Pins and the DHT11's task. False if the task could not start (the PIR and the light still work).
bool begin();

// PIR and light samples, and the serial log of what changes. Call often (every loop); never blocks.
void step(uint32_t now_ms);

// Something worth sending at once happened since the last call: motion started or ended, the PIR
// warmed up, the DHT11 answered for the first time or stopped answering. Clears it.
bool changed();

// The ENVIRONMENT payload (proto::ENVIRONMENT_SIZE bytes) at now_ms. Returns its size.
size_t payload(uint8_t *p, uint32_t now_ms);

// FLAG_ENVIRONMENT when a sensor is built in (protocol.h).
uint8_t hello_flags();

}  // namespace room_io

#endif
