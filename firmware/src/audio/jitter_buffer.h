// Jitter buffer for the speaker stream (AUDIO_OUT). Portable C++ (no Arduino), unit-tested with
// `pio test -e native`. Not thread-safe: the caller serialises push() and pull() (audio.cpp holds
// a mutex around both).
//
// - Wi-Fi delivers AUDIO_OUT in bursts; the buffer holds back playback until `prebuffer` samples
//   are queued (100 ms by default), then plays at the I2S clock. If it runs dry it goes back to
//   prebuffering, so a hiccup costs one gap instead of a crackle on every datagram.
// - A short stream (a tail shorter than the prebuffer) still plays: once nothing new arrived for
//   `drain_after_ms`, whatever is queued is played.
// - Sample indexes place each datagram in the stream: a lost datagram (index ahead of the one
//   expected) becomes silence of the same length, so the rest stays in time, up to `max_gap`
//   samples; a late or duplicated datagram (index behind) is dropped, or trimmed when it overlaps.
// - A new stream id flushes the old stream. stop() flushes too and ignores the stopped stream's
//   datagrams still in flight, until the host starts another stream id.
// - When the host sends faster than real time and the buffer is full, the newest samples are
//   dropped and counted.
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace audio {

class JitterBuffer {
 public:
  struct Config {
    size_t prebuffer = 1600;          // samples queued before playback starts (100 ms at 16 kHz)
    size_t max_gap = 3200;            // lost samples filled with silence; larger gaps resync (200 ms)
    uint32_t drain_after_ms = 60;     // play a short tail after this long without new datagrams
  };

  struct Counters {
    uint32_t datagrams = 0;           // accepted AUDIO_OUT datagrams
    uint32_t samples = 0;             // samples queued from them
    uint32_t lost_samples = 0;        // silence inserted for lost datagrams
    uint32_t late_samples = 0;        // dropped: late, duplicated or overlapping
    uint32_t overflow_samples = 0;    // dropped: the buffer was full
    uint32_t stale_datagrams = 0;     // datagrams of a stopped stream
    uint32_t resyncs = 0;             // gaps too large to fill
    uint32_t underruns = 0;           // ran dry while the stream was still arriving
    uint32_t streams = 0;             // streams started
  };

  // storage: capacity samples owned by the caller (e.g. 12000 = 750 ms).
  JitterBuffer(int16_t *storage, size_t capacity, const Config &cfg);
  JitterBuffer(int16_t *storage, size_t capacity) : JitterBuffer(storage, capacity, Config()) {}

  // Queues n samples of stream `stream`, whose first sample has index `index`. now_ms: a clock.
  void push(uint16_t stream, uint32_t index, const int16_t *pcm, size_t n, uint32_t now_ms);

  // Fills out[0..n) with the next samples to play, silence where there are none.
  // Returns how many came from the stream (0 while prebuffering or idle).
  size_t pull(int16_t *out, size_t n, uint32_t now_ms);

  // Drops everything and ignores the current stream's remaining datagrams.
  void stop();

  size_t level() const { return size_; }       // samples queued
  size_t capacity() const { return cap_; }
  bool playing() const { return playing_; }
  bool active() const { return size_ > 0; }  // something is queued or playing
  const Counters &counters() const { return counters_; }

 private:
  void clear();
  size_t write(const int16_t *pcm, size_t n);  // pcm == nullptr writes silence; returns written

  int16_t *buf_;
  size_t cap_;
  Config cfg_;
  size_t head_ = 0;   // next sample to play
  size_t size_ = 0;   // samples queued
  bool playing_ = false;
  bool has_stream_ = false;
  uint16_t stream_ = 0;
  uint32_t expected_ = 0;   // index of the next sample expected in the stream
  bool has_stopped_ = false;
  uint16_t stopped_stream_ = 0;
  uint32_t last_push_ms_ = 0;
  Counters counters_;
};

}  // namespace audio
