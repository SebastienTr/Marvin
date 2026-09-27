// Tests of the sensor stream framers and the LD2450 command builders: `pio test -e native`.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include <stdio.h>
#include <stdlib.h>

#include <vector>

#include "sensor_framing.h"
#include "sensors_sim.h"

using Bytes = std::vector<uint8_t>;
using framing::Ld2450Framer;
using framing::LidarFramer;

// A real LD19 packet captured from a sensor (CRC valid).
static const char *LD19_HEX =
    "542c6808ab7ee000e4dc00e2d900e5d500e3d300e4d000e9cd00e4ca00e2c700e9c500e5c200e5c000e5be823a1a50";

// Datasheet example, "HLK-LD2450 Serial Communication Protocol V1.03", section 2.3:
// one target at x = -782 mm, y = 1713 mm, speed -16 cm/s, resolution 320 mm.
static const uint8_t LD2450_EXAMPLE[30] = {0xAA, 0xFF, 0x03, 0x00, 0x0E, 0x03, 0xB1, 0x86, 0x10, 0x00,
                                           0x40, 0x01, 0, 0, 0, 0, 0, 0, 0, 0,
                                           0, 0, 0, 0, 0, 0, 0, 0, 0x55, 0xCC};
// Same document, section 2.2.1: ACK of "enable configuration" (protocol 1, buffer 0x40).
static const uint8_t ENABLE_ACK[18] = {0xFD, 0xFC, 0xFB, 0xFA, 0x08, 0x00, 0xFF, 0x01, 0x00,
                                       0x00, 0x01, 0x00, 0x40, 0x00, 0x04, 0x03, 0x02, 0x01};
// Section 2.2.4: ACK of "multi-target tracking".
static const uint8_t MULTI_ACK[14] = {0xFD, 0xFC, 0xFB, 0xFA, 0x04, 0x00, 0x90, 0x01,
                                      0x00, 0x00, 0x04, 0x03, 0x02, 0x01};

static Bytes hex(const char *s) {
  Bytes out;
  for (; s[0] && s[1]; s += 2) {
    char b[3] = {s[0], s[1], 0};
    out.push_back((uint8_t)strtoul(b, nullptr, 16));
  }
  return out;
}

static void append(Bytes &dst, const Bytes &src) { dst.insert(dst.end(), src.begin(), src.end()); }
static void append(Bytes &dst, const uint8_t *src, size_t n) { dst.insert(dst.end(), src, src + n); }

// Everything a framer delivers, in order.
struct Sink {
  std::vector<Bytes> lidar;
  std::vector<Bytes> targets;
  std::vector<Bytes> acks;
};

static void feed_lidar(LidarFramer &f, const Bytes &data, Sink &sink, size_t chunk = 0) {
  auto cb = [&](const uint8_t *p) { sink.lidar.push_back(Bytes(p, p + framing::LIDAR_PACKET)); };
  if (!chunk) chunk = data.size();
  for (size_t i = 0; i < data.size(); i += chunk)
    f.feed(data.data() + i, data.size() - i < chunk ? data.size() - i : chunk, cb);
}

static void feed_ld2450(Ld2450Framer &f, const Bytes &data, Sink &sink, size_t chunk = 0) {
  auto cb = [&](Ld2450Framer::Kind kind, const uint8_t *p, size_t n) {
    (kind == Ld2450Framer::TARGETS ? sink.targets : sink.acks).push_back(Bytes(p, p + n));
  };
  if (!chunk) chunk = data.size();
  for (size_t i = 0; i < data.size(); i += chunk)
    f.feed(data.data() + i, data.size() - i < chunk ? data.size() - i : chunk, cb);
}

static void assert_bytes(const Bytes &expected, const Bytes &actual) {
  TEST_ASSERT_EQUAL_size_t(expected.size(), actual.size());
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), actual.data(), expected.size());
}

void setUp() {}
void tearDown() {}

// ---- lidar ----------------------------------------------------------------------------------

static void test_crc_of_real_packet() {
  Bytes p = hex(LD19_HEX);
  TEST_ASSERT_EQUAL_size_t(47, p.size());
  TEST_ASSERT_EQUAL_HEX8(p[46], framing::ldrobot_crc8(p.data(), 46));
}

static void test_lidar_single_packet() {
  LidarFramer f;
  Sink s;
  Bytes p = hex(LD19_HEX);
  feed_lidar(f, p, s);
  TEST_ASSERT_EQUAL(1, s.lidar.size());
  assert_bytes(p, s.lidar[0]);
  TEST_ASSERT_EQUAL_UINT32(1, f.packets());
  TEST_ASSERT_EQUAL_UINT32(0, f.crc_errors());
  TEST_ASSERT_EQUAL_UINT32(0, f.dropped_bytes());
  TEST_ASSERT_EQUAL_size_t(0, f.pending());
}

static void test_lidar_garbage_between_and_partial() {
  Bytes p = hex(LD19_HEX), stream;
  const uint8_t junk[] = {0x00, 0x54, 0x13, 0x2C, 0x54, 0x54, 0xFF};
  append(stream, junk, sizeof(junk));                       // garbage, including lone header bytes
  append(stream, p);
  append(stream, p.data(), 20);                             // a packet cut short (UART started mid-packet)
  append(stream, p);
  append(stream, junk, sizeof(junk));
  append(stream, p);
  LidarFramer f;
  Sink s;
  feed_lidar(f, stream, s);
  TEST_ASSERT_EQUAL(3, s.lidar.size());
  for (const Bytes &got : s.lidar) assert_bytes(p, got);
  // the partial packet swallows the header of the next one: its CRC fails, then resync finds it
  TEST_ASSERT_EQUAL_UINT32(1, f.crc_errors());
  TEST_ASSERT_EQUAL_UINT32(2 * sizeof(junk) + 20, f.dropped_bytes());
}

static void test_lidar_split_in_every_chunk_size() {
  Bytes p = hex(LD19_HEX), stream;
  for (int i = 0; i < 4; i++) {
    stream.push_back(0xA5);
    append(stream, p);
  }
  for (size_t chunk = 1; chunk <= stream.size(); chunk++) {
    LidarFramer f;
    Sink s;
    feed_lidar(f, stream, s, chunk);
    TEST_ASSERT_EQUAL_MESSAGE(4, s.lidar.size(), "chunked feed lost packets");
    TEST_ASSERT_EQUAL_UINT32(4, f.dropped_bytes());
  }
}

static void test_lidar_corrupted_crc_rejected() {
  Bytes good = hex(LD19_HEX), bad = good, stream;
  bad[10] ^= 0x01;                                          // one bit flipped in a distance
  append(stream, bad);
  append(stream, good);
  Bytes wrong_crc = good;
  wrong_crc[46] ^= 0xFF;
  append(stream, wrong_crc);
  append(stream, good);
  LidarFramer f;
  Sink s;
  feed_lidar(f, stream, s);
  TEST_ASSERT_EQUAL(2, s.lidar.size());
  assert_bytes(good, s.lidar[0]);
  assert_bytes(good, s.lidar[1]);
  TEST_ASSERT_EQUAL_UINT32(2, f.crc_errors());
  TEST_ASSERT_EQUAL_UINT32(2 * 47, f.dropped_bytes());
}

static void test_lidar_fake_header_inside_garbage() {
  // A false 54 2C shortly before a real packet: the false candidate spans the real header and
  // fails its CRC; the real packet must still come out.
  Bytes p = hex(LD19_HEX), stream = {0x54, 0x2C, 0x01, 0x02, 0x03};
  append(stream, p);
  append(stream, p);
  LidarFramer f;
  Sink s;
  feed_lidar(f, stream, s);
  TEST_ASSERT_EQUAL(2, s.lidar.size());
  assert_bytes(p, s.lidar[0]);
  TEST_ASSERT_EQUAL_UINT32(1, f.crc_errors());
  TEST_ASSERT_EQUAL_UINT32(5, f.dropped_bytes());
}

static void test_lidar_sim_round_trip() {
  // Packets from the firmware's own simulator, with noise bursts, fed in odd-sized chunks.
  sim::begin(2);
  std::vector<Bytes> sent;
  Bytes stream;
  uint32_t rng = 1;
  auto rnd = [&rng]() { rng = rng * 1103515245u + 12345u; return (rng >> 16) & 0x7FFF; };
  size_t junk_bytes = 0;
  for (int i = 0; i < 500; i++) {
    Bytes p(sim::LIDAR_PACKET);
    sim::lidar_packet(i * 0.005f, p.data());
    sent.push_back(p);
    if (i % 7 == 3) {
      for (unsigned n = rnd() % 9 + 1; n; n--, junk_bytes++) stream.push_back(0x40 + rnd() % 16);
    }
    append(stream, p);
  }
  LidarFramer f;
  Sink s;
  auto cb = [&](const uint8_t *p) { s.lidar.push_back(Bytes(p, p + framing::LIDAR_PACKET)); };
  for (size_t i = 0; i < stream.size();) {
    size_t n = rnd() % 97 + 1;
    if (n > stream.size() - i) n = stream.size() - i;
    f.feed(stream.data() + i, n, cb);
    i += n;
  }
  TEST_ASSERT_EQUAL(sent.size(), s.lidar.size());
  for (size_t i = 0; i < sent.size(); i++) assert_bytes(sent[i], s.lidar[i]);
  TEST_ASSERT_EQUAL_UINT32(0, f.crc_errors());
  TEST_ASSERT_EQUAL_UINT32(junk_bytes, f.dropped_bytes());
}

// ---- LD2450 ---------------------------------------------------------------------------------

static int16_t ld2450_value(const uint8_t *p) {  // bit 15 set = positive
  uint16_t v = (uint16_t)(p[0] | (p[1] << 8));
  return (v & 0x8000) ? (int16_t)(v & 0x7FFF) : (int16_t)-(v & 0x7FFF);
}

static void test_ld2450_datasheet_frame() {
  Ld2450Framer f;
  Sink s;
  feed_ld2450(f, Bytes(LD2450_EXAMPLE, LD2450_EXAMPLE + 30), s);
  TEST_ASSERT_EQUAL(1, s.targets.size());
  TEST_ASSERT_EQUAL(0, s.acks.size());
  const uint8_t *t = s.targets[0].data() + 4;
  TEST_ASSERT_EQUAL_INT16(-782, ld2450_value(t));
  TEST_ASSERT_EQUAL_INT16(1713, ld2450_value(t + 2));
  TEST_ASSERT_EQUAL_INT16(-16, ld2450_value(t + 4));
  TEST_ASSERT_EQUAL_UINT32(1, f.frames());
}

static void test_ld2450_mixed_stream_with_acks_and_garbage() {
  Bytes frame(LD2450_EXAMPLE, LD2450_EXAMPLE + 30), stream;
  const uint8_t junk[] = {0x55, 0xCC, 0xAA, 0xFF, 0x01, 0xFD, 0xFC, 0x00};
  append(stream, frame.data() + 13, 17);                    // tail of a frame: start mid-stream
  append(stream, frame);
  append(stream, ENABLE_ACK, sizeof(ENABLE_ACK));
  append(stream, junk, sizeof(junk));
  append(stream, MULTI_ACK, sizeof(MULTI_ACK));
  append(stream, frame);
  append(stream, junk, sizeof(junk));
  append(stream, frame);
  for (size_t chunk = 1; chunk <= stream.size(); chunk++) {
    Ld2450Framer f;
    Sink s;
    feed_ld2450(f, stream, s, chunk);
    TEST_ASSERT_EQUAL(3, s.targets.size());
    TEST_ASSERT_EQUAL(2, s.acks.size());
    for (const Bytes &got : s.targets) assert_bytes(frame, got);
    assert_bytes(Bytes(ENABLE_ACK, ENABLE_ACK + sizeof(ENABLE_ACK)), s.acks[0]);
    assert_bytes(Bytes(MULTI_ACK, MULTI_ACK + sizeof(MULTI_ACK)), s.acks[1]);
    TEST_ASSERT_EQUAL_UINT32(3, f.frames());
    TEST_ASSERT_EQUAL_UINT32(2, f.acks());
  }
}

static void test_ld2450_bad_footer_rejected() {
  Bytes good(LD2450_EXAMPLE, LD2450_EXAMPLE + 30), bad = good, stream;
  bad[29] = 0x00;
  append(stream, bad);
  append(stream, good);
  Bytes bad_ack(MULTI_ACK, MULTI_ACK + sizeof(MULTI_ACK));
  bad_ack[4] = 0xF0;                                        // absurd in-frame length
  append(stream, bad_ack);
  append(stream, good);
  Ld2450Framer f;
  Sink s;
  feed_ld2450(f, stream, s);
  TEST_ASSERT_EQUAL(2, s.targets.size());
  TEST_ASSERT_EQUAL(0, s.acks.size());
  TEST_ASSERT_EQUAL_UINT32(2, f.bad_frames());
  TEST_ASSERT_EQUAL_UINT32(30 + sizeof(MULTI_ACK), f.dropped_bytes());
}

static void test_ld2450_sim_round_trip() {
  sim::begin(1);
  Ld2450Framer f;
  Sink s;
  std::vector<Bytes> sent;
  Bytes stream;
  for (int i = 0; i < 300; i++) {                           // 30 s of the simulated scene at 10 Hz
    Bytes p(sim::LD2450_FRAME);
    sim::ld2450_frame(i * 0.1f, p.data());
    sent.push_back(p);
    append(stream, p);
    if (i % 5 == 0) stream.push_back(0xAA);                 // stray header byte
  }
  feed_ld2450(f, stream, s, 13);
  TEST_ASSERT_EQUAL(sent.size(), s.targets.size());
  for (size_t i = 0; i < sent.size(); i++) assert_bytes(sent[i], s.targets[i]);
  TEST_ASSERT_EQUAL_UINT32(0, f.bad_frames());
  TEST_ASSERT_EQUAL_UINT32(60, f.dropped_bytes());
}

// ---- LD2450 commands ------------------------------------------------------------------------

static void test_ld2450_command_bytes() {
  namespace cmd = framing::ld2450_cmd;
  uint8_t out[cmd::MAX_SIZE];
  // "HLK-LD2450 Serial Communication Protocol V1.03", sections 2.2.1, 2.2.2 and 2.2.4
  const uint8_t enable[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x04, 0x00, 0xFF, 0x00, 0x01, 0x00, 0x04, 0x03, 0x02, 0x01};
  const uint8_t end[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x02, 0x00, 0xFE, 0x00, 0x04, 0x03, 0x02, 0x01};
  const uint8_t multi[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x02, 0x00, 0x90, 0x00, 0x04, 0x03, 0x02, 0x01};
  TEST_ASSERT_EQUAL_size_t(sizeof(enable), cmd::enable_config(out));
  TEST_ASSERT_EQUAL_HEX8_ARRAY(enable, out, sizeof(enable));
  TEST_ASSERT_EQUAL_size_t(sizeof(end), cmd::end_config(out));
  TEST_ASSERT_EQUAL_HEX8_ARRAY(end, out, sizeof(end));
  TEST_ASSERT_EQUAL_size_t(sizeof(multi), cmd::multi_target(out));
  TEST_ASSERT_EQUAL_HEX8_ARRAY(multi, out, sizeof(multi));
}

static void test_ld2450_parse_ack() {
  namespace cmd = framing::ld2450_cmd;
  uint16_t word = 0, status = 0xFFFF;
  TEST_ASSERT_TRUE(cmd::parse_ack(ENABLE_ACK, sizeof(ENABLE_ACK), &word, &status));
  TEST_ASSERT_EQUAL_HEX16(cmd::ENABLE_CONFIG, word);
  TEST_ASSERT_EQUAL_HEX16(0, status);
  TEST_ASSERT_TRUE(cmd::parse_ack(MULTI_ACK, sizeof(MULTI_ACK), &word, &status));
  TEST_ASSERT_EQUAL_HEX16(cmd::MULTI_TARGET, word);
  uint8_t failed[sizeof(MULTI_ACK)];
  memcpy(failed, MULTI_ACK, sizeof(failed));
  failed[8] = 0x01;                                         // status 1 = failure
  TEST_ASSERT_TRUE(cmd::parse_ack(failed, sizeof(failed), &word, &status));
  TEST_ASSERT_EQUAL_HEX16(1, status);
  // a command we sent is not an ACK (no 0x0100 bit)
  uint8_t sent[cmd::MAX_SIZE];
  size_t n = cmd::end_config(sent);
  TEST_ASSERT_FALSE(cmd::parse_ack(sent, n, &word, &status));
  TEST_ASSERT_FALSE(cmd::parse_ack(LD2450_EXAMPLE, sizeof(LD2450_EXAMPLE), &word, &status));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_crc_of_real_packet);
  RUN_TEST(test_lidar_single_packet);
  RUN_TEST(test_lidar_garbage_between_and_partial);
  RUN_TEST(test_lidar_split_in_every_chunk_size);
  RUN_TEST(test_lidar_corrupted_crc_rejected);
  RUN_TEST(test_lidar_fake_header_inside_garbage);
  RUN_TEST(test_lidar_sim_round_trip);
  RUN_TEST(test_ld2450_datasheet_frame);
  RUN_TEST(test_ld2450_mixed_stream_with_acks_and_garbage);
  RUN_TEST(test_ld2450_bad_footer_rejected);
  RUN_TEST(test_ld2450_sim_round_trip);
  RUN_TEST(test_ld2450_command_bytes);
  RUN_TEST(test_ld2450_parse_ack);
  return UNITY_END();
}
