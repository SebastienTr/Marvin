// Tests of the screen's orientation flags (face/panel.h). `pio test -e native -f test_panel`.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include "face/panel.h"
#include "face/raster.h"

using namespace face;

void setUp() {}
void tearDown() {}

static void test_upright_is_the_old_default() {
  PanelOrientation o;
  TEST_ASSERT_EQUAL_HEX8(0x00, panel_madctl(o));  // the driver's default before these flags
  uint16_t c, r;
  panel_offsets(o, SCREEN_W, SCREEN_H, 0, 20, &c, &r);
  TEST_ASSERT_EQUAL_UINT16(0, c);
  TEST_ASSERT_EQUAL_UINT16(20, r);
}

static void test_upside_down() {
  PanelOrientation o;
  o.rotate_180 = true;
  TEST_ASSERT_EQUAL_HEX8(MADCTL_MX | MADCTL_MY, panel_madctl(o));
  uint16_t c, r;
  panel_offsets(o, SCREEN_W, SCREEN_H, 0, 20, &c, &r);
  TEST_ASSERT_EQUAL_UINT16(0, c);    // 240 - 240 - 0
  TEST_ASSERT_EQUAL_UINT16(20, r);   // 320 - 280 - 20: a centred glass stays at 20
  panel_offsets(o, SCREEN_W, SCREEN_H, 0, 0, &c, &r);
  TEST_ASSERT_EQUAL_UINT16(40, r);   // glass at the top of the RAM: at the bottom once flipped
}

static void test_mirrors() {
  PanelOrientation x;
  x.mirror_x = true;
  TEST_ASSERT_EQUAL_HEX8(MADCTL_MX, panel_madctl(x));
  PanelOrientation y;
  y.mirror_y = true;
  TEST_ASSERT_EQUAL_HEX8(MADCTL_MY, panel_madctl(y));
  // upside down and mirrored left-right: only the rows end up flipped
  PanelOrientation both;
  both.rotate_180 = true;
  both.mirror_x = true;
  TEST_ASSERT_EQUAL_HEX8(MADCTL_MY, panel_madctl(both));
  uint16_t c, r;
  panel_offsets(both, SCREEN_W, SCREEN_H, 0, 0, &c, &r);
  TEST_ASSERT_EQUAL_UINT16(0, c);
  TEST_ASSERT_EQUAL_UINT16(40, r);
}

static void test_bgr() {
  PanelOrientation o;
  o.bgr = true;
  TEST_ASSERT_EQUAL_HEX8(MADCTL_BGR, panel_madctl(o));
  o.rotate_180 = true;
  TEST_ASSERT_EQUAL_HEX8(MADCTL_BGR | MADCTL_MX | MADCTL_MY, panel_madctl(o));
}

static void test_offsets_never_negative() {
  PanelOrientation o;
  o.mirror_y = true;
  uint16_t c, r;
  panel_offsets(o, SCREEN_W, SCREEN_H, 0, 60, &c, &r);  // a wrong offset: clamped, not wrapped to 65 516
  TEST_ASSERT_EQUAL_UINT16(0, r);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_upright_is_the_old_default);
  RUN_TEST(test_upside_down);
  RUN_TEST(test_mirrors);
  RUN_TEST(test_bgr);
  RUN_TEST(test_offsets_never_negative);
  return UNITY_END();
}
