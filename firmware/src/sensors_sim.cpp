// SPDX-License-Identifier: MIT
#include "sensors_sim.h"

#include <math.h>
#include <string.h>

namespace sim {
namespace {

// Device frame, mm: X right (seen facing the robot), Y backwards, the robot looks at -Y.
struct Seg { float x1, y1, x2, y2; };
const Seg ROOM[] = {
    {-2000, 400, 2000, 400},     {2000, 400, 2000, -3100},     {2000, -3100, -2000, -3100},
    {-2000, -3100, -2000, 400},  {-2000, -1200, -1400, -1200}, {-1400, -1200, -1400, -1800},
    {-1400, -1800, -2000, -1800},
};
constexpr float PERSON_R = 180.0f;
constexpr float SCAN_HZ = 10.0f;
constexpr float LD2450_Y = -33.1f;
constexpr float LD2450_TILT = 10.0f * (float)M_PI / 180.0f;

constexpr int TABLE = 1440;          // room distances every 0.25 deg, computed once at boot
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
  for (const Seg &s : ROOM) {
    float ex = s.x2 - s.x1, ey = s.y2 - s.y1, den = dx * ey - dy * ex;
    if (fabsf(den) < 1e-6f) continue;
    float t = (s.x1 * ey - s.y1 * ex) / den, u = (s.x1 * dy - s.y1 * dx) / den;
    if (t > 0 && u >= 0 && u <= 1 && t < best) best = t;
  }
  return best;
}

struct Person { float x, y, vx, vy; };

Person person(float t) {             // 40 s loop: walks around, comes to the desk, sits
  t = fmodf(t, 40.0f);
  if (t < 20.0f) {
    float w = 2 * (float)M_PI / 20.0f;
    return {1200 * cosf(w * t), -1700 + 900 * sinf(w * t), -1200 * w * sinf(w * t), 900 * w * cosf(w * t)};
  }
  if (t < 24.0f) {
    float k = (t - 20.0f) / 4.0f;
    return {1200 - 1200 * k, -1700 + 1000 * k, -300, 250};
  }
  float w = 2 * (float)M_PI * 0.25f;
  return {30 * sinf(w * t), -700, 30 * w * cosf(w * t), 0};
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

void begin(uint8_t lidar_model) {
  rate = lidar_model == 2 ? 21600 : 4500;
  for (int i = 0; i < TABLE; i++) {
    float d = ray_room(i * 0.25f * (float)M_PI / 180.0f);
    room[i] = d > 12000 ? 0 : (uint16_t)d;
  }
}

uint32_t lidar_points_per_second() { return rate; }

void lidar_packet(float t, uint8_t *out) {
  const float step = 360.0f * SCAN_HZ / rate;
  Person p = person(t);
  float pr = sqrtf(p.x * p.x + p.y * p.y);
  float pa = atan2f(-p.x, -p.y);                        // person bearing, radians, clockwise from the front
  float half = pr > PERSON_R ? asinf(PERSON_R / pr) : (float)M_PI;

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
    float d = room[(int)(a * 4) % TABLE];
    float th = a * (float)M_PI / 180.0f;
    float delta = remainderf(th - pa, 2 * (float)M_PI);
    if (fabsf(delta) < half) {                          // the ray may hit the person
      float s = pr * sinf(delta);
      float hit = pr * cosf(delta) - sqrtf(PERSON_R * PERSON_R - s * s);
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
  int rx = (int)p.x;
  int ry = (int)((LD2450_Y - p.y) / cosf(LD2450_TILT));
  float dy = p.y - LD2450_Y, r = sqrtf(p.x * p.x + dy * dy);
  float v = r > 1 ? (p.vx * p.x + p.vy * dy) / r : 0;   // mm/s, positive = away from the robot
  int speed = (int)(-v / 10);
  static const uint8_t head[4] = {0xAA, 0xFF, 0x03, 0x00};
  memset(out, 0, LD2450_FRAME);
  memcpy(out, head, 4);
  uint16_t f[4] = {enc(rx), enc(ry), enc(speed), 320};
  for (int i = 0; i < 4; i++) { out[4 + 2 * i] = f[i]; out[5 + 2 * i] = f[i] >> 8; }
  out[28] = 0x55;
  out[29] = 0xCC;
}

}  // namespace sim
