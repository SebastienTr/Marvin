// SPDX-License-Identifier: MIT
#include "health.h"

#include <stdio.h>

namespace health {

namespace {

size_t done(int n, size_t cap) {
  if (n < 0) return 0;
  return (size_t)n < cap ? (size_t)n : (cap ? cap - 1 : 0);
}

unsigned kb(uint32_t bytes) { return (unsigned)((bytes + 512) / 1024); }

}  // namespace

size_t line(char *out, size_t cap, const Snapshot &s) {
  int n = snprintf(out, cap, "health: up %lu s, heap %u kB free (min %u kB, largest block %u kB)",
                   (unsigned long)s.uptime_s, kb(s.heap_free), kb(s.heap_min), kb(s.heap_largest));
  auto more = [&](int m) {
    if (n >= 0 && m >= 0) n += m;
  };
  auto room = [&]() -> size_t { return n >= 0 && (size_t)n < cap ? cap - n : 0; };
  if (room() && s.psram_total)
    more(snprintf(out + n, room(), ", PSRAM %.1f of %.1f MB free", s.psram_free / 1048576.0, s.psram_total / 1048576.0));
  if (room()) more(snprintf(out + n, room(), ", loop max %lu ms", (unsigned long)s.loop_max_ms));
  if (room()) {
    if (s.rssi) more(snprintf(out + n, room(), ", RSSI %d dBm", s.rssi));
    else more(snprintf(out + n, room(), ", no wifi"));
  }
  if (room()) more(snprintf(out + n, room(), ", %lu wifi drops", (unsigned long)s.wifi_drops));
  return done(n, cap);
}

const char *reset_reason_name(int reason) {
  switch (reason) {
    case RESET_POWERON: return "power-on";
    case RESET_EXT: return "reset pin";
    case RESET_SW: return "software (an update or a restart)";
    case RESET_PANIC: return "crash";
    case RESET_INT_WDT: return "interrupt watchdog";
    case RESET_TASK_WDT: return "task watchdog";
    case RESET_WDT: return "watchdog";
    case RESET_DEEPSLEEP: return "deep sleep";
    case RESET_BROWNOUT: return "brownout";
    case RESET_SDIO: return "SDIO";
    default: return "unknown";
  }
}

bool reset_was_trouble(int reason) {
  return reason == RESET_PANIC || reason == RESET_INT_WDT || reason == RESET_TASK_WDT || reason == RESET_WDT ||
         reason == RESET_BROWNOUT;
}

size_t boot_line(char *out, size_t cap, int reason) {
  const char *hint = "";
  if (reason == RESET_BROWNOUT) hint = " - the 5 V supply sagged: use a 5 V / 2 A adapter and a short USB-C cable";
  else if (reset_was_trouble(reason)) hint = " - the robot restarted itself; please report it with ./marvin report";
  return done(snprintf(out, cap, "boot: last reset %s%s", reset_reason_name(reason), hint), cap);
}

}  // namespace health
