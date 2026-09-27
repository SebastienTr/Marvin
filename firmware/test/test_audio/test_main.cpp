// Tests of the portable audio and camera pieces: `pio test -e native`.
// Jitter buffer, earcons, audio packet payloads, DSP helpers, and the camera server's HTTP layer.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include <stdlib.h>
#include <string.h>

#include <string>
#include <vector>

#include "audio/audio_dsp.h"
#include "audio/audio_packets.h"
#include "audio/earcons.h"
#include "audio/jitter_buffer.h"
#include "camera/http_request.h"
#include "protocol.h"

using audio::JitterBuffer;

void setUp() {}
void tearDown() {}

// A ramp so every sample says where it came from: sample i of the stream is value (i + 1).
static std::vector<int16_t> ramp(uint32_t first_index, size_t n) {
  std::vector<int16_t> v(n);
  for (size_t i = 0; i < n; i++) v[i] = (int16_t)(first_index + i + 1);
  return v;
}

struct Jb {
  std::vector<int16_t> storage;
  JitterBuffer jb;
  explicit Jb(size_t cap = 4000, JitterBuffer::Config cfg = JitterBuffer::Config())
      : storage(cap), jb(storage.data(), cap, cfg) {}
  void push(uint16_t stream, uint32_t index, size_t n, uint32_t ms) {
    auto v = ramp(index, n);
    jb.push(stream, index, v.data(), n, ms);
  }
  std::vector<int16_t> pull(size_t n, uint32_t ms, size_t *got = nullptr) {
    std::vector<int16_t> out(n, 12345);
    size_t g = jb.pull(out.data(), n, ms);
    if (got) *got = g;
    return out;
  }
};

// ---- Jitter buffer ----------------------------------------------------------------------------

static void test_jitter_prebuffers_then_plays_in_order() {
  Jb b;
  size_t got;
  b.push(1, 0, 320, 0);
  b.pull(160, 1, &got);
  TEST_ASSERT_EQUAL(0, got);  // 320 < 1600 prebuffer, stream still arriving: silence
  for (uint32_t i = 1; i < 5; i++) b.push(1, i * 320, 320, i);
  TEST_ASSERT_EQUAL(1600, b.jb.level());
  auto out = b.pull(1600, 5, &got);
  TEST_ASSERT_EQUAL(1600, got);
  for (size_t i = 0; i < 1600; i++) TEST_ASSERT_EQUAL_INT16(i + 1, out[i]);
  TEST_ASSERT_EQUAL(0, b.jb.counters().underruns);
}

static void test_jitter_plays_short_tail_after_drain_timeout() {
  Jb b;
  size_t got;
  b.push(1, 0, 400, 0);
  b.pull(160, 30, &got);
  TEST_ASSERT_EQUAL(0, got);
  auto out = b.pull(500, 70, &got);  // nothing new for 70 ms > 60: play what is there
  TEST_ASSERT_EQUAL(400, got);
  TEST_ASSERT_EQUAL_INT16(1, out[0]);
  TEST_ASSERT_EQUAL_INT16(400, out[399]);
  TEST_ASSERT_EQUAL_INT16(0, out[400]);  // silence after the end
  TEST_ASSERT_EQUAL(0, b.jb.counters().underruns);  // the stream had ended: not an underrun
}

static void test_jitter_fills_lost_datagram_with_silence() {
  Jb b;
  b.push(1, 0, 320, 0);
  b.push(1, 640, 320, 1);  // 320..639 lost
  for (uint32_t i = 3; i < 6; i++) b.push(1, i * 320, 320, 2);
  TEST_ASSERT_EQUAL(320, b.jb.counters().lost_samples);
  auto out = b.pull(1920, 3);
  TEST_ASSERT_EQUAL_INT16(320, out[319]);
  TEST_ASSERT_EQUAL_INT16(0, out[320]);
  TEST_ASSERT_EQUAL_INT16(0, out[639]);
  TEST_ASSERT_EQUAL_INT16(641, out[640]);  // back in time
}

static void test_jitter_drops_late_and_trims_overlap() {
  Jb b;
  b.push(1, 0, 320, 0);
  b.push(1, 320, 320, 0);
  b.push(1, 0, 320, 0);    // duplicate
  b.push(1, 480, 320, 0);  // overlaps 480..639: only 640..799 kept
  TEST_ASSERT_EQUAL(320 + 160, b.jb.counters().late_samples);
  TEST_ASSERT_EQUAL(800, b.jb.level());
  auto out = b.pull(800, 100);
  for (size_t i = 0; i < 800; i++) TEST_ASSERT_EQUAL_INT16(i + 1, out[i]);
}

static void test_jitter_large_gap_resyncs() {
  Jb b;
  b.push(1, 0, 320, 0);
  b.push(1, 100000, 320, 0);  // far beyond max_gap: no silence inserted
  TEST_ASSERT_EQUAL(1, b.jb.counters().resyncs);
  TEST_ASSERT_EQUAL(640, b.jb.level());
}

static void test_jitter_index_wraps() {
  Jb b;
  b.push(1, 0xFFFFFF00u, 256, 0);
  b.push(1, 0, 256, 0);
  TEST_ASSERT_EQUAL(0, b.jb.counters().lost_samples);
  TEST_ASSERT_EQUAL(0, b.jb.counters().late_samples);
  TEST_ASSERT_EQUAL(512, b.jb.level());
}

static void test_jitter_new_stream_flushes_old() {
  Jb b;
  b.push(1, 0, 1000, 0);
  b.push(2, 5000, 320, 0);
  TEST_ASSERT_EQUAL(320, b.jb.level());
  TEST_ASSERT_EQUAL(2, b.jb.counters().streams);
  auto out = b.pull(320, 100);
  TEST_ASSERT_EQUAL_INT16(5001, out[0]);
}

static void test_jitter_stop_ignores_stopped_stream() {
  Jb b;
  b.push(7, 0, 2000, 0);
  b.pull(100, 1);
  b.jb.stop();
  TEST_ASSERT_EQUAL(0, b.jb.level());
  b.push(7, 2000, 320, 2);  // still in flight when the host stopped: ignored
  TEST_ASSERT_EQUAL(0, b.jb.level());
  TEST_ASSERT_EQUAL(1, b.jb.counters().stale_datagrams);
  b.push(8, 0, 320, 3);  // the next stream plays
  TEST_ASSERT_EQUAL(320, b.jb.level());
}

static void test_jitter_overflow_drops_newest() {
  Jb b(1000);
  b.push(1, 0, 480, 0);
  b.push(1, 480, 480, 0);
  b.push(1, 960, 480, 0);
  TEST_ASSERT_EQUAL(1000, b.jb.level());
  TEST_ASSERT_EQUAL(440, b.jb.counters().overflow_samples);
  auto out = b.pull(1000, 100);
  TEST_ASSERT_EQUAL_INT16(1000, out[999]);
}

static void test_jitter_underrun_rebuffers() {
  Jb b;
  for (uint32_t i = 0; i < 5; i++) b.push(1, i * 320, 320, 0);
  size_t got;
  b.pull(1600, 10, &got);
  TEST_ASSERT_EQUAL(1600, got);
  b.push(1, 1600, 320, 20);
  b.pull(640, 30, &got);  // only 320 there while the stream is arriving
  TEST_ASSERT_EQUAL(320, got);
  TEST_ASSERT_EQUAL(1, b.jb.counters().underruns);
  TEST_ASSERT_FALSE(b.jb.playing());
  b.push(1, 1920, 320, 40);
  b.pull(160, 41, &got);
  TEST_ASSERT_EQUAL(0, got);  // prebuffering again
}

static void test_jitter_ring_wraps_around() {
  Jb b(1000);
  uint32_t index = 0, played = 0;
  for (int round = 0; round < 20; round++) {
    b.push(1, index, 300, round * 10);
    index += 300;
    std::vector<int16_t> out(300);
    size_t got = b.jb.pull(out.data(), 300, round * 10 + 100);
    for (size_t i = 0; i < got; i++) TEST_ASSERT_EQUAL_INT16(played + i + 1, out[i]);
    played += got;
  }
  TEST_ASSERT_EQUAL(index, played + b.jb.level());
}

// ---- Earcons ----------------------------------------------------------------------------------

static void test_earcon_durations_and_ids() {
  // One table on both sides: docs/audio.md and host/marvin_host/protocol.py SOUNDS.
  const uint32_t expected[] = {0, 140, 120, 200, 200, 360, 430};
  for (uint8_t id = 1; id <= audio::SOUND_MAX_ID; id++) TEST_ASSERT_EQUAL_UINT32(expected[id], audio::Earcon::duration_ms(id));
  TEST_ASSERT_EQUAL_UINT32(0, audio::Earcon::duration_ms(0));
  TEST_ASSERT_EQUAL_UINT32(0, audio::Earcon::duration_ms(audio::SOUND_MAX_ID + 1));
  audio::Earcon e;
  TEST_ASSERT_FALSE(e.start(0));
  TEST_ASSERT_FALSE(e.start(200));
  TEST_ASSERT_FALSE(e.active());
}

static void test_earcon_render_length_bounds_and_smooth_edges() {
  for (uint8_t id = 1; id <= audio::SOUND_MAX_ID; id++) {
    audio::Earcon e(16000);
    TEST_ASSERT_TRUE(e.start(id));
    std::vector<int16_t> all;
    int16_t block[160];
    for (int i = 0; i < 100 && e.active(); i++) {
      size_t n = e.render(block, 160);
      all.insert(all.end(), block, block + n);
    }
    TEST_ASSERT_FALSE(e.active());
    TEST_ASSERT_EQUAL(audio::Earcon::duration_ms(id) * 16, all.size());
    TEST_ASSERT_TRUE(audio::peak(all.data(), all.size()) <= (uint16_t)(0.8f * 32767 + 1));
    TEST_ASSERT_TRUE(audio::peak(all.data(), all.size()) > 5000);  // audible
    TEST_ASSERT_TRUE(abs(all.front()) < 200);  // ramps in and out: no click
    TEST_ASSERT_TRUE(abs(all.back()) < 200);
    size_t n = e.render(block, 160);  // after the end: silence
    TEST_ASSERT_EQUAL(0, n);
    TEST_ASSERT_EQUAL_INT16(0, block[0]);
  }
}

// ---- Packets ----------------------------------------------------------------------------------

static void test_audio_in_encoding() {
  int16_t pcm[3] = {1, -2, 0x1234};
  uint8_t p[16];
  size_t n = audio_packets::encode_audio_in(p, 0x01020304, pcm, 3);
  const uint8_t expected[] = {4, 3, 2, 1, 1, 0, 0xFE, 0xFF, 0x34, 0x12};
  TEST_ASSERT_EQUAL(sizeof(expected), n);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, p, n);
}

static void test_audio_out_decoding() {
  const uint8_t p[] = {0x02, 0x01, 0x10, 0, 0, 0, 0xFF, 0x7F, 0x00, 0x80};
  audio_packets::AudioOut a;
  TEST_ASSERT_TRUE(audio_packets::decode_audio_out(p, sizeof(p), &a));
  TEST_ASSERT_EQUAL_UINT16(0x0102, a.stream);
  TEST_ASSERT_EQUAL_UINT32(16, a.sample_index);
  TEST_ASSERT_EQUAL(2, a.n);
  TEST_ASSERT_EQUAL_INT16(32767, a.sample(0));
  TEST_ASSERT_EQUAL_INT16(-32768, a.sample(1));
  TEST_ASSERT_FALSE(audio_packets::decode_audio_out(p, 6, &a));   // no samples
  TEST_ASSERT_FALSE(audio_packets::decode_audio_out(p, 9, &a));   // odd PCM length
  std::vector<uint8_t> big(6 + 2 * (proto::AUDIO_OUT_MAX_SAMPLES + 1));
  TEST_ASSERT_FALSE(audio_packets::decode_audio_out(big.data(), big.size(), &a));
  big.resize(6 + 2 * proto::AUDIO_OUT_MAX_SAMPLES);
  TEST_ASSERT_TRUE(audio_packets::decode_audio_out(big.data(), big.size(), &a));
  TEST_ASSERT_TRUE(proto::HEADER_SIZE + big.size() <= proto::MAX_HOST_DATAGRAM);
}

static void test_audio_ctrl_and_sound_decoding() {
  audio_packets::AudioCtrl c;
  const uint8_t vol[] = {proto::AUDIO_VOLUME, 80};
  TEST_ASSERT_TRUE(audio_packets::decode_audio_ctrl(vol, 2, &c));
  TEST_ASSERT_EQUAL_UINT8(4, c.command);
  TEST_ASSERT_EQUAL_UINT8(80, c.argument);
  const uint8_t start[] = {proto::AUDIO_MIC_START};
  TEST_ASSERT_TRUE(audio_packets::decode_audio_ctrl(start, 1, &c));
  TEST_ASSERT_EQUAL_UINT8(0, c.argument);
  TEST_ASSERT_FALSE(audio_packets::decode_audio_ctrl(start, 0, &c));
  uint8_t id = 0;
  const uint8_t s[] = {3};
  TEST_ASSERT_TRUE(audio_packets::decode_sound(s, 1, &id));
  TEST_ASSERT_EQUAL_UINT8(3, id);
  TEST_ASSERT_FALSE(audio_packets::decode_sound(s, 0, &id));
}

static void test_protocol_constants() {
  TEST_ASSERT_EQUAL_HEX8(0x06, proto::AUDIO_IN);
  TEST_ASSERT_EQUAL_HEX8(0x84, proto::AUDIO_OUT);
  TEST_ASSERT_EQUAL_HEX8(0x85, proto::AUDIO_CTRL);
  TEST_ASSERT_EQUAL_HEX8(0x86, proto::SOUND);
  TEST_ASSERT_EQUAL_HEX8(0x02, proto::FLAG_CAMERA);
  TEST_ASSERT_EQUAL_HEX8(0x04, proto::FLAG_AUDIO);
  TEST_ASSERT_EQUAL(81, proto::CAMERA_PORT);
  TEST_ASSERT_EQUAL(-1, audio_packets::index_diff(0xFFFFFFFFu, 0));
  TEST_ASSERT_EQUAL(1, audio_packets::index_diff(0, 0xFFFFFFFFu));
}

// ---- DSP --------------------------------------------------------------------------------------

static void test_dc_blocker_removes_offset() {
  audio::DcBlocker dc;
  std::vector<int16_t> x(16000, 3000);  // pure DC
  dc.process(x.data(), x.size());
  TEST_ASSERT_TRUE(abs(x.back()) < 20);
  // a 1 kHz tone on a DC offset keeps its amplitude
  std::vector<int16_t> t(1600);
  for (size_t i = 0; i < t.size(); i++) t[i] = (int16_t)(3000 + 8000 * ((i / 8) % 2 ? 1 : -1));
  dc.process(t.data(), t.size());
  TEST_ASSERT_TRUE(audio::peak(t.data() + 800, 800) > 7000);
}

static void test_dc_blocker_gain_saturates() {
  audio::DcBlocker dc;
  int16_t x[4] = {0, 20000, -20000, 20000};
  dc.process(x, 4, 4.0f);
  TEST_ASSERT_EQUAL_INT16(0, x[0]);
  TEST_ASSERT_EQUAL_INT16(32767, x[1]);
  TEST_ASSERT_EQUAL_INT16(-32768, x[2]);
}

static void test_volume_curve_and_cap() {
  TEST_ASSERT_EQUAL_FLOAT(0.0f, audio::volume_gain(0, 0.5f));
  TEST_ASSERT_EQUAL_FLOAT(0.5f, audio::volume_gain(100, 0.5f));
  TEST_ASSERT_EQUAL_FLOAT(0.5f, audio::volume_gain(255, 0.5f));  // clamped: never above the cap
  TEST_ASSERT_FLOAT_WITHIN(1e-6, 0.125f, audio::volume_gain(50, 0.5f));
  int16_t x[2] = {32767, -32768};
  audio::scale(x, 2, audio::volume_gain(100, 0.5f));
  TEST_ASSERT_EQUAL_INT16(16384, x[0]);
  TEST_ASSERT_EQUAL_INT16(-16384, x[1]);
  TEST_ASSERT_FLOAT_WITHIN(1e-3, 3.981f, audio::db_to_gain(12));
}

static void test_mix_saturates() {
  int16_t a[3] = {30000, -30000, 5};
  const int16_t b[3] = {10000, -10000, -10};
  audio::mix(a, b, 3);
  TEST_ASSERT_EQUAL_INT16(32767, a[0]);
  TEST_ASSERT_EQUAL_INT16(-32768, a[1]);
  TEST_ASSERT_EQUAL_INT16(-5, a[2]);
}

// ---- Camera HTTP ------------------------------------------------------------------------------

static camera_http::Route route(const std::string &s, bool full = false) {
  return camera_http::route(s.data(), s.size(), full);
}

static void test_camera_routes() {
  using camera_http::Route;
  TEST_ASSERT_EQUAL(Route::Stream, route("GET /stream HTTP/1.1\r\nHost: x\r\n\r\n"));
  TEST_ASSERT_EQUAL(Route::Capture, route("GET /capture?t=1 HTTP/1.1\r\n\r\n"));
  TEST_ASSERT_EQUAL(Route::Capture, route("GET /capture.jpg HTTP/1.0\r\n\r\n"));
  TEST_ASSERT_EQUAL(Route::Index, route("GET / HTTP/1.1\r\n\r\n"));
  TEST_ASSERT_EQUAL(Route::NotFound, route("GET /streams HTTP/1.1\r\n\r\n"));
  TEST_ASSERT_EQUAL(Route::BadRequest, route("POST /stream HTTP/1.1\r\n\r\n"));
  TEST_ASSERT_EQUAL(Route::Incomplete, route("GET /stream HTTP/1.1\r\nHost: x\r\n"));
  TEST_ASSERT_EQUAL(Route::BadRequest, route("GET /stream HTTP/1.1\r\nHost: x\r\n", true));
}

static void test_camera_part_head() {
  char head[256];
  size_t n = camera_http::part_head(head, sizeof(head), 1234, 5678);
  TEST_ASSERT_EQUAL_STRING(
      "--marvinframe\r\nContent-Type: image/jpeg\r\nContent-Length: 1234\r\nX-Marvin-Time-Us: 5678\r\n\r\n", head);
  TEST_ASSERT_EQUAL(strlen(head), n);
  n = camera_http::stream_head(head, sizeof(head));
  TEST_ASSERT_NOT_NULL(strstr(head, "multipart/x-mixed-replace; boundary=marvinframe\r\n"));
  TEST_ASSERT_EQUAL(strlen(head), n);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_jitter_prebuffers_then_plays_in_order);
  RUN_TEST(test_jitter_plays_short_tail_after_drain_timeout);
  RUN_TEST(test_jitter_fills_lost_datagram_with_silence);
  RUN_TEST(test_jitter_drops_late_and_trims_overlap);
  RUN_TEST(test_jitter_large_gap_resyncs);
  RUN_TEST(test_jitter_index_wraps);
  RUN_TEST(test_jitter_new_stream_flushes_old);
  RUN_TEST(test_jitter_stop_ignores_stopped_stream);
  RUN_TEST(test_jitter_overflow_drops_newest);
  RUN_TEST(test_jitter_underrun_rebuffers);
  RUN_TEST(test_jitter_ring_wraps_around);
  RUN_TEST(test_earcon_durations_and_ids);
  RUN_TEST(test_earcon_render_length_bounds_and_smooth_edges);
  RUN_TEST(test_audio_in_encoding);
  RUN_TEST(test_audio_out_decoding);
  RUN_TEST(test_audio_ctrl_and_sound_decoding);
  RUN_TEST(test_protocol_constants);
  RUN_TEST(test_dc_blocker_removes_offset);
  RUN_TEST(test_dc_blocker_gain_saturates);
  RUN_TEST(test_volume_curve_and_cap);
  RUN_TEST(test_mix_saturates);
  RUN_TEST(test_camera_routes);
  RUN_TEST(test_camera_part_head);
  return UNITY_END();
}
