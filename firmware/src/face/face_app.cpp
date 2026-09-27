// SPDX-License-Identifier: MIT
#include "face_app.h"

#if defined(MARVIN_HAS_SCREEN) && defined(ARDUINO) && defined(ESP32)

#include <Arduino.h>
#include <esp_heap_caps.h>
#include <esp_random.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>
#include <string.h>

#include "../protocol.h"
#include "display_st7789.h"
#include "face_link.h"

#ifndef MARVIN_TFT_SCK
#define MARVIN_TFT_SCK 7    // D8
#endif
#ifndef MARVIN_TFT_MOSI
#define MARVIN_TFT_MOSI 9   // D10
#endif
#ifndef MARVIN_TFT_CS
#define MARVIN_TFT_CS 1     // D0
#endif
#ifndef MARVIN_TFT_DC
#define MARVIN_TFT_DC 2     // D1
#endif
#ifndef MARVIN_TFT_RST
#define MARVIN_TFT_RST 4    // D3
#endif
#ifndef MARVIN_TFT_SPI_HZ
// GPIO7 / GPIO9 reach SPI2 through the GPIO matrix; 40 MHz is safe on jumper wires, 80 MHz
// usually works with short ones.
#define MARVIN_TFT_SPI_HZ 40000000
#endif
#ifndef MARVIN_TFT_SPI_BUS
#define MARVIN_TFT_SPI_BUS FSPI  // SPI2
#endif
#ifndef MARVIN_TFT_COL_OFFSET
#define MARVIN_TFT_COL_OFFSET 0
#endif
#ifndef MARVIN_TFT_ROW_OFFSET
#define MARVIN_TFT_ROW_OFFSET 20
#endif
#ifndef MARVIN_TFT_MADCTL
#define MARVIN_TFT_MADCTL 0x00
#endif
#ifndef MARVIN_TFT_INVERT
#define MARVIN_TFT_INVERT 1
#endif
#ifndef MARVIN_FACE_FPS
#define MARVIN_FACE_FPS 30
#endif

namespace face_app {

namespace {

constexpr size_t FB_PIXELS = face::SCREEN_W * face::SCREEN_H;

struct Msg {
  uint8_t type;
  uint8_t n;
  uint8_t data[24];  // FACE_STATE is 17 bytes; longer future payloads keep their v1 prefix
};

QueueHandle_t queue = nullptr;
face::FaceLink *link = nullptr;
uint16_t *fb = nullptr;
face::St7789 display;
volatile uint32_t n_frames = 0;
volatile uint32_t frame_us = 0;

void task(void *) {
  const TickType_t period = pdMS_TO_TICKS(1000 / MARVIN_FACE_FPS) ? pdMS_TO_TICKS(1000 / MARVIN_FACE_FPS) : 1;
  TickType_t wake = xTaskGetTickCount();
  bool first = true;
  for (;;) {
    const uint32_t now = millis();
    Msg m;
    while (xQueueReceive(queue, &m, 0) == pdTRUE) link->on_message(m.type, m.data, m.n, now);

    const uint32_t t0 = micros();
    face::Rect r = link->frame(now, fb);
    if (first) r = face::Rect{0, 0, face::SCREEN_W, face::SCREEN_H};  // whatever the panel showed
    first = false;
    display.push(fb, r);  // nothing to send when nothing changed
    frame_us = micros() - t0;
    n_frames = n_frames + 1;

    // Keep the frame period, but always give up at least one tick so the idle task (and its
    // watchdog) runs even when a frame overruns.
    if ((TickType_t)(xTaskGetTickCount() - wake) >= period) {
      vTaskDelay(1);
      wake = xTaskGetTickCount();
    } else {
      vTaskDelayUntil(&wake, period);
    }
  }
}

}  // namespace

bool begin() {
  if (link) return true;
  // 134 kB: PSRAM when the board has it, so Wi-Fi and the sensor buffers keep the internal RAM.
  // The SPI FIFO is fed by the CPU, so PSRAM has no DMA restriction here. +4 bytes: writePixels
  // reads whole 32-bit words and may read 2 bytes past the last pixel.
  const size_t bytes = FB_PIXELS * sizeof(uint16_t) + 4;
  fb = (uint16_t *)heap_caps_malloc(bytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (!fb) fb = (uint16_t *)heap_caps_malloc(bytes, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
  if (!fb) return false;
  memset(fb, 0, bytes);

  const face::St7789Config cfg = {MARVIN_TFT_SCK,         MARVIN_TFT_MOSI,       MARVIN_TFT_CS,
                                  MARVIN_TFT_DC,          MARVIN_TFT_RST,        MARVIN_TFT_SPI_HZ,
                                  MARVIN_TFT_COL_OFFSET,  MARVIN_TFT_ROW_OFFSET, MARVIN_TFT_MADCTL,
                                  MARVIN_TFT_INVERT != 0};
  if (!display.begin(cfg, MARVIN_TFT_SPI_BUS)) return false;

  queue = xQueueCreate(16, sizeof(Msg));
  link = new face::FaceLink(esp_random());
  if (!queue || !link) return false;
  // Core 0 (Wi-Fi runs there at a much higher priority); the Arduino loop keeps core 1.
  return xTaskCreatePinnedToCore(task, "face", 8192, nullptr, 1, nullptr, 0) == pdPASS;
}

void on_message(uint8_t type, const uint8_t *payload, size_t n) {
  if (!queue || (type != proto::FACE_STATE && type != proto::FACE_EVENT)) return;
  Msg m;
  m.type = type;
  m.n = n < sizeof(m.data) ? (uint8_t)n : (uint8_t)sizeof(m.data);
  memcpy(m.data, payload, m.n);
  xQueueSend(queue, &m, 0);  // queue full: drop (the next FACE_STATE follows within 100 ms)
}

uint32_t frames() { return n_frames; }
uint32_t last_frame_us() { return frame_us; }

}  // namespace face_app

#endif
