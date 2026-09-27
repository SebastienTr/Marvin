// Small sample-processing helpers for the microphone and the speaker. Portable C++ (no Arduino),
// unit-tested with `pio test -e native`.
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace audio {

// Removes the DC offset of the PDM microphone: one-pole high-pass, y = x - x[-1] + a y[-1],
// with a = 0.995 (corner near 13 Hz at 16 kHz). Keeps its state between blocks.
class DcBlocker {
 public:
  explicit DcBlocker(float a = 0.995f) : a_(a) {}
  // Filters n samples in place, multiplied by gain, saturated to int16.
  void process(int16_t *x, size_t n, float gain = 1.0f);
  void reset() { x1_ = y1_ = 0.0f; }

 private:
  float a_, x1_ = 0.0f, y1_ = 0.0f;
};

// Multiplies n samples in place by gain, saturated to int16.
void scale(int16_t *x, size_t n, float gain);

// Adds src to dst, saturated to int16.
void mix(int16_t *dst, const int16_t *src, size_t n);

// Largest absolute sample value (for level logs).
uint16_t peak(const int16_t *x, size_t n);

// Speaker gain for a volume 0..100 (clamped): cap * (volume / 100)^2, roughly even loudness steps.
// cap is the hard ceiling of the whole volume range (docs/audio.md: 0.5 for the 8 ohm 1 W speaker).
float volume_gain(uint8_t volume, float cap);

// 10^(db / 20).
float db_to_gain(float db);

}  // namespace audio
