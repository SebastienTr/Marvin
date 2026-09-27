// SPDX-License-Identifier: MIT
//
// The C++ face against the Python reference (host/marvin_host/face.py), natively:
//   cd firmware && pio test -e native -f test_face
//
// golden_face.h and golden_frames.bin come from host/scripts/face_golden.py: a scripted scenario,
// the CRC-32 of every frame, and a few complete frames for a per-pixel comparison.
#include <unity.h>

#include <chrono>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <vector>

#include "face/face.h"
#include "face/face_link.h"
#include "golden_face.h"
#include "protocol.h"

using namespace face;

namespace {

constexpr int N_PIXELS = SCREEN_W * SCREEN_H;

// Tolerance for float rounding: at most 0.5 % of the pixels may differ, by at most 2 LSB per
// RGB565 channel.
constexpr double MAX_DIFF_FRACTION = 0.005;
constexpr int MAX_CHANNEL_DIFF = 2;

uint32_t crc32(const uint16_t *px, size_t n) {
  uint32_t crc = 0xFFFFFFFFu;
  for (size_t i = 0; i < n; i++) {
    const uint8_t bytes[2] = {(uint8_t)(px[i] & 0xFF), (uint8_t)(px[i] >> 8)};
    for (uint8_t b : bytes) {
      crc ^= b;
      for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
  }
  return ~crc;
}

std::string test_dir() {
  std::string f = __FILE__;
  size_t slash = f.find_last_of("/\\");
  return slash == std::string::npos ? std::string(".") : f.substr(0, slash);
}

// golden_frames.bin: per frame, u32 run count then (u16 length, u16 RGB565) runs.
std::vector<std::vector<uint16_t>> load_full_frames() {
  std::vector<std::vector<uint16_t>> frames;
  std::string path = test_dir() + "/golden_frames.bin";
  FILE *f = fopen(path.c_str(), "rb");
  if (!f) return frames;
  std::vector<uint8_t> data;
  uint8_t chunk[4096];
  size_t n;
  while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0) data.insert(data.end(), chunk, chunk + n);
  fclose(f);
  size_t pos = 0;
  auto u16 = [&](size_t at) { return (uint16_t)(data[at] | (data[at + 1] << 8)); };
  while (pos + 4 <= data.size()) {
    uint32_t runs = data[pos] | (data[pos + 1] << 8) | (data[pos + 2] << 16) | ((uint32_t)data[pos + 3] << 24);
    pos += 4;
    std::vector<uint16_t> img;
    img.reserve(N_PIXELS);
    for (uint32_t r = 0; r < runs && pos + 4 <= data.size(); r++, pos += 4) img.insert(img.end(), u16(pos), u16(pos + 2));
    frames.push_back(img);
  }
  return frames;
}

struct Diff {
  int pixels = 0;       // pixels that differ at all
  int max_channel = 0;  // largest per-channel difference, in RGB565 LSB
};

Diff compare(const uint16_t *a, const uint16_t *b) {
  Diff d;
  for (int i = 0; i < N_PIXELS; i++) {
    if (a[i] == b[i]) continue;
    d.pixels++;
    int dr = abs((a[i] >> 11) - (b[i] >> 11));
    int dg = abs(((a[i] >> 5) & 63) - ((b[i] >> 5) & 63));
    int db = abs((a[i] & 31) - (b[i] & 31));
    int m = dr > dg ? dr : dg;
    m = m > db ? m : db;
    if (m > d.max_channel) d.max_channel = m;
  }
  return d;
}

uint16_t fb[N_PIXELS];

}  // namespace

// Unity's double assertions are disabled in the native env; compare doubles by hand.
#define NEAR(expected, actual) TEST_ASSERT_TRUE(fabs((double)(expected) - (double)(actual)) < 1e-9)

void setUp() {}
void tearDown() {}

static void test_xorshift_matches_python() {
  XorShift32 r(7);
  TEST_ASSERT_EQUAL_HEX32(0x8938bb14, r.next_u32());
  TEST_ASSERT_EQUAL_HEX32(0x5514f319, r.next_u32());
  TEST_ASSERT_EQUAL_HEX32(0xa58162e2, r.next_u32());
  TEST_ASSERT_EQUAL_HEX32(0x9e3779b9, XorShift32(0).state());
}

static void test_palette() {
  TEST_ASSERT_EQUAL_HEX16(0x1082, rgb565(BACKGROUND));
  TEST_ASSERT_EQUAL_HEX16(0xEF3B, rgb565(EYE_WHITE));
  TEST_ASSERT_EQUAL_HEX16(0xFDEF, rgb565(EYE_AMBER));
  TEST_ASSERT_EQUAL_HEX16(0xEBA4, rgb565(ACCENT));
}

static void test_gaze_signs() {
  double gx, gy;
  const double right[3] = {500.0, -1000.0, 92.0};
  gaze_from_point(right, &gx, &gy);
  TEST_ASSERT_TRUE(gx > 0);
  NEAR(0.0, gy);
  const double above[3] = {0.0, -1000.0, 600.0};
  gaze_from_point(above, &gx, &gy);
  TEST_ASSERT_TRUE(gy < 0);
}

// Replays the golden scenario; fast = the ESP32 distance maths (Raster::set_fast_math).
static void run_parity(bool fast) {
  const auto full = load_full_frames();
  const size_t n_full = sizeof(golden::FULL_STEPS) / sizeof(golden::FULL_STEPS[0]);
  TEST_ASSERT_EQUAL_MESSAGE(n_full, full.size(), "golden_frames.bin missing or incomplete");
  const char *mode = fast ? "fast maths" : "exact maths";

  Face f(golden::SEED);
  f.raster().set_fast_math(fast);
  memset(fb, 0, sizeof(fb));
  const size_t n_steps = sizeof(golden::STEPS) / sizeof(golden::STEPS[0]);
  size_t crc_ok = 0, next_full = 0;
  int worst_pixels = 0, worst_channel = 0;
  double render_s = 0;
  for (size_t i = 0; i < n_steps; i++) {
    const golden::Step &s = golden::STEPS[i];
    for (uint8_t code : s.events)
      if (code) f.on_event((Event)code);
    auto t0 = std::chrono::steady_clock::now();
    f.update_and_render(golden::STATES[s.state], s.t_ms / 1000.0, fb);
    render_s += std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    if (crc32(fb, N_PIXELS) == s.crc) crc_ok++;
    if (next_full < n_full && golden::FULL_STEPS[next_full] == i) {
      TEST_ASSERT_EQUAL(N_PIXELS, full[next_full].size());
      Diff d = compare(fb, full[next_full].data());
      char msg[200];
      snprintf(msg, sizeof(msg), "%s, full frame %u (step %u, t %.3f s, %s): %d pixels differ (%.4f %%), max %d LSB",
               mode, (unsigned)next_full, (unsigned)i, s.t_ms / 1000.0, expr_name(f.expression()), d.pixels,
               100.0 * d.pixels / N_PIXELS, d.max_channel);
      TEST_MESSAGE(msg);
      TEST_ASSERT_TRUE_MESSAGE(d.pixels <= MAX_DIFF_FRACTION * N_PIXELS, msg);
      TEST_ASSERT_TRUE_MESSAGE(d.max_channel <= MAX_CHANNEL_DIFF, msg);
      if (d.pixels > worst_pixels) worst_pixels = d.pixels;
      if (d.max_channel > worst_channel) worst_channel = d.max_channel;
      next_full++;
    }
  }
  char msg[240];
  snprintf(msg, sizeof(msg),
           "%s: %u / %u frames bit-exact (CRC); full frames: worst %d pixels differ (%.4f %%), max %d LSB; "
           "render %.3f ms/frame (native)",
           mode, (unsigned)crc_ok, (unsigned)n_steps, worst_pixels, 100.0 * worst_pixels / N_PIXELS, worst_channel,
           1000.0 * render_s / n_steps);
  TEST_MESSAGE(msg);
  // Exact maths: the trajectory must not diverge, nearly every frame is expected to be bit-exact.
  if (!fast) TEST_ASSERT_TRUE_MESSAGE(crc_ok >= n_steps * 98 / 100, msg);
}

static void test_matches_python() { run_parity(false); }
static void test_matches_python_fast_maths() { run_parity(true); }

static void test_dirty_rect() {
  Face f(1);
  Presence nobody;
  static uint16_t buf[N_PIXELS];
  memset(buf, 0, sizeof(buf));
  Rect first = f.update_and_render(nobody, 0.0, buf);
  TEST_ASSERT_EQUAL(0, first.x0);
  TEST_ASSERT_EQUAL(SCREEN_W, first.x1);
  TEST_ASSERT_EQUAL(SCREEN_H, first.y1);
  f.raster().render(buf);                          // same display list again: nothing changes
  TEST_ASSERT_TRUE(f.raster().render(buf).empty());
  Rect later = f.update_and_render(nobody, 1.0, buf);  // asleep, breathing: only the eye lines
  TEST_ASSERT_FALSE(later.empty());
  TEST_ASSERT_TRUE(later.height() < 40);
  TEST_ASSERT_EQUAL(0, later.x0 % 2);
}

static void test_decode_state() {
  // protocol.FaceState(True, True, (-150, -800, 550), (-150.4, -800, 16), 0.81, 72.5).encode()
  const uint8_t payload[] = {0x3f, 0x6a, 0xff, 0xe0, 0xfc, 0x26, 0x02, 0x6a, 0xff,
                             0xe0, 0xfc, 0x10, 0x00, 0x2a, 0x03, 0x52, 0x1c};
  TEST_ASSERT_EQUAL(proto::FACE_STATE_SIZE, sizeof(payload));
  Presence s;
  TEST_ASSERT_TRUE(decode_state(payload, sizeof(payload), s));
  TEST_ASSERT_TRUE(s.present && s.seated && s.has_head && s.has_position && s.has_distance);
  NEAR(-150.0, s.head[0]);
  NEAR(-800.0, s.head[1]);
  NEAR(550.0, s.head[2]);
  NEAR(-150.0, s.position[0]);
  NEAR(16.0, s.position[2]);
  NEAR(0.81, s.distance_m);
  NEAR(72.5, s.heart_rate);
  TEST_ASSERT_FALSE(decode_state(payload, sizeof(payload) - 1, s));

  // protocol.FaceState(True).encode(): present, nothing else known
  const uint8_t bare[17] = {0x01};
  TEST_ASSERT_TRUE(decode_state(bare, sizeof(bare), s));
  TEST_ASSERT_TRUE(s.present);
  TEST_ASSERT_FALSE(s.seated || s.has_head || s.has_position || s.has_distance);
  NEAR(0.0, s.heart_rate);
}

static void test_decode_event() {
  Event ev;
  const uint8_t codes[] = {0, 1, 8, 9};
  TEST_ASSERT_FALSE(decode_event(codes, 1, ev));
  TEST_ASSERT_TRUE(decode_event(codes + 1, 1, ev));
  TEST_ASSERT_TRUE(ev == Event::ARRIVED);
  TEST_ASSERT_TRUE(decode_event(codes + 2, 1, ev));
  TEST_ASSERT_TRUE(ev == Event::VITALS_LOST);
  TEST_ASSERT_FALSE(decode_event(codes + 3, 1, ev));
  TEST_ASSERT_FALSE(decode_event(codes, 0, ev));
}

static void test_link_autonomy() {
  FaceLink link(3);
  static uint16_t buf[N_PIXELS];
  // protocol.FaceState(True, True, (0, -800, 400), (0, -800, 16), 0.8).encode()
  uint8_t seated[17] = {0x1f};
  auto put = [&](int at, int16_t v) { seated[at] = (uint8_t)v; seated[at + 1] = (uint8_t)((uint16_t)v >> 8); };
  put(3, -800); put(5, 400); put(9, -800); put(11, 16); put(13, 800);
  const uint8_t arrived = 1;

  uint32_t ms = 4000000000u;  // close to the 32-bit wrap: the face clock must not care
  TEST_ASSERT_TRUE(link.on_message(proto::FACE_EVENT, &arrived, 1, ms));
  TEST_ASSERT_FALSE(link.on_message(proto::HOST_ACK, &arrived, 1, ms));
  for (int i = 0; i < 90; i++, ms += 33) {    // 3 s of host messages at 30 fps
    if (i % 3 == 0) TEST_ASSERT_TRUE(link.on_message(proto::FACE_STATE, seated, sizeof(seated), ms));
    link.frame(ms, buf);
  }
  TEST_ASSERT_TRUE(link.host_alive(ms));
  TEST_ASSERT_EQUAL_STRING("calm", expr_name(link.face().expression()));

  // The host goes quiet: nobody is assumed after 5 s, sleepy 4 s later, asleep after 20 s.
  const uint32_t silent = ms;
  Expr at_4s = Expr::COUNT, at_10s = Expr::COUNT, at_30s = Expr::COUNT;
  uint32_t crc_prev = 0;
  int changes_asleep = 0;
  for (; ms - silent < 32000; ms += 33) {
    link.frame(ms, buf);
    uint32_t dt = ms - silent;
    if (dt < 4033 && dt >= 4000) at_4s = link.face().expression();
    if (dt < 10033 && dt >= 10000) at_10s = link.face().expression();
    if (dt < 30033 && dt >= 30000) at_30s = link.face().expression();
    if (dt > 28000) {
      uint32_t c = crc32(buf, N_PIXELS);
      changes_asleep += c != crc_prev;
      crc_prev = c;
    }
  }
  TEST_ASSERT_FALSE(link.host_alive(ms));
  TEST_ASSERT_EQUAL_STRING("calm", expr_name(at_4s));
  TEST_ASSERT_EQUAL_STRING("sleepy", expr_name(at_10s));
  TEST_ASSERT_EQUAL_STRING("asleep", expr_name(at_30s));
  TEST_ASSERT_TRUE_MESSAGE(changes_asleep > 10, "asleep face must keep breathing, not freeze");

  // The host comes back: the face wakes up.
  for (int i = 0; i < 30; i++, ms += 33) {
    link.on_message(proto::FACE_STATE, seated, sizeof(seated), ms);
    link.frame(ms, buf);
  }
  TEST_ASSERT_EQUAL_STRING("awake", expr_name(link.face().expression()));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_xorshift_matches_python);
  RUN_TEST(test_palette);
  RUN_TEST(test_gaze_signs);
  RUN_TEST(test_matches_python);
  RUN_TEST(test_matches_python_fast_maths);
  RUN_TEST(test_dirty_rect);
  RUN_TEST(test_decode_state);
  RUN_TEST(test_decode_event);
  RUN_TEST(test_link_autonomy);
  return UNITY_END();
}
