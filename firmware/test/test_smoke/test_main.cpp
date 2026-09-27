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

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_header_layout);
  return UNITY_END();
}
