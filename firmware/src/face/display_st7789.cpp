// SPDX-License-Identifier: MIT
#include "display_st7789.h"

#if defined(ARDUINO) && defined(ESP32)

namespace face {

namespace {

enum : uint8_t {
  SWRESET = 0x01,
  SLPOUT = 0x11,
  NORON = 0x13,
  INVON = 0x21,
  DISPON = 0x29,
  CASET = 0x2A,
  RASET = 0x2B,
  RAMWR = 0x2C,
  MADCTL = 0x36,
  COLMOD = 0x3A,
};

}  // namespace

bool St7789::begin(const St7789Config &cfg, uint8_t spi_bus) {
  cfg_ = cfg;
  pinMode(cfg.cs, OUTPUT);
  digitalWrite(cfg.cs, HIGH);
  pinMode(cfg.dc, OUTPUT);
  digitalWrite(cfg.dc, HIGH);
  if (cfg.rst >= 0) {
    pinMode(cfg.rst, OUTPUT);
    digitalWrite(cfg.rst, HIGH);
    delay(5);
    digitalWrite(cfg.rst, LOW);
    delay(20);
    digitalWrite(cfg.rst, HIGH);
    delay(150);
  }
  spi_ = new SPIClass(spi_bus);
  if (!spi_) return false;
  spi_->begin(cfg.sck, -1, cfg.mosi, -1);

  command(SWRESET);
  delay(150);
  command(SLPOUT);
  delay(120);
  const uint8_t colmod = 0x55;  // 16 bits per pixel, RGB565
  command(COLMOD, &colmod, 1);
  command(MADCTL, &cfg.madctl, 1);
  if (cfg.invert) command(INVON);
  command(NORON);
  delay(10);
  command(DISPON);
  delay(20);
  return true;
}

void St7789::command(uint8_t cmd, const uint8_t *data, size_t n) {
  spi_->beginTransaction(SPISettings(cfg_.spi_hz, MSBFIRST, SPI_MODE0));
  digitalWrite(cfg_.cs, LOW);
  digitalWrite(cfg_.dc, LOW);
  spi_->write(cmd);
  digitalWrite(cfg_.dc, HIGH);
  if (n) spi_->writeBytes(data, n);
  digitalWrite(cfg_.cs, HIGH);
  spi_->endTransaction();
}

void St7789::window(int x0, int y0, int x1, int y1) {
  const uint16_t c0 = x0 + cfg_.col_offset, c1 = x1 - 1 + cfg_.col_offset;
  const uint16_t r0 = y0 + cfg_.row_offset, r1 = y1 - 1 + cfg_.row_offset;
  const uint8_t cols[4] = {(uint8_t)(c0 >> 8), (uint8_t)c0, (uint8_t)(c1 >> 8), (uint8_t)c1};
  const uint8_t rows[4] = {(uint8_t)(r0 >> 8), (uint8_t)r0, (uint8_t)(r1 >> 8), (uint8_t)r1};
  command(CASET, cols, 4);
  command(RASET, rows, 4);
}

void St7789::push(const uint16_t *fb, Rect r) {
  if (!spi_ || r.empty()) return;
  window(r.x0, r.y0, r.x1, r.y1);
  spi_->beginTransaction(SPISettings(cfg_.spi_hz, MSBFIRST, SPI_MODE0));
  digitalWrite(cfg_.cs, LOW);
  digitalWrite(cfg_.dc, LOW);
  spi_->write(RAMWR);
  digitalWrite(cfg_.dc, HIGH);
  if (r.x0 == 0 && r.x1 == SCREEN_W) {
    // Full rows are contiguous: one stream.
    spi_->writePixels(fb + r.y0 * SCREEN_W, (uint32_t)r.height() * SCREEN_W * 2);
  } else {
    for (int y = r.y0; y < r.y1; y++) spi_->writePixels(fb + y * SCREEN_W + r.x0, (uint32_t)r.width() * 2);
  }
  digitalWrite(cfg_.cs, HIGH);
  spi_->endTransaction();
}

}  // namespace face

#endif
