// SPDX-License-Identifier: MIT
//
// Minimal ST7789 driver for the 1.69" 240 x 280 IPS panel: init, then push a rectangle of an
// RGB565 framebuffer. No graphics library: the face renders into its own framebuffer
// (raster.h), so all the panel needs is a window command and a pixel stream.
//
// SPI through Arduino-ESP32's SPIClass::writePixels (CPU-fed FIFO, 64 bytes per burst, bytes
// swapped to MSB first on the fly), so the framebuffer can live in PSRAM with no DMA constraint.
// At 40 MHz a full frame takes about 30 ms and the usual changed area (the eyes) under 10 ms.
//
// ESP32 + Arduino only; the native build skips this file.
#pragma once

#if defined(ARDUINO) && defined(ESP32)

#include <Arduino.h>
#include <SPI.h>

#include "raster.h"

namespace face {

struct St7789Config {
  int8_t sck, mosi, cs, dc, rst;  // GPIO numbers; rst = -1 if not wired
  uint32_t spi_hz;
  uint16_t col_offset, row_offset;  // 240 x 280 panels sit at row 20 of the 240 x 320 controller
  uint8_t madctl;                   // memory access control: 0x00 = portrait, RGB order
  bool invert;                      // IPS panels need colour inversion on
};

class St7789 {
 public:
  bool begin(const St7789Config &cfg, uint8_t spi_bus);
  // Send `r` of `fb` (SCREEN_W x SCREEN_H, row-major, native RGB565). r.x0 must be even and `fb`
  // 4-byte aligned (writePixels reads 32-bit words).
  void push(const uint16_t *fb, Rect r);

 private:
  void command(uint8_t cmd, const uint8_t *data = nullptr, size_t n = 0);
  void window(int x0, int y0, int x1, int y1);

  SPIClass *spi_ = nullptr;
  St7789Config cfg_{};
};

}  // namespace face

#endif
