// SPDX-License-Identifier: MIT
//
// How the 1.69" ST7789 module sits in the head: upside down, or seen through a mirror, is set at
// build time, not by editing the driver. The face is always drawn upright in portrait (240 x 280);
// the panel's memory access control (MADCTL) flips the scan, and the window offsets follow the
// visible area to the other end of the controller's 240 x 320 RAM.
//
//   MARVIN_TFT_ROTATE_180  1: the module is mounted upside down (FPC on top)
//   MARVIN_TFT_MIRROR_X    1: the face shows mirrored left-right
//   MARVIN_TFT_MIRROR_Y    1: the face shows mirrored top-bottom
//   MARVIN_TFT_BGR         1: red and blue are swapped (the colour test shows blue first)
//   MARVIN_TFT_COL_OFFSET, MARVIN_TFT_ROW_OFFSET: where the 240 x 280 glass sits in the RAM when
//   nothing is flipped (0, 20 on most modules); a face cut off at one edge with garbage at the other
//   means another offset.
//   MARVIN_TFT_MADCTL      a raw MADCTL byte instead of all of the above flips (offsets used as given)
//
// Portable C++ (no Arduino), unit-tested natively (test_panel).
#pragma once

#include <stdint.h>

namespace face {

// ST7789 MADCTL bits.
constexpr uint8_t MADCTL_MY = 0x80;   // rows bottom to top
constexpr uint8_t MADCTL_MX = 0x40;   // columns right to left
constexpr uint8_t MADCTL_MV = 0x20;   // rows and columns exchanged (landscape): not used, the face is portrait
constexpr uint8_t MADCTL_BGR = 0x08;  // blue-green-red order

// The controller's RAM: 240 columns, 320 rows.
constexpr uint16_t ST7789_RAM_W = 240;
constexpr uint16_t ST7789_RAM_H = 320;

struct PanelOrientation {
  bool rotate_180 = false;
  bool mirror_x = false;
  bool mirror_y = false;
  bool bgr = false;
};

// The MADCTL byte: a half turn flips both axes, a mirror one more time.
uint8_t panel_madctl(const PanelOrientation &o);

// The window offsets for a width x height panel whose glass sits at (col, row) of the RAM when
// nothing is flipped: an axis scanned backwards moves the visible area to the other end of the RAM.
void panel_offsets(const PanelOrientation &o, uint16_t width, uint16_t height, uint16_t col, uint16_t row,
                   uint16_t *col_out, uint16_t *row_out);

}  // namespace face
