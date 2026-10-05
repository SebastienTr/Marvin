// SPDX-License-Identifier: MIT
#include "head_io.h"

#if defined(MARVIN_HAS_HEAD) && defined(ARDUINO) && defined(ESP32)

#include <Arduino.h>
#include <math.h>

#include "../protocol.h"

#ifndef MARVIN_HEAD_PIN
#define MARVIN_HEAD_PIN 13
#endif
#ifndef MARVIN_HEAD_LEDC_CHANNEL
#define MARVIN_HEAD_LEDC_CHANNEL 4
#endif
#ifndef MARVIN_HEAD_MIN_US
#define MARVIN_HEAD_MIN_US 500
#endif
#ifndef MARVIN_HEAD_MAX_US
#define MARVIN_HEAD_MAX_US 2500
#endif
#ifndef MARVIN_HEAD_CENTER_DEG
#define MARVIN_HEAD_CENTER_DEG 90
#endif
#ifndef MARVIN_HEAD_MIN_DEG
#define MARVIN_HEAD_MIN_DEG 20
#endif
#ifndef MARVIN_HEAD_MAX_DEG
#define MARVIN_HEAD_MAX_DEG 160
#endif
#ifndef MARVIN_HEAD_MIRROR
#define MARVIN_HEAD_MIRROR 0
#endif
#ifndef MARVIN_HEAD_RADAR_ON_HEAD
#define MARVIN_HEAD_RADAR_ON_HEAD 0
#endif
#ifndef MARVIN_LD2450_X_SIGN
#define MARVIN_LD2450_X_SIGN 1
#endif
#ifndef MARVIN_HEAD_MAX_DPS
#define MARVIN_HEAD_MAX_DPS 120
#endif
#ifndef MARVIN_OLED_ON_HEAD
#define MARVIN_OLED_ON_HEAD 1
#endif
#ifndef MARVIN_HEAD_ATTACH_MS
#define MARVIN_HEAD_ATTACH_MS 1000
#endif
#ifndef MARVIN_HEAD_REST_MS
#define MARVIN_HEAD_REST_MS 5000
#endif

namespace head_io {

namespace {

constexpr uint32_t SERVO_HZ = 50;
constexpr uint8_t DUTY_BITS = 16;
constexpr uint32_t PERIOD_US = 1000000 / SERVO_HZ;

head::Tracker *tracker = nullptr;
head::Presence presence;
head::Target last_target;
bool started = false;

enum class Servo : uint8_t {
  WAITING,  // since boot: no pulses yet
  DRIVEN,   // pulses at the head's angle
  RESTING,  // held still for MARVIN_HEAD_REST_MS: no pulses, until the next move
};
Servo servo = Servo::WAITING;
uint32_t attach_at = 0;
uint32_t written_duty = 0;
uint32_t still_since = 0;
bool was_linked = false;

bool dirty = false;
uint8_t last_flags = 0;
uint8_t last_mode = 0;

head::PresenceEvent events[4];
uint8_t n_events = 0;

void push_event(head::PresenceEvent ev) {
  if (ev == head::PresenceEvent::NONE || n_events >= sizeof(events) / sizeof(events[0])) return;
  events[n_events++] = ev;
}

uint32_t duty_for(double deg) {
  const double us = MARVIN_HEAD_MIN_US + (MARVIN_HEAD_MAX_US - MARVIN_HEAD_MIN_US) * deg / 180.0;
  return (uint32_t)lround(us * ((1u << DUTY_BITS) - 1) / PERIOD_US);
}

void write_duty(uint32_t duty) {
  if (duty == written_duty) return;
  written_duty = duty;
  ledcWrite(MARVIN_HEAD_LEDC_CHANNEL, duty);
}

// Tracking, glancing, the servo and the mode changing are sent at once. The target's own flag is
// not: it flickers with the radar, and the 5 Hz HEAD carries it.
void note_changes() {
  uint8_t p[proto::HEAD_SIZE];
  head::payload(p, *tracker, servo == Servo::DRIVEN);
  const uint8_t f = p[1] & ~proto::HEAD_TARGET;
  if (f != last_flags || tracker->mode() != last_mode) dirty = true;
  last_flags = f;
  last_mode = tracker->mode();
}

}  // namespace

void begin(uint32_t seed) {
  head::Config c;
  c.center_deg = MARVIN_HEAD_CENTER_DEG;
  c.min_deg = MARVIN_HEAD_MIN_DEG;
  c.max_deg = MARVIN_HEAD_MAX_DEG;
  c.mirror = MARVIN_HEAD_MIRROR != 0;
  c.radar_on_head = MARVIN_HEAD_RADAR_ON_HEAD != 0;
  c.max_speed_dps = MARVIN_HEAD_MAX_DPS;
  tracker = new head::Tracker(c, seed);
  // The pin stays low (no pulses: the servo does not move) until attach_at.
  pinMode(MARVIN_HEAD_PIN, OUTPUT);
  digitalWrite(MARVIN_HEAD_PIN, LOW);
  attach_at = millis() + MARVIN_HEAD_ATTACH_MS;
  last_mode = tracker->mode();
  started = true;
  Serial.printf("head: servo on GPIO%d, %d..%d us, centre %d deg, limits %d..%d deg%s, driven in %u ms\n",
                MARVIN_HEAD_PIN, MARVIN_HEAD_MIN_US, MARVIN_HEAD_MAX_US, MARVIN_HEAD_CENTER_DEG, MARVIN_HEAD_MIN_DEG,
                MARVIN_HEAD_MAX_DEG, MARVIN_HEAD_MIRROR ? ", mirrored" : "", (unsigned)MARVIN_HEAD_ATTACH_MS);
}

void on_ld2450(const uint8_t *frame, size_t n, uint32_t now_ms) {
  if (!started) return;
  head::Target t;
  head::nearest_target(frame, n, MARVIN_LD2450_X_SIGN, t);
  last_target = t;
  tracker->on_target(t, now_ms);
  push_event(presence.on_frame(t.valid, now_ms));
}

void step(uint32_t now_ms) {
  if (!started) return;
  push_event(presence.step(now_ms));
  if (servo == Servo::WAITING) {
    if ((int32_t)(now_ms - attach_at) < 0) return;  // the head stays at the centre until then
    ledcSetup(MARVIN_HEAD_LEDC_CHANNEL, SERVO_HZ, DUTY_BITS);
    ledcAttachPin(MARVIN_HEAD_PIN, MARVIN_HEAD_LEDC_CHANNEL);
    servo = Servo::DRIVEN;
    still_since = now_ms;
    written_duty = 0;
    Serial.println("head: servo on, at the centre");
  }
  const double deg = tracker->step(now_ms);
  if (servo == Servo::RESTING && tracker->moving()) {
    servo = Servo::DRIVEN;
    still_since = now_ms;
  }
  if (servo == Servo::DRIVEN) {
    write_duty(duty_for(deg));
    if (tracker->moving()) {
      still_since = now_ms;
    } else if (MARVIN_HEAD_REST_MS > 0 && (uint32_t)(now_ms - still_since) >= MARVIN_HEAD_REST_MS) {
      servo = Servo::RESTING;  // no pulses: the servo goes limp and quiet
      write_duty(0);
    }
  }
  note_changes();
}

void set_linked(bool linked, uint32_t now_ms) {
  if (!started) return;
  if (was_linked && !linked) tracker->host_lost(now_ms);
  was_linked = linked;
}

bool on_message(uint8_t type, const uint8_t *p, size_t n, uint32_t now_ms) {
  if (!started || type != proto::HEAD_CTRL || n < proto::HEAD_CTRL_SIZE) return false;
  const double yaw = (int16_t)proto::get16(p + 1) / 10.0;
  if (!tracker->set_mode(p[0], yaw, now_ms)) return false;
  Serial.printf("head: mode %u, yaw %.1f deg (host)\n", (unsigned)p[0], yaw);
  dirty = true;
  return true;
}

bool changed() {
  const bool c = dirty;
  dirty = false;
  return c;
}

size_t payload(uint8_t *p) {
  if (!started) return 0;
  return head::payload(p, *tracker, servo == Servo::DRIVEN);
}

bool next_event(head::PresenceEvent *ev) {
  if (n_events == 0) return false;
  *ev = events[0];
  for (uint8_t i = 1; i < n_events; i++) events[i - 1] = events[i];
  n_events--;
  return true;
}

bool eyes_point(double z_mm, double out[3]) {
  if (!started || !last_target.valid) return false;
  const bool turn = MARVIN_OLED_ON_HEAD && !MARVIN_HEAD_RADAR_ON_HEAD;
  head::face_point(last_target, turn ? tracker->yaw() : 0.0, z_mm, out);
  return true;
}

}  // namespace head_io

#endif
