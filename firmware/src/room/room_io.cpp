// SPDX-License-Identifier: MIT
#include "room_io.h"

#if defined(MARVIN_HAS_ROOM_SENSORS) && defined(ARDUINO) && defined(ESP32)

#include <Arduino.h>
#include <driver/gpio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <hal/cpu_hal.h>

#include "../protocol.h"

#ifndef MARVIN_PIR_PIN
#define MARVIN_PIR_PIN -1
#endif
#ifndef MARVIN_PIR_WARMUP_S
#define MARVIN_PIR_WARMUP_S 60
#endif
#ifndef MARVIN_DHT11_PIN
#define MARVIN_DHT11_PIN -1
#endif
#ifndef MARVIN_LDR_PIN
#define MARVIN_LDR_PIN -1
#endif
#ifndef MARVIN_LDR_BRIGHT_HIGH
#define MARVIN_LDR_BRIGHT_HIGH 0
#endif

namespace room_io {

namespace {

constexpr bool HAS_PIR = MARVIN_PIR_PIN >= 0;
constexpr bool HAS_DHT = MARVIN_DHT11_PIN >= 0;
#if defined(MARVIN_HAS_LDR)
constexpr bool HAS_LDR = MARVIN_LDR_PIN >= 0;
#else
constexpr bool HAS_LDR = false;
#endif
constexpr uint32_t LIGHT_SAMPLE_MS = 50;
constexpr uint32_t STATUS_LOG_MS = 60000;  // a line with every reading this often

room::Pir pir(MARVIN_PIR_WARMUP_S * 1000UL);
room::Light light(MARVIN_LDR_BRIGHT_HIGH != 0);
bool pending = false;

// ---- DHT11: its task --------------------------------------------------------------------------

// The task's Climate is copied here after each read, under `mux`, for the loop.
portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
room::Climate shared_climate;
portMUX_TYPE dht_mux = portMUX_INITIALIZER_UNLOCKED;  // interrupts off on this core while the bits come
uint32_t cycles_per_us = 240;

// Waits while the line stays at `level`, at most timeout_us. Returns how long it stayed, or -1.
inline int32_t wait_while(gpio_num_t pin, int level, uint32_t timeout_us) {
  const uint32_t c0 = cpu_hal_get_cycle_count();
  const uint32_t limit = timeout_us * cycles_per_us;
  for (;;) {
    const uint32_t dc = cpu_hal_get_cycle_count() - c0;
    if (gpio_get_level(pin) != level) return (int32_t)(dc / cycles_per_us);
    if (dc > limit) return -1;
  }
}

room::DhtStatus read_dht(int16_t *t_dc, uint16_t *h_dpct) {
  const gpio_num_t pin = (gpio_num_t)MARVIN_DHT11_PIN;
  gpio_set_level(pin, 0);
  gpio_set_direction(pin, GPIO_MODE_OUTPUT);
  vTaskDelay(pdMS_TO_TICKS(20));  // the start signal: at least 18 ms low; the task sleeps meanwhile
  uint8_t low[40], high[40];
  bool ok = true;
  portENTER_CRITICAL(&dht_mux);
  gpio_set_direction(pin, GPIO_MODE_INPUT);  // released: the pull-ups raise the line
  // the sensor answers within 20-40 us: 80 us low, 80 us high, then 40 bits
  if (wait_while(pin, 1, 100) < 0 || wait_while(pin, 0, 120) < 0 || wait_while(pin, 1, 120) < 0) ok = false;
  for (int i = 0; ok && i < 40; i++) {
    const int32_t l = wait_while(pin, 0, 120);
    const int32_t h = l < 0 ? -1 : wait_while(pin, 1, 120);
    if (h < 0) {
      ok = false;
      break;
    }
    low[i] = (uint8_t)(l > 255 ? 255 : l);
    high[i] = (uint8_t)(h > 255 ? 255 : h);
  }
  portEXIT_CRITICAL(&dht_mux);
  if (!ok) return room::DhtStatus::NO_RESPONSE;
  uint8_t bytes[5];
  room::dht_bits(low, high, bytes);
  return room::dht11_decode(bytes, t_dc, h_dpct);
}

void dht_task(void *) {
  room::Climate climate;
  for (;;) {
    const uint32_t now = millis();
    if (climate.due(now)) {
      int16_t t = 0;
      uint16_t h = 0;
      const room::DhtStatus s = read_dht(&t, &h);
      climate.result(s, t, h, millis());
      portENTER_CRITICAL(&mux);
      shared_climate = climate;
      portEXIT_CRITICAL(&mux);
    }
    vTaskDelay(pdMS_TO_TICKS(100));
  }
}

room::Climate climate_now() {
  portENTER_CRITICAL(&mux);
  room::Climate c = shared_climate;
  portEXIT_CRITICAL(&mux);
  return c;
}

// ---- Serial log -------------------------------------------------------------------------------

bool dht_was_valid = false;
bool dht_announced_missing = false;

void log_dht(const room::Climate &c, uint32_t now) {
  const bool valid = c.valid(now);
  if (valid && !dht_was_valid) {
    Serial.printf("dht11: %.1f °C, %.0f %% humidity\n", c.temperature_dc() / 10.0, c.humidity_dpct() / 10.0);
    dht_announced_missing = false;
    pending = true;
  } else if (!valid && dht_was_valid) {
    Serial.printf("dht11: no reading for %lu s (%s): unplugged?\n", (unsigned long)(room::Climate::STALE_MS / 1000),
                  room::dht_status_name(c.last_status()));
    pending = true;
  } else if (!valid && !dht_announced_missing && c.reads() >= 1 + room::Climate::RETRIES) {
    Serial.printf("dht11: no reading on GPIO%d (%s), trying again every %lu s\n", MARVIN_DHT11_PIN,
                  room::dht_status_name(c.last_status()), (unsigned long)(room::Climate::PERIOD_MS / 1000));
    dht_announced_missing = true;
  }
  dht_was_valid = valid;
}

void log_status(const room::Climate &c, uint32_t now) {
  char line[160];
  int n = snprintf(line, sizeof(line), "room:");
  if (HAS_DHT) {
    if (c.valid(now))
      n += snprintf(line + n, sizeof(line) - n, " %.1f °C, %.0f %%,", c.temperature_dc() / 10.0, c.humidity_dpct() / 10.0);
    else
      n += snprintf(line + n, sizeof(line) - n, " no DHT11 reading (%lu of %lu reads failed),",
                    (unsigned long)c.failures(), (unsigned long)c.reads());
  }
  if (HAS_LDR) n += snprintf(line + n, sizeof(line) - n, " light %u/1000 (raw %u),", light.level(), light.raw());
  if (HAS_PIR) {
    const uint16_t age = pir.motion_age_s(now);
    if (pir.warming_up())
      n += snprintf(line + n, sizeof(line) - n, " PIR warming up");
    else if (pir.motion())
      n += snprintf(line + n, sizeof(line) - n, " motion now");
    else if (age == proto::ENV_NO_MOTION)
      n += snprintf(line + n, sizeof(line) - n, " no motion yet");
    else
      n += snprintf(line + n, sizeof(line) - n, " last motion %u s ago", age);
  }
  if (n > 0 && line[n - 1] == ',') line[n - 1] = 0;
  Serial.println(line);
}

}  // namespace

bool begin() {
  bool ok = true;
  if (HAS_PIR) {
    pinMode(MARVIN_PIR_PIN, INPUT);
    Serial.printf("pir: on GPIO%d, warming up for %d s\n", MARVIN_PIR_PIN, MARVIN_PIR_WARMUP_S);
  }
  if (HAS_LDR) {
    analogReadResolution(12);
    analogSetPinAttenuation(MARVIN_LDR_PIN, ADC_11db);  // 0..~3.1 V
    Serial.printf("light: LDR on GPIO%d\n", MARVIN_LDR_PIN);
  }
  if (HAS_DHT) {
    const gpio_num_t pin = (gpio_num_t)MARVIN_DHT11_PIN;
    gpio_reset_pin(pin);
    gpio_set_pull_mode(pin, GPIO_PULLUP_ONLY);  // idle high even without the module's pull-up
    gpio_set_direction(pin, GPIO_MODE_INPUT);
    cycles_per_us = getCpuFrequencyMhz();
    // core 1 (the loop's: the Wi-Fi keeps core 0 with interrupts on), above the loop, asleep but for a read
    ok = xTaskCreatePinnedToCore(dht_task, "dht11", 3072, nullptr, 2, nullptr, 1) == pdPASS;
    Serial.printf("dht11: on GPIO%d%s\n", MARVIN_DHT11_PIN, ok ? "" : ", but its task did not start");
  }
  return ok;
}

void step(uint32_t now_ms) {
  const bool was_warming = pir.warming_up();
  if (HAS_PIR && pir.update(digitalRead(MARVIN_PIR_PIN) == HIGH, now_ms)) {
    pending = true;
    if (was_warming)
      Serial.printf("pir: warmed up, %s\n", pir.motion() ? "motion now" : "no motion");
    else
      Serial.println(pir.motion() ? "pir: motion" : "pir: still");
  }
  static uint32_t next_light = 0;
  if (HAS_LDR && (int32_t)(now_ms - next_light) >= 0) {
    next_light = now_ms + LIGHT_SAMPLE_MS;
    light.add((uint16_t)analogRead(MARVIN_LDR_PIN), now_ms);
  }
  static uint32_t next_status = STATUS_LOG_MS;
  const room::Climate c = HAS_DHT ? climate_now() : room::Climate();
  if (HAS_DHT) log_dht(c, now_ms);
  if ((int32_t)(now_ms - next_status) >= 0) {
    next_status = now_ms + STATUS_LOG_MS;
    log_status(c, now_ms);
  }
}

bool changed() {
  const bool p = pending;
  pending = false;
  return p;
}

size_t payload(uint8_t *p, uint32_t now_ms) {
  const room::Climate c = climate_now();
  room::Readings r;
  if (HAS_PIR) r.pir = &pir;
  if (HAS_DHT) r.climate = &c;
  if (HAS_LDR) r.light = &light;
  return proto::environment(p, room::environment(r, now_ms));
}

uint8_t hello_flags() { return HAS_PIR || HAS_DHT || HAS_LDR ? proto::FLAG_ENVIRONMENT : 0; }

}  // namespace room_io

#endif
