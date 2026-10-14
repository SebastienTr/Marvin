// Tests of the voice's moods on the robot's face (face/mood.h, through face/face_link.h).
// `pio test -e native -f test_face_mood`.
// SPDX-License-Identifier: MIT
#include <unity.h>

#include <math.h>
#include <string.h>

#include "audio/earcons.h"
#include "face/face_link.h"
#include "face/mood.h"
#include "protocol.h"

using namespace face;

// Unity is built without double support here.
#define ASSERT_NEAR(expected, actual) TEST_ASSERT_TRUE(fabs((double)(expected) - (double)(actual)) < 1e-9)

void setUp() {}
void tearDown() {}

static void test_names() {
  TEST_ASSERT_EQUAL_STRING("none", mood_name(Mood::NONE));
  TEST_ASSERT_EQUAL_STRING("listening", mood_name(Mood::LISTENING));
  TEST_ASSERT_EQUAL_STRING("thinking", mood_name(Mood::THINKING));
  TEST_ASSERT_EQUAL_STRING("speaking", mood_name(Mood::SPEAKING));
  TEST_ASSERT_EQUAL_STRING("concerned", mood_name(Mood::CONCERNED));
  TEST_ASSERT_EQUAL_STRING("happy", mood_name(Mood::HAPPY));
}

// Talk now: wake, then done, then the answer plays, then it ends.
static void test_a_conversation_turn() {
  Moods m;
  TEST_ASSERT_EQUAL(Mood::NONE, m.mood(0));
  m.on_sound(audio::SOUND_WAKE, 1.0);
  TEST_ASSERT_EQUAL(Mood::LISTENING, m.mood(1.0));
  ASSERT_NEAR(0.0, m.weight(1.0));  // fades in
  ASSERT_NEAR(1.0, m.weight(1.0 + Moods::FADE_IN_S));
  m.on_sound(audio::SOUND_DONE, 4.0);
  TEST_ASSERT_EQUAL(Mood::THINKING, m.mood(4.5));
  m.set_speaking(true, 5.0);
  TEST_ASSERT_EQUAL(Mood::SPEAKING, m.mood(5.0));
  TEST_ASSERT_EQUAL(Mood::SPEAKING, m.mood(60.0));  // as long as the answer plays
  ASSERT_NEAR(1.0, m.weight(60.0));
  m.set_speaking(false, 61.0);
  TEST_ASSERT_TRUE(m.weight(61.1) < 1.0);  // fades out
  TEST_ASSERT_EQUAL(Mood::SPEAKING, m.mood(61.1));
  TEST_ASSERT_EQUAL(Mood::NONE, m.mood(61.0 + Moods::FADE_OUT_S + 0.01));
}

static void test_listening_times_out() {
  Moods m;
  m.on_sound(audio::SOUND_WAKE, 0.0);
  TEST_ASSERT_EQUAL(Mood::LISTENING, m.mood(Moods::LISTENING_S));
  TEST_ASSERT_EQUAL(Mood::NONE, m.mood(Moods::LISTENING_S + Moods::FADE_OUT_S + 0.01));
  // thinking too, when no answer comes
  m.on_sound(audio::SOUND_DONE, 20.0);
  TEST_ASSERT_EQUAL(Mood::THINKING, m.mood(20.0 + Moods::THINKING_S - 0.01));
  TEST_ASSERT_EQUAL(Mood::NONE, m.mood(20.0 + Moods::THINKING_S + Moods::FADE_OUT_S + 0.01));
}

static void test_wake_again_extends_without_fading_in_again() {
  Moods m;
  m.on_sound(audio::SOUND_WAKE, 0.0);
  m.on_sound(audio::SOUND_WAKE, 5.0);
  ASSERT_NEAR(1.0, m.weight(5.0));
  TEST_ASSERT_EQUAL(Mood::LISTENING, m.mood(12.0));
}

static void test_a_sound_during_the_answer_then_back_to_speaking() {
  Moods m;
  m.set_speaking(true, 0.0);
  m.on_sound(audio::SOUND_HELLO, 2.0);
  TEST_ASSERT_EQUAL(Mood::HAPPY, m.mood(2.5));
  TEST_ASSERT_EQUAL(Mood::SPEAKING, m.mood(2.0 + Moods::HAPPY_S + 0.01));
  // done while it speaks changes nothing
  m.on_sound(audio::SOUND_DONE, 5.0);
  TEST_ASSERT_EQUAL(Mood::SPEAKING, m.mood(5.5));
}

static void test_the_answer_waits_for_a_happy_look_to_finish() {
  Moods m;
  m.on_sound(audio::SOUND_HELLO, 0.0);  // the greeting chime, then the greeting itself
  m.set_speaking(true, 0.3);
  TEST_ASSERT_EQUAL(Mood::HAPPY, m.mood(1.0));
  TEST_ASSERT_EQUAL(Mood::SPEAKING, m.mood(Moods::HAPPY_S + 0.01));
}

static void test_unknown_sounds_and_beeps_are_ignored() {
  Moods m;
  m.on_sound(audio::SOUND_BEEP, 0.0);
  m.on_sound(audio::SOUND_CHIRP, 0.0);
  m.on_sound(200, 0.0);
  TEST_ASSERT_EQUAL(Mood::NONE, m.mood(0.1));
  m.on_sound(audio::SOUND_ERROR, 1.0);
  TEST_ASSERT_EQUAL(Mood::CONCERNED, m.mood(1.1));
}

static void test_clear() {
  Moods m;
  m.set_speaking(true, 0.0);
  m.clear(1.0);
  TEST_ASSERT_EQUAL(Mood::NONE, m.mood(1.0 + Moods::FADE_OUT_S + 0.01));
  m.on_sound(audio::SOUND_WAKE, 2.0);
  m.clear(3.0);
  TEST_ASSERT_EQUAL(Mood::NONE, m.mood(3.0 + Moods::FADE_OUT_S + 0.01));
}

static void test_apply() {
  Moods m;
  FaceParams base = EXPRESSIONS[(int)Expr::SLEEPY];
  FaceParams p = base;
  double gx = 0, gy = 0, blink = 0.7;
  ASSERT_NEAR(0.0, m.apply(0.0, p, &gx, &gy, &blink));  // no mood: nothing changes
  TEST_ASSERT_EQUAL(0, memcmp(&p, &base, sizeof(p)));
  ASSERT_NEAR(0.7, blink);

  m.on_sound(audio::SOUND_WAKE, 0.0);
  ASSERT_NEAR(1.0, m.apply(1.0, p, &gx, &gy, &blink));
  ASSERT_NEAR(1.0, p.open);          // wide open, even from sleepy
  ASSERT_NEAR(0.0, blink);           // no blink while listening
  TEST_ASSERT_TRUE(p.height > base.height);

  Moods t;
  t.on_sound(audio::SOUND_DONE, 0.0);
  p = base;
  gx = gy = 0;
  blink = 0;
  t.apply(1.0, p, &gx, &gy, &blink);
  ASSERT_NEAR(16.0, gx);             // a glance up and to the side
  ASSERT_NEAR(-16.0, gy);

  // speaking: the eyes bob, so two moments a quarter period apart differ
  Moods s;
  s.set_speaking(true, 0.0);
  FaceParams a = base, b = base;
  double g = 0, bl = 0;
  s.apply(2.0 + 0.25 / Moods::SPEAKING_HZ, a, &g, &g, &bl);
  s.apply(2.0 + 0.75 / Moods::SPEAKING_HZ, b, &g, &g, &bl);
  TEST_ASSERT_TRUE(a.dy < base.dy && b.dy > base.dy);
  ASSERT_NEAR(1.0, a.open);
}

// FaceLink: SOUND messages and the speaker set the mood; the frame shows it; host_lost clears it.
static void test_link() {
  static uint16_t with[SCREEN_W * SCREEN_H], without[SCREEN_W * SCREEN_H];
  FaceLink a(7), b(7);
  const uint8_t wake = audio::SOUND_WAKE;
  uint32_t ms = 1000;
  for (int i = 0; i < 30; i++, ms += 33) {
    a.frame(ms, with);
    b.frame(ms, without);
  }
  TEST_ASSERT_EQUAL(0, memcmp(with, without, sizeof(with)));  // same seed, no mood: the same face
  TEST_ASSERT_TRUE(a.on_message(proto::SOUND, &wake, 1, ms));
  TEST_ASSERT_FALSE(a.on_message(proto::SOUND, &wake, 0, ms));  // empty payload
  for (int i = 0; i < 15; i++, ms += 33) {
    a.frame(ms, with);
    b.frame(ms, without);
  }
  TEST_ASSERT_EQUAL(Mood::LISTENING, a.mood(ms));
  TEST_ASSERT_EQUAL(Mood::NONE, b.mood(ms));
  TEST_ASSERT_NOT_EQUAL(0, memcmp(with, without, sizeof(with)));  // asleep vs wide awake

  a.set_speaking(true, ms);
  TEST_ASSERT_EQUAL(Mood::SPEAKING, a.mood(ms));
  a.host_lost();
  for (int i = 0; i < 15; i++, ms += 33) a.frame(ms, with);
  TEST_ASSERT_EQUAL(Mood::NONE, a.mood(ms));

  b.on_sound(audio::SOUND_ERROR, ms);  // a sound the robot plays itself
  TEST_ASSERT_EQUAL(Mood::CONCERNED, b.mood(ms));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_names);
  RUN_TEST(test_a_conversation_turn);
  RUN_TEST(test_listening_times_out);
  RUN_TEST(test_wake_again_extends_without_fading_in_again);
  RUN_TEST(test_a_sound_during_the_answer_then_back_to_speaking);
  RUN_TEST(test_the_answer_waits_for_a_happy_look_to_finish);
  RUN_TEST(test_unknown_sounds_and_beeps_are_ignored);
  RUN_TEST(test_clear);
  RUN_TEST(test_apply);
  RUN_TEST(test_link);
  return UNITY_END();
}
