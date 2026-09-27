// Built-in sounds ("earcons") played by SOUND, without streaming. Portable C++ (no Arduino),
// unit-tested with `pio test -e native`.
//
// Each earcon is a few tones (fixed or swept frequency) generated on the fly with a smooth
// attack and release, so there are no sample tables in flash and no clicks. The ids are part of
// the protocol (docs/audio.md, host/marvin_host/protocol.py SOUNDS); unknown ids play nothing.
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace audio {

enum SoundId : uint8_t {
  SOUND_CHIRP = 1,  // the robot's "hm?": two quick rising sweeps
  SOUND_BEEP = 2,   // plain 1 kHz beep
  SOUND_WAKE = 3,   // listening starts: two notes up
  SOUND_DONE = 4,   // listening ends: two notes down
  SOUND_ERROR = 5,  // something went wrong: two low notes down
  SOUND_HELLO = 6,  // greeting: a rising arpeggio
};

constexpr uint8_t SOUND_MAX_ID = 6;

class Earcon {
 public:
  explicit Earcon(uint32_t rate = 16000) : rate_(rate) {}

  // Starts sound `id` from its beginning (replacing the one playing). False if the id is unknown.
  bool start(uint8_t id);
  void stop() { tone_ = count_ = 0; }
  bool active() const { return tone_ < count_; }

  // Writes the next n samples of the sound (full scale up to about 0.8, before the volume) and
  // silence after its end. Returns how many samples belong to the sound.
  size_t render(int16_t *out, size_t n);

  // Length of sound `id` in milliseconds, 0 if unknown.
  static uint32_t duration_ms(uint8_t id);

 private:
  struct Tone;
  static const Tone *tones(uint8_t id, size_t *count);

  uint32_t rate_;
  const Tone *list_ = nullptr;
  size_t count_ = 0;
  size_t tone_ = 0;       // current tone
  uint32_t pos_ = 0;      // sample within the current tone
  float phase_ = 0.0f;    // radians, continuous across tones
};

}  // namespace audio
