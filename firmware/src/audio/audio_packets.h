// Payloads of the audio messages (protocol v1, docs/protocol.md): AUDIO_IN, AUDIO_OUT, AUDIO_CTRL
// and SOUND. Portable C++ (no Arduino), unit-tested with `pio test -e native`.
//
//   AUDIO_IN   robot -> host   sample index u32, then N x i16 PCM (N = 320, 20 ms)
//   AUDIO_OUT  host -> robot   stream id u16, sample index u32, then N x i16 PCM (1 <= N <= 480)
//   AUDIO_CTRL host -> robot   command u8, argument u8 (proto::AudioCommand)
//   SOUND      host -> robot   sound id u8 (audio/earcons.h)
//
// Sample indexes count samples since the start of the stream (u32, wraps after 74 hours at
// 16 kHz): the receiver compares a datagram's index with the one it expected to spot lost,
// duplicated or late datagrams, independently of the header sequence number.
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "protocol.h"

namespace audio_packets {

constexpr size_t AUDIO_IN_HEADER = 4;   // sample index
constexpr size_t AUDIO_OUT_HEADER = 6;  // stream id + sample index

// Signed distance from sample index `from` to `to`, wrap-aware (positive: `to` is later).
inline int32_t index_diff(uint32_t to, uint32_t from) { return (int32_t)(to - from); }

// Writes an AUDIO_IN payload into p, returns its size (4 + 2 n).
inline size_t encode_audio_in(uint8_t *p, uint32_t sample_index, const int16_t *pcm, size_t n) {
  uint8_t *q = proto::put32(p, sample_index);
  for (size_t i = 0; i < n; i++) q = proto::put16(q, (uint16_t)pcm[i]);
  return (size_t)(q - p);
}

struct AudioOut {
  uint16_t stream;
  uint32_t sample_index;
  const uint8_t *pcm;  // n little-endian int16 samples, not aligned: read with sample(i)
  size_t n;

  int16_t sample(size_t i) const { return (int16_t)proto::get16(pcm + 2 * i); }
};

// Decodes an AUDIO_OUT payload. False if it is malformed (odd PCM length, no samples, too many).
inline bool decode_audio_out(const uint8_t *p, size_t len, AudioOut *out) {
  if (len < AUDIO_OUT_HEADER + 2 || (len - AUDIO_OUT_HEADER) % 2) return false;
  size_t n = (len - AUDIO_OUT_HEADER) / 2;
  if (n > proto::AUDIO_OUT_MAX_SAMPLES) return false;
  out->stream = proto::get16(p);
  out->sample_index = proto::get32(p + 2);
  out->pcm = p + AUDIO_OUT_HEADER;
  out->n = n;
  return true;
}

struct AudioCtrl {
  uint8_t command;   // proto::AudioCommand
  uint8_t argument;  // 0 when unused
};

// Decodes an AUDIO_CTRL payload (a missing argument reads as 0). False if empty.
inline bool decode_audio_ctrl(const uint8_t *p, size_t len, AudioCtrl *out) {
  if (len < 1) return false;
  out->command = p[0];
  out->argument = len >= 2 ? p[1] : 0;
  return true;
}

// Decodes a SOUND payload. False if empty.
inline bool decode_sound(const uint8_t *p, size_t len, uint8_t *id) {
  if (len < 1) return false;
  *id = p[0];
  return true;
}

}  // namespace audio_packets
