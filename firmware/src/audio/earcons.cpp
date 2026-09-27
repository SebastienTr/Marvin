// SPDX-License-Identifier: MIT
#include "earcons.h"

#include <math.h>
#include <string.h>

namespace audio {

// One tone: frequency sweeping linearly from f0 to f1 (Hz) over ms milliseconds, at amp percent
// of the earcon full scale. f0 == 0 is a silence.
struct Earcon::Tone {
  uint16_t f0, f1, ms;
  uint8_t amp;
};

namespace {

constexpr float FULL_SCALE = 0.8f * 32767.0f;
constexpr float TWO_PI = 6.28318530718f;
constexpr uint32_t RAMP_MS = 5;  // raised-cosine attack and release of every tone

}  // namespace

const Earcon::Tone *Earcon::tones(uint8_t id, size_t *count) {
  static const Tone CHIRP[] = {{1200, 2400, 50, 80}, {0, 0, 30, 0}, {1600, 3200, 60, 80}};
  static const Tone BEEP[] = {{1000, 1000, 120, 70}};
  static const Tone WAKE[] = {{660, 660, 80, 90}, {990, 990, 120, 90}};
  static const Tone DONE[] = {{990, 990, 80, 90}, {660, 660, 120, 90}};
  static const Tone ERR[] = {{330, 330, 120, 100}, {0, 0, 60, 0}, {262, 262, 180, 100}};
  static const Tone HELLO[] = {{523, 523, 90, 80}, {659, 659, 90, 80}, {784, 784, 90, 80}, {1047, 1047, 160, 80}};
#define MARVIN_EARCON(name) \
  *count = sizeof(name) / sizeof(name[0]); \
  return name
  switch (id) {
    case SOUND_CHIRP: MARVIN_EARCON(CHIRP);
    case SOUND_BEEP: MARVIN_EARCON(BEEP);
    case SOUND_WAKE: MARVIN_EARCON(WAKE);
    case SOUND_DONE: MARVIN_EARCON(DONE);
    case SOUND_ERROR: MARVIN_EARCON(ERR);
    case SOUND_HELLO: MARVIN_EARCON(HELLO);
    default: *count = 0; return nullptr;
  }
#undef MARVIN_EARCON
}

uint32_t Earcon::duration_ms(uint8_t id) {
  size_t n;
  const Tone *t = tones(id, &n);
  uint32_t ms = 0;
  for (size_t i = 0; i < n; i++) ms += t[i].ms;
  return ms;
}

bool Earcon::start(uint8_t id) {
  size_t n;
  const Tone *t = tones(id, &n);
  if (!t) return false;
  list_ = t;
  count_ = n;
  tone_ = 0;
  pos_ = 0;
  phase_ = 0.0f;
  return true;
}

size_t Earcon::render(int16_t *out, size_t n) {
  size_t i = 0;
  while (i < n && tone_ < count_) {
    const Tone &t = list_[tone_];
    const uint32_t len = t.ms * rate_ / 1000, ramp = RAMP_MS * rate_ / 1000;
    for (; i < n && pos_ < len; i++, pos_++) {
      if (!t.f0) {
        out[i] = 0;
        continue;
      }
      float frac = (float)pos_ / (float)len;
      float f = t.f0 + (t.f1 - t.f0) * frac;
      phase_ += TWO_PI * f / (float)rate_;
      if (phase_ > TWO_PI) phase_ -= TWO_PI;
      uint32_t edge = pos_ < len - 1 - pos_ ? pos_ : len - 1 - pos_;  // distance to the nearest end
      float env = edge >= ramp ? 1.0f : 0.5f - 0.5f * cosf(3.14159265f * (float)edge / (float)ramp);
      out[i] = (int16_t)lrintf(sinf(phase_) * env * FULL_SCALE * t.amp / 100.0f);
    }
    if (pos_ >= len) {
      tone_++;
      pos_ = 0;
    }
  }
  size_t sound = i;
  if (i < n) memset(out + i, 0, (n - i) * sizeof(int16_t));
  return sound;
}

}  // namespace audio
