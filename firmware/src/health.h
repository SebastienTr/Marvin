// SPDX-License-Identifier: MIT
//
// The robot's health line, to the console and as a LOG to the host every minute (main.cpp), and
// why it last restarted (once per boot, on the first link):
//
//   health: up 3600 s, heap 182 kB free (min 151 kB, largest block 110 kB), PSRAM 7.1 of 8.0 MB free,
//   loop max 9 ms, RSSI -55 dBm, 0 wifi drops
//   boot: last reset power-on
//
// A shrinking "min" or "largest block" over hours is a leak or fragmentation; a long "loop max"
// is what makes the radar's UART overflow; a "task watchdog" reset means the loop was stuck for
// MARVIN_WDT_S seconds and the robot rebooted itself.
//
// Portable C++ (no Arduino): main.cpp reads the numbers, the wording lives here and is unit-tested
// with `pio test -e native`.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace health {

struct Snapshot {
  uint32_t uptime_s = 0;
  uint32_t heap_free = 0;     // bytes, internal RAM
  uint32_t heap_min = 0;      // bytes, the lowest free heap since boot
  uint32_t heap_largest = 0;  // bytes, the largest block that can be allocated
  uint32_t psram_free = 0;    // bytes
  uint32_t psram_total = 0;   // bytes, 0: no PSRAM
  uint32_t loop_max_ms = 0;   // the longest loop() since the previous line
  int rssi = 0;               // dBm, 0: no Wi-Fi
  uint32_t wifi_drops = 0;
};

// One line (no newline). Returns its length (snprintf's, clipped to cap - 1).
size_t line(char *out, size_t cap, const Snapshot &s);

// ESP-IDF's esp_reset_reason_t values (esp_system.h), named here so the wording is testable.
enum ResetReason : int {
  RESET_UNKNOWN = 0,
  RESET_POWERON = 1,
  RESET_EXT = 2,
  RESET_SW = 3,
  RESET_PANIC = 4,
  RESET_INT_WDT = 5,
  RESET_TASK_WDT = 6,
  RESET_WDT = 7,
  RESET_DEEPSLEEP = 8,
  RESET_BROWNOUT = 9,
  RESET_SDIO = 10,
};

// "power-on", "software (an update or a restart)", "crash", "task watchdog", "brownout", ...
const char *reset_reason_name(int reason);

// A reset the owner should hear about: a crash, a watchdog, a brownout.
bool reset_was_trouble(int reason);

// "boot: last reset power-on", with a hint for the troubling ones.
size_t boot_line(char *out, size_t cap, int reason);

}  // namespace health
