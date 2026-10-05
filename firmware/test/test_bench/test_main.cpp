// Tests of the bench board's portable parts: `pio test -e native`.
// Button debouncing, the buzzer's tone sequencer, the status LED patterns, the OLED eyes' layout
// and moods, the BUTTON payload, the eyes following the board's own radar and the soft "bye".
// SPDX-License-Identifier: MIT
#include <unity.h>

#include <string.h>

#include "audio/earcons.h"
#include "bench/bench_logic.h"
#include "bench/eyes.h"
#include "face/face_link.h"
#include "protocol.h"

using bench::Button;
using bench::Chime;
using bench::Eyes;
using bench::EyesLayout;
using bench::LinkPhase;
using bench::Mood;

void setUp() {}
void tearDown() {}

// ---- Button -----------------------------------------------------------------------------------

static void test_button_press_counts_once_after_debounce() {
  Button b;
  TEST_ASSERT_FALSE(b.update(false, 0));
  TEST_ASSERT_FALSE(b.update(true, 100));   // pressed: not yet stable
  TEST_ASSERT_FALSE(b.update(true, 129));
  TEST_ASSERT_TRUE(b.update(true, 130));    // 30 ms stable: one press
  TEST_ASSERT_TRUE(b.pressed());
  for (uint32_t t = 131; t < 2000; t += 7) TEST_ASSERT_FALSE(b.update(true, t));  // held: nothing more
  TEST_ASSERT_FALSE(b.update(false, 2000));
  TEST_ASSERT_FALSE(b.update(false, 2030));  // released (no event for a release)
  TEST_ASSERT_FALSE(b.pressed());
  TEST_ASSERT_FALSE(b.update(true, 2100));
  TEST_ASSERT_TRUE(b.update(true, 2131));   // a second press
}

static void test_button_ignores_bounce() {
  Button b;
  b.update(false, 0);
  // contact bounce: the level flips every 3 ms for 20 ms, then settles released
  bool level = true;
  for (uint32_t t = 10; t < 30; t += 3, level = !level) TEST_ASSERT_FALSE(b.update(level, t));
  for (uint32_t t = 30; t < 200; t++) TEST_ASSERT_FALSE(b.update(false, t));
  TEST_ASSERT_FALSE(b.pressed());
  // bounce then settles pressed: exactly one press, 30 ms after the last flip
  int presses = 0;
  level = true;
  for (uint32_t t = 200; t < 215; t += 3, level = !level) presses += b.update(level, t);
  for (uint32_t t = 215; t < 400; t++) presses += b.update(true, t);
  TEST_ASSERT_EQUAL(1, presses);
}

static void test_button_held_at_boot_is_not_a_press() {
  Button b;
  TEST_ASSERT_FALSE(b.update(true, 5));
  for (uint32_t t = 6; t < 500; t++) TEST_ASSERT_FALSE(b.update(true, t));
  TEST_ASSERT_TRUE(b.pressed());
}

static void test_button_across_millis_wrap() {
  Button b;
  b.update(false, 0xFFFFFF00u);
  TEST_ASSERT_FALSE(b.update(true, 0xFFFFFFF0u));
  TEST_ASSERT_TRUE(b.update(true, 0x0000000Eu));  // 30 ms later, after the wrap
}

// ---- Chime ------------------------------------------------------------------------------------

static void test_chime_plays_the_earcon_notes() {
  Chime c;
  TEST_ASSERT_TRUE(c.start(audio::SOUND_WAKE, 1000));  // 660 Hz 80 ms, 990 Hz 120 ms
  TEST_ASSERT_TRUE(c.active());
  TEST_ASSERT_EQUAL_UINT16(660, c.freq(1000));
  TEST_ASSERT_EQUAL_UINT16(660, c.freq(1079));
  TEST_ASSERT_EQUAL_UINT16(990, c.freq(1080));
  TEST_ASSERT_EQUAL_UINT16(990, c.freq(1199));
  TEST_ASSERT_EQUAL_UINT16(0, c.freq(1200));
  TEST_ASSERT_FALSE(c.active());
}

static void test_chime_silences_and_sweeps() {
  Chime c;
  TEST_ASSERT_TRUE(c.start(audio::SOUND_ERROR, 0));  // 330 Hz 120 ms, silence 60 ms, 262 Hz 180 ms
  TEST_ASSERT_EQUAL_UINT16(330, c.freq(10));
  TEST_ASSERT_EQUAL_UINT16(0, c.freq(150));
  TEST_ASSERT_TRUE(c.active());
  TEST_ASSERT_EQUAL_UINT16(262, c.freq(200));
  TEST_ASSERT_TRUE(c.start(audio::SOUND_CHIRP, 0));  // 1200 -> 2400 Hz over 50 ms
  uint16_t last = 0;
  for (uint32_t t = 0; t < 50; t++) {
    uint16_t f = c.freq(t);
    TEST_ASSERT_TRUE(f >= 1200 && f <= 2400);
    TEST_ASSERT_EQUAL(0, f % Chime::SWEEP_STEP_HZ);
    TEST_ASSERT_TRUE(f >= last);
    last = f;
  }
}

static void test_chime_unknown_id_and_click() {
  Chime c;
  TEST_ASSERT_FALSE(c.start(0, 0));
  TEST_ASSERT_FALSE(c.start(audio::SOUND_MAX_ID + 1, 0));
  TEST_ASSERT_FALSE(c.active());
  c.click(100);
  TEST_ASSERT_EQUAL_UINT16(Chime::CLICK_HZ, c.freq(100));
  TEST_ASSERT_EQUAL_UINT16(0, c.freq(100 + Chime::CLICK_MS));
  c.start(audio::SOUND_HELLO, 200);
  c.click(210);  // a sound is playing: the click does not cut it
  TEST_ASSERT_EQUAL_UINT16(523, c.freq(210));
}

static void test_chime_lengths_match_the_earcons() {
  for (uint8_t id = 1; id <= audio::SOUND_MAX_ID; id++) {
    Chime c;
    TEST_ASSERT_TRUE(c.start(id, 0));
    const uint32_t ms = audio::Earcon::duration_ms(id);
    c.freq(ms - 1);
    TEST_ASSERT_TRUE(c.active());
    c.freq(ms);
    TEST_ASSERT_FALSE(c.active());
  }
}

// ---- Status LED -------------------------------------------------------------------------------

static void test_status_patterns() {
  int on = 0;
  for (uint32_t t = 0; t < 1000; t++) on += bench::status_led_on(LinkPhase::WIFI, t);
  TEST_ASSERT_EQUAL(500, on);  // one slow blink per second
  int edges = 0;
  for (uint32_t t = 1; t < 1000; t++)
    edges += bench::status_led_on(LinkPhase::SEARCHING, t) != bench::status_led_on(LinkPhase::SEARCHING, t - 1);
  TEST_ASSERT_EQUAL(7, edges);  // four fast blinks per second
  for (uint32_t t = 0; t < 3000; t += 17) TEST_ASSERT_TRUE(bench::status_led_on(LinkPhase::LINKED, t));
  bench::Rgb8 linked = bench::status_color(LinkPhase::LINKED, 123);
  TEST_ASSERT_TRUE(linked.g > 0 && linked.r == 0 && linked.b == 0);
  bench::Rgb8 wifi = bench::status_color(LinkPhase::WIFI, 0);
  TEST_ASSERT_TRUE(wifi.b > 0 && wifi.r == 0 && wifi.g == 0);
}

// ---- Eyes -------------------------------------------------------------------------------------

static int eye_height(const EyesLayout &l) { return l.eye[0].h; }
static double eye_center_x(const EyesLayout &l) { return (l.eye[0].x + l.eye[1].x + l.eye[1].w) / 2.0; }

static void test_eyes_layout_neutral_is_symmetric_and_on_screen() {
  EyesLayout l;
  Eyes::layout(face::EXPRESSIONS[(int)face::Expr::NEUTRAL], 0, 0, 0, 32, l);
  for (const bench::EyeShape &e : l.eye) {
    TEST_ASSERT_TRUE(e.x >= 0 && e.y >= 0 && e.x + e.w <= Eyes::WIDTH && e.y + e.h <= Eyes::HEIGHT);
    TEST_ASSERT_EQUAL(32, e.w);  // 64 face px at half scale
    TEST_ASSERT_EQUAL(41, e.h);
    TEST_ASSERT_TRUE(2 * (e.r + 1) <= e.w && 2 * (e.r + 1) <= e.h);  // what U8g2's drawRBox needs
    TEST_ASSERT_FALSE(e.top_lid);
    TEST_ASSERT_FALSE(e.bottom_lid);
  }
  TEST_ASSERT_EQUAL(Eyes::WIDTH, l.eye[0].x + l.eye[1].x + l.eye[1].w);  // mirrored around x = 64
}

static void test_eyes_layout_lids_and_closed() {
  EyesLayout l;
  Eyes::layout(face::EXPRESSIONS[(int)face::Expr::CONCERNED], 0, 0, 0, 32, l);
  TEST_ASSERT_TRUE(l.eye[0].top_lid && l.eye[1].top_lid);
  // tilted: the lid is lower on the inner side of each eye
  TEST_ASSERT_TRUE(l.eye[0].lid_y[1] < l.eye[0].lid_y[2] || l.eye[0].lid_y[3] > l.eye[0].lid_y[0]);
  Eyes::layout(face::EXPRESSIONS[(int)face::Expr::CONTENT], 0, 0, 0, 32, l);
  TEST_ASSERT_TRUE(l.eye[0].bottom_lid);
  Eyes::layout(face::EXPRESSIONS[(int)face::Expr::NEUTRAL], 0, 0, 1.0, 32, l);  // mid-blink
  TEST_ASSERT_TRUE(eye_height(l) <= 3);
  TEST_ASSERT_TRUE(eye_height(l) >= 2);  // a line, not nothing
}

static void test_eyes_start_asleep_without_a_host() {
  Eyes eyes(7);
  EyesLayout l;
  for (uint32_t t = 0; t <= 2000; t += 40) eyes.frame(t, 32, l);
  TEST_ASSERT_TRUE(eye_height(l) <= 4);  // eyes closed
  TEST_ASSERT_EQUAL(Mood::NONE, eyes.mood());
}

static void test_eyes_listening_opens_them_wide_until_done() {
  Eyes eyes(7);
  EyesLayout l;
  uint32_t t = 0;
  for (; t <= 1000; t += 40) eyes.frame(t, 32, l);
  const uint8_t wake = audio::SOUND_WAKE;
  TEST_ASSERT_TRUE(eyes.on_message(proto::SOUND, &wake, 1, t));
  for (uint32_t end = t + 600; t <= end; t += 40) eyes.frame(t, 32, l);
  TEST_ASSERT_EQUAL(Mood::LISTENING, eyes.mood());
  TEST_ASSERT_TRUE(eye_height(l) >= 42);  // wide open (the asleep face's eyes, 37 px, and more)
  const uint8_t done = audio::SOUND_DONE;
  eyes.on_message(proto::SOUND, &done, 1, t);
  for (uint32_t end = t + 600; t <= end; t += 40) eyes.frame(t, 32, l);
  TEST_ASSERT_EQUAL(Mood::THINKING, eyes.mood());
  TEST_ASSERT_TRUE(eye_center_x(l) > 64 + 4);  // glancing to the side...
  TEST_ASSERT_TRUE(eye_height(l) >= 20);       // ...eyes open, although nobody is there
  for (uint32_t end = t + 4000; t <= end; t += 40) eyes.frame(t, 32, l);
  TEST_ASSERT_EQUAL(Mood::NONE, eyes.mood());  // faded out
}

static void test_eyes_ignore_unknown_sounds_and_messages() {
  Eyes eyes(3);
  const uint8_t beep = audio::SOUND_BEEP, junk = 200;
  eyes.on_message(proto::SOUND, &beep, 1, 0);
  eyes.on_message(proto::SOUND, &junk, 1, 0);
  TEST_ASSERT_EQUAL(Mood::NONE, eyes.mood());
  TEST_ASSERT_FALSE(eyes.on_message(proto::SOUND, nullptr, 0, 0));
  const uint8_t unknown_event = 99;
  TEST_ASSERT_FALSE(eyes.on_message(proto::FACE_EVENT, &unknown_event, 1, 0));
  TEST_ASSERT_FALSE(eyes.on_message(proto::AUDIO_CTRL, &beep, 1, 0));
}

// A FACE_STATE payload with someone present, head at (x, -900, 500) mm.
static void presence_payload(int16_t x, uint8_t *p) {
  memset(p, 0, proto::FACE_STATE_SIZE);
  p[0] = proto::FACE_PRESENT | proto::FACE_HEAD | proto::FACE_POSITION | proto::FACE_DISTANCE;
  proto::put16(p + 1, (uint16_t)x);
  proto::put16(p + 3, (uint16_t)(int16_t)-900);
  proto::put16(p + 5, 500);
  proto::put16(p + 7, (uint16_t)x);
  proto::put16(p + 9, (uint16_t)(int16_t)-900);
  proto::put16(p + 11, 0);
  proto::put16(p + 13, 1000);
}

static double gaze_after(int16_t head_x) {
  Eyes eyes(11);
  EyesLayout l;
  uint8_t p[proto::FACE_STATE_SIZE];
  presence_payload(head_x, p);
  double sum = 0;
  int n = 0;
  for (uint32_t t = 0; t <= 6000; t += 40) {
    TEST_ASSERT_TRUE(eyes.on_message(proto::FACE_STATE, p, sizeof(p), t));
    eyes.frame(t, 32, l);
    if (t >= 3000) {
      sum += eye_center_x(l);
      n++;
    }
  }
  TEST_ASSERT_TRUE(eye_height(l) > 10);  // someone is there: awake
  return sum / n;
}

static void test_eyes_follow_the_head() {
  const double right = gaze_after(800), left = gaze_after(-800);
  TEST_ASSERT_TRUE(right > 64 + 3);
  TEST_ASSERT_TRUE(left < 64 - 3);
}

// ---- Protocol ---------------------------------------------------------------------------------

static void test_button_payload() {
  uint8_t p[4] = {0xAA, 0xAA, 0xAA, 0xAA};
  TEST_ASSERT_EQUAL(2, proto::button(p, proto::BUTTON_MUTE, proto::BUTTON_PRESS));
  TEST_ASSERT_EQUAL_UINT8(2, p[0]);
  TEST_ASSERT_EQUAL_UINT8(1, p[1]);
  TEST_ASSERT_EQUAL_UINT8(0xAA, p[2]);
  TEST_ASSERT_EQUAL_UINT8(0x07, proto::BUTTON);
  TEST_ASSERT_EQUAL_UINT8(0x08, proto::FLAG_BUTTONS);
  TEST_ASSERT_EQUAL_UINT8(0x10, proto::FLAG_BUZZER);
  TEST_ASSERT_EQUAL_UINT8(0x20, proto::FLAG_FACE);
}

// ---- The board's own presence and the soft bye --------------------------------------------------

static void test_local_presence_round_trip_and_priority() {
  face::Presence s;
  s.present = true;
  s.has_position = true;
  s.position[0] = 250;
  s.position[1] = -900;
  s.position[2] = 300;
  uint8_t p[proto::FACE_STATE_SIZE];
  TEST_ASSERT_EQUAL(proto::FACE_STATE_SIZE, face::encode_state(s, p));
  face::Presence d;
  TEST_ASSERT_TRUE(face::decode_state(p, sizeof(p), d));
  TEST_ASSERT_TRUE(d.present && d.has_position && !d.has_head);
  TEST_ASSERT_TRUE(250 == d.position[0]);
  TEST_ASSERT_TRUE(-900 == d.position[1]);
  // the eyes follow the local target while the host is quiet, and look elsewhere once it expires
  bench::Eyes eyes(7);
  bench::EyesLayout l;
  for (uint32_t ms = 0; ms < 3000; ms += 40) {
    eyes.on_local_state(p, sizeof(p), ms);
    eyes.frame(ms, 32, l);
  }
  TEST_ASSERT_TRUE(eyes.link().face().gaze_x() > 5);  // looking to the right, at them
  TEST_ASSERT_FALSE(eyes.link().host_alive(3000));
}

static void test_chime_bye_is_soft() {
  bench::Chime c;
  c.bye(0);
  TEST_ASSERT_TRUE(c.soft());
  TEST_ASSERT_EQUAL_UINT16(784, c.freq(10));
  TEST_ASSERT_EQUAL_UINT16(0, c.freq(100));
  TEST_ASSERT_EQUAL_UINT16(523, c.freq(200));
  TEST_ASSERT_EQUAL_UINT16(0, c.freq(400));
  TEST_ASSERT_FALSE(c.soft());
  c.start(6, 500);  // hello: not soft
  TEST_ASSERT_FALSE(c.soft());
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_button_press_counts_once_after_debounce);
  RUN_TEST(test_button_ignores_bounce);
  RUN_TEST(test_button_held_at_boot_is_not_a_press);
  RUN_TEST(test_button_across_millis_wrap);
  RUN_TEST(test_chime_plays_the_earcon_notes);
  RUN_TEST(test_chime_silences_and_sweeps);
  RUN_TEST(test_chime_unknown_id_and_click);
  RUN_TEST(test_chime_lengths_match_the_earcons);
  RUN_TEST(test_status_patterns);
  RUN_TEST(test_eyes_layout_neutral_is_symmetric_and_on_screen);
  RUN_TEST(test_eyes_layout_lids_and_closed);
  RUN_TEST(test_eyes_start_asleep_without_a_host);
  RUN_TEST(test_eyes_listening_opens_them_wide_until_done);
  RUN_TEST(test_eyes_ignore_unknown_sounds_and_messages);
  RUN_TEST(test_eyes_follow_the_head);
  RUN_TEST(test_button_payload);
  RUN_TEST(test_local_presence_round_trip_and_priority);
  RUN_TEST(test_chime_bye_is_soft);
  return UNITY_END();
}
