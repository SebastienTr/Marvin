// SPDX-License-Identifier: MIT
#include <unity.h>

#include "protocol.h"

void setUp() {}
void tearDown() {}

static void test_header_layout() {
  uint8_t buf[32];
  uint8_t *p = proto::header(buf, proto::HELLO, 7, 99);
  TEST_ASSERT_EQUAL(16, p - buf);
  TEST_ASSERT_TRUE(proto::valid(buf, 16));
  TEST_ASSERT_EQUAL_UINT8(proto::HELLO, buf[3]);
}

// BUTTON and HELLO as in host-java/marvin-contracts/golden/protocol/vectors.json (button_talk_long_press,
// hello_xiao_robot_knob): the v1 contract C1.
static void test_button_long_press_vector() {
  uint8_t p[proto::BUTTON_SIZE];
  TEST_ASSERT_EQUAL(2, proto::button(p, proto::BUTTON_TALK, proto::BUTTON_LONG_PRESS));
  TEST_ASSERT_EQUAL_UINT8(0x01, p[0]);
  TEST_ASSERT_EQUAL_UINT8(0x02, p[1]);
  TEST_ASSERT_EQUAL(2, proto::button(p, proto::BUTTON_TALK, proto::BUTTON_PRESS));
  TEST_ASSERT_EQUAL_UINT8(0x01, p[1]);
  TEST_ASSERT_EQUAL_UINT32(1500, proto::BUTTON_LONG_PRESS_MS);
  TEST_ASSERT_EQUAL_UINT8(0x0e, proto::FLAG_CAMERA | proto::FLAG_AUDIO | proto::FLAG_BUTTONS);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_header_layout);
  RUN_TEST(test_button_long_press_vector);
  return UNITY_END();
}
