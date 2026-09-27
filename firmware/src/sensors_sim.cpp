// SPDX-License-Identifier: MIT
#include "sensors_sim.h"

#include "scene_data.h"

#include <math.h>
#include <string.h>

namespace sim {
namespace {

// The room, the person's path and the timings come from scene_data.h, generated from
// host/marvin_host/scene.py, so the firmware and the host simulators see the same room.
constexpr float SCAN_HZ = 10.0f;
constexpr float LD2450_Y = -33.1f;
constexpr float LD2450_TILT = 10.0f * (float)M_PI / 180.0f;
constexpr float LD2450_FOV = 60.0f * (float)M_PI / 180.0f;
constexpr float LD2450_RANGE = 6000.0f;

constexpr int TABLE = 3600;          // room distances every 0.1 deg, computed once at boot
uint16_t room[TABLE];
uint32_t rate = 4500;
float angle = 0;                     // next scan angle, degrees
uint32_t rng = 12345;

float noise() {                      // roughly +-8 mm
  rng = rng * 1664525u + 1013904223u;
  return ((rng >> 16) & 0xFF) / 16.0f - 8.0f;
}

float ray_room(float th) {
  float dx = -sinf(th), dy = -cosf(th), best = 1e9f;
  for (const scene::Seg &s : scene::SEGMENTS) {
    float ex = s.x2 - s.x1, ey = s.y2 - s.y1, den = dx * ey - dy * ex;
    if (fabsf(den) < 1e-6f) continue;
    float t = (s.x1 * ey - s.y1 * ex) / den, u = (s.x1 * dy - s.y1 * dx) / den;
    if (t > 0 && u >= 0 && u <= 1 && t < best) best = t;
  }
  return best;
}

struct Person { float x, y, vx, vy; bool seated; };

// Fidget sway envelope (0..1) and its derivative at time t in the loop: same as scene.fidget_at().
float fidget(float t, float *denv) {
  for (const scene::Window &f : scene::FIDGETS) {
    if (t > f.start && t < f.end) {
      float k = (float)M_PI / (f.end - f.start);
      if (denv) *denv = k * cosf(k * (t - f.start));
      return sinf(k * (t - f.start));
    }
  }
  if (denv) *denv = 0;
  return 0;
}

Person person(float t) {             // same as scene.person_at()
  t = fmodf(t, scene::LOOP);
  Person p{0, 0, 0, 0, false};
  for (int i = 0; i + 1 < scene::WAYPOINT_COUNT; i++) {
    const scene::Waypoint &a = scene::WAYPOINTS[i], &b = scene::WAYPOINTS[i + 1];
    if (t >= a.t && t <= b.t) {
      float k = (t - a.t) / (b.t - a.t);
      p.x = a.x + (b.x - a.x) * k;
      p.y = a.y + (b.y - a.y) * k;
      p.vx = (b.x - a.x) / (b.t - a.t);
      p.vy = (b.y - a.y) / (b.t - a.t);
      break;
    }
  }
  p.seated = t >= scene::SIT_START && t <= scene::SIT_END;
  if (p.seated) {                    // small sway while seated
    float w = 2 * (float)M_PI * 0.2f;
    p.x += 25 * sinf(w * t);
    p.vx += 25 * w * cosf(w * t);
    float denv, env = fidget(t, &denv);
    if (env > 0) {                   // typing / shifting: larger, faster sway
      float wx = 2 * (float)M_PI * scene::FIDGET_HZ_X, wy = 2 * (float)M_PI * scene::FIDGET_HZ_Y;
      p.x += scene::FIDGET_SWAY_X * env * sinf(wx * t);
      p.vx += scene::FIDGET_SWAY_X * (denv * sinf(wx * t) + env * wx * cosf(wx * t));
      p.y += scene::FIDGET_SWAY_Y * env * sinf(wy * t);
      p.vy += scene::FIDGET_SWAY_Y * (denv * sinf(wy * t) + env * wy * cosf(wy * t));
    }
  }
  return p;
}

uint16_t room_at(float bearing) {    // radians, clockwise from the front
  float deg = fmodf(bearing * 180.0f / (float)M_PI + 360.0f, 360.0f);
  return room[(int)(deg * 10) % TABLE];
}

uint16_t enc(int v) { return v >= 0 ? (0x8000 | (v > 0x7FFF ? 0x7FFF : v)) : (-v > 0x7FFF ? 0x7FFF : -v); }

uint8_t crc8(const uint8_t *p, size_t n) {
  uint8_t c = 0;
  while (n--) {
    c ^= *p++;
    for (int i = 0; i < 8; i++) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x4D) : (uint8_t)(c << 1);
  }
  return c;
}

}  // namespace

void begin(uint8_t lidar_model, void (*idle)()) {
  rate = lidar_model == 2 ? 21600 : 4500;
  for (int i = 0; i < TABLE; i++) {
    float d = ray_room(i * 0.1f * (float)M_PI / 180.0f);
    room[i] = d > 12000 ? 0 : (uint16_t)d;
    if (idle && i % 32 == 0) idle();
  }
}

uint32_t lidar_points_per_second() { return rate; }

void lidar_packet(float t, uint8_t *out) {
  const float step = 360.0f * SCAN_HZ / rate;
  Person p = person(t);
  float pr = sqrtf(p.x * p.x + p.y * p.y);
  float pa = atan2f(-p.x, -p.y);                        // person bearing, radians, clockwise from the front
  float half = pr > scene::PERSON_RADIUS ? asinf(scene::PERSON_RADIUS / pr) : (float)M_PI;

  uint8_t *q = out;
  *q++ = 0x54;
  *q++ = 0x2C;
  uint16_t speed = (uint16_t)(SCAN_HZ * 360);
  *q++ = speed; *q++ = speed >> 8;
  uint16_t a0 = (uint16_t)lroundf(angle * 100) % 36000;
  *q++ = a0; *q++ = a0 >> 8;
  float a = angle;
  for (int i = 0; i < 12; i++, a += step) {
    if (a >= 360.0f) a -= 360.0f;
    float d = room[(int)(a * 10) % TABLE];
    float th = a * (float)M_PI / 180.0f;
    float delta = remainderf(th - pa, 2 * (float)M_PI);
    if (fabsf(delta) < half) {                          // the ray may hit the person
      float s = pr * sinf(delta);
      float hit = pr * cosf(delta) - sqrtf(scene::PERSON_RADIUS * scene::PERSON_RADIUS - s * s);
      if (hit > 0 && (d == 0 || hit < d)) d = hit;
    }
    uint16_t dist = d > 0 ? (uint16_t)(d + noise()) : 0;
    *q++ = dist; *q++ = dist >> 8;
    *q++ = d == 0 ? 0 : (d < 3000 ? 200 : 120);
  }
  a -= step;                                            // last point's angle
  uint16_t a1 = (uint16_t)lroundf(a * 100) % 36000;
  *q++ = a1; *q++ = a1 >> 8;
  uint16_t ts = (uint32_t)(t * 1000) % 30000;
  *q++ = ts; *q++ = ts >> 8;
  *q = crc8(out, LIDAR_PACKET - 1);
  angle = fmodf(angle + 12 * step, 360.0f);
}

void ld2450_frame(float t, uint8_t *out) {
  Person p = person(t);
  static const uint8_t head[4] = {0xAA, 0xFF, 0x03, 0x00};
  memset(out, 0, LD2450_FRAME);
  memcpy(out, head, 4);
  out[28] = 0x55;
  out[29] = 0xCC;
  float dy = p.y - LD2450_Y, r = sqrtf(p.x * p.x + dy * dy);
  float d = sqrtf(p.x * p.x + p.y * p.y);
  uint16_t wall = room_at(atan2f(-p.x, -p.y));
  bool hidden = wall != 0 && wall < d - scene::PERSON_RADIUS;
  if (r > LD2450_RANGE || fabsf(atan2f(p.x, -dy)) > LD2450_FOV || hidden) return;   // no target
  int rx = (int)p.x;
  int ry = (int)((LD2450_Y - p.y) / cosf(LD2450_TILT));
  float v = r > 1 ? (p.vx * p.x + p.vy * dy) / r : 0;   // mm/s, positive = away from the robot
  uint16_t f[4] = {enc(rx), enc(ry), enc((int)(-v / 10)), 320};
  for (int i = 0; i < 4; i++) { out[4 + 2 * i] = f[i]; out[5 + 2 * i] = f[i] >> 8; }
}

size_t vitals(float t, uint8_t *out) {
  Person p = person(t);
  float dist = sqrtf((p.x - scene::MR60_X) * (p.x - scene::MR60_X) + (p.y - scene::MR60_Y) * (p.y - scene::MR60_Y));
  // same as scene.vitals_at(): nothing while walking or fidgeting
  bool valid = p.seated && fidget(fmodf(t, scene::LOOP), nullptr) <= 0;
  float br = 0, hr = 0, bw = 0, hw = 0;
  if (valid) {
    br = 14.0f + 1.5f * sinf(2 * (float)M_PI * t / 23.0f) + 0.3f * sinf(2 * (float)M_PI * t / 3.7f);
    hr = 68.0f + 4.0f * sinf(2 * (float)M_PI * t / 17.0f) + 1.2f * sinf(2 * (float)M_PI * t / 2.3f);
    bw = sinf(2 * (float)M_PI * 14.0f / 60.0f * t);
    hw = sinf(2 * (float)M_PI * 68.0f / 60.0f * t);
  }
  uint8_t *q = out;
  *q++ = valid ? 1 : 0;
  uint16_t b = (uint16_t)lroundf(br * 100), h = (uint16_t)lroundf(hr * 100);
  int16_t bws = (int16_t)lroundf(bw * 32767), hws = (int16_t)lroundf(hw * 32767);
  uint16_t dmm = dist > 65535 ? 65535 : (uint16_t)dist;
  const uint16_t fields[5] = {b, h, (uint16_t)bws, (uint16_t)hws, dmm};
  for (uint16_t v : fields) { *q++ = v; *q++ = v >> 8; }
  return q - out;
}

}  // namespace sim
