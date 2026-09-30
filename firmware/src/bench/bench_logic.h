// SPDX-License-Identifier: MIT
//
// The bench board's small parts, without the hardware: a debounced push button, the buzzer's
// tone sequencer, and the status LED's patterns. Used by bench/bench_io.h on the board.
//
// Portable C++ (no Arduino), unit-tested with `pio test -e native`. Time is supplied by the
// caller in milliseconds and may wrap.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace bench {

// A push button to ground with the pin's pull-up: `raw_pressed` is the pin read low.
// A change counts once the pin has kept its new level for DEBOUNCE_MS (contact bounce lasts a
// few milliseconds). update() returns true once per press, on the stable released -> pressed
// change. The first sample sets the state without an event, so a button held at boot does not
// count as a press.
class Button {
 public:
  static constexpr uint32_t DEBOUNCE_MS = 30;

  bool update(bool raw_pressed, uint32_t now_ms);
  bool pressed() const { return stable_; }

 private:
  bool started_ = false;
  bool stable_ = false;
  bool raw_ = false;
  uint32_t raw_since_ = 0;
};

// Plays the host's SOUND ids (audio/earcons.h) and a short key click as a sequence of square-wave
// tones on a passive buzzer: the same notes as the robot's earcons, without the envelope (a
// buzzer has no volume). freq() says what the buzzer should play now; the caller changes the
// LEDC tone only when it changes. A new sound replaces the one playing.
class Chime {
 public:
  static constexpr uint16_t CLICK_HZ = 2400;
  static constexpr uint16_t CLICK_MS = 12;
  // Sweeps move in steps of this many hertz, so the LEDC timer is not reprogrammed every
  // millisecond for a change nobody hears.
  static constexpr uint16_t SWEEP_STEP_HZ = 50;

  // Starts sound `id` at now_ms. False (and nothing changes) for an unknown id.
  bool start(uint8_t id, uint32_t now_ms);
  // A key click, unless a sound is playing.
  void click(uint32_t now_ms);
  void stop() { count_ = 0; }

  // The frequency to play at now_ms, 0 for silence. Past the end, 0 and inactive.
  uint16_t freq(uint32_t now_ms);
  bool active() const { return count_ > 0; }

 private:
  static constexpr size_t MAX_TONES = 8;
  struct Tone {
    uint16_t f0, f1, ms;
  };
  Tone tones_[MAX_TONES];
  size_t count_ = 0;
  uint32_t start_ms_ = 0;
};

// Where the link is, for the status LED and the OLED's status line.
enum class LinkPhase : uint8_t {
  WIFI,       // joining Wi-Fi
  SEARCHING,  // on Wi-Fi, broadcasting HELLO until a host answers
  LINKED,     // a host answers
};

// Single-colour LED: slow blink while joining Wi-Fi (1 Hz), fast blink while looking for the host
// (4 Hz), on while linked.
bool status_led_on(LinkPhase phase, uint32_t now_ms);

struct Rgb8 {
  uint8_t r, g, b;
};
// RGB LED, dim: blue slow blink (Wi-Fi), amber fast blink (host), steady green (linked).
Rgb8 status_color(LinkPhase phase, uint32_t now_ms);

}  // namespace bench
