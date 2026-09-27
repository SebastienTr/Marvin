// SPDX-License-Identifier: MIT
#include "audio_dsp.h"

#include <math.h>

namespace audio {

namespace {

inline int16_t saturate(float v) {
  if (v > 32767.0f) return 32767;
  if (v < -32768.0f) return -32768;
  return (int16_t)lrintf(v);
}

}  // namespace

void DcBlocker::process(int16_t *x, size_t n, float gain) {
  for (size_t i = 0; i < n; i++) {
    float in = x[i];
    float y = in - x1_ + a_ * y1_;
    x1_ = in;
    y1_ = y;
    x[i] = saturate(y * gain);
  }
}

void scale(int16_t *x, size_t n, float gain) {
  for (size_t i = 0; i < n; i++) x[i] = saturate(x[i] * gain);
}

void mix(int16_t *dst, const int16_t *src, size_t n) {
  for (size_t i = 0; i < n; i++) {
    int32_t v = (int32_t)dst[i] + src[i];
    dst[i] = v > 32767 ? 32767 : v < -32768 ? -32768 : (int16_t)v;
  }
}

uint16_t peak(const int16_t *x, size_t n) {
  int32_t m = 0;
  for (size_t i = 0; i < n; i++) {
    int32_t a = x[i] < 0 ? -(int32_t)x[i] : x[i];
    if (a > m) m = a;
  }
  return m > 65535 ? 65535 : (uint16_t)m;
}

float volume_gain(uint8_t volume, float cap) {
  float v = (volume > 100 ? 100 : volume) / 100.0f;
  return cap * v * v;
}

float db_to_gain(float db) { return powf(10.0f, db / 20.0f); }

}  // namespace audio
