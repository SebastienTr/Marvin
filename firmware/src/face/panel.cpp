// SPDX-License-Identifier: MIT
#include "panel.h"

namespace face {

namespace {

bool flip_x(const PanelOrientation &o) { return o.rotate_180 != o.mirror_x; }
bool flip_y(const PanelOrientation &o) { return o.rotate_180 != o.mirror_y; }

}  // namespace

uint8_t panel_madctl(const PanelOrientation &o) {
  uint8_t m = 0;
  if (flip_y(o)) m |= MADCTL_MY;
  if (flip_x(o)) m |= MADCTL_MX;
  if (o.bgr) m |= MADCTL_BGR;
  return m;
}

void panel_offsets(const PanelOrientation &o, uint16_t width, uint16_t height, uint16_t col, uint16_t row,
                   uint16_t *col_out, uint16_t *row_out) {
  const int c = flip_x(o) ? (int)ST7789_RAM_W - width - col : col;
  const int r = flip_y(o) ? (int)ST7789_RAM_H - height - row : row;
  *col_out = (uint16_t)(c < 0 ? 0 : c);
  *row_out = (uint16_t)(r < 0 ? 0 : r);
}

}  // namespace face
