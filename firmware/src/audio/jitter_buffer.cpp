// SPDX-License-Identifier: MIT
#include "jitter_buffer.h"

#include <string.h>

#include "audio_packets.h"

namespace audio {

JitterBuffer::JitterBuffer(int16_t *storage, size_t capacity, const Config &cfg)
    : buf_(storage), cap_(capacity), cfg_(cfg) {
  if (cfg_.prebuffer > cap_) cfg_.prebuffer = cap_;
}

void JitterBuffer::clear() {
  head_ = 0;
  size_ = 0;
  playing_ = false;
}

size_t JitterBuffer::write(const int16_t *pcm, size_t n) {
  size_t room = cap_ - size_;
  if (n > room) {
    counters_.overflow_samples += n - room;
    n = room;
  }
  size_t tail = (head_ + size_) % cap_;
  for (size_t done = 0; done < n;) {
    size_t run = cap_ - tail;
    if (run > n - done) run = n - done;
    if (pcm) memcpy(buf_ + tail, pcm + done, run * sizeof(int16_t));
    else memset(buf_ + tail, 0, run * sizeof(int16_t));
    done += run;
    tail = (tail + run) % cap_;
  }
  size_ += n;
  return n;
}

void JitterBuffer::push(uint16_t stream, uint32_t index, const int16_t *pcm, size_t n, uint32_t now_ms) {
  if (!n || !cap_) return;
  if (has_stopped_ && stream == stopped_stream_) {
    counters_.stale_datagrams++;
    return;
  }
  if (!has_stream_ || stream != stream_) {  // a new stream replaces whatever was playing
    clear();
    has_stream_ = true;
    has_stopped_ = false;
    stream_ = stream;
    expected_ = index;
    counters_.streams++;
  }
  int32_t ahead = audio_packets::index_diff(index, expected_);
  if (ahead < 0) {  // late or duplicated: keep only the part after what we already have
    size_t skip = (size_t)(-(int64_t)ahead);
    if (skip >= n) {
      counters_.late_samples += n;
      return;
    }
    counters_.late_samples += skip;
    pcm += skip;
    n -= skip;
    index += skip;
    ahead = 0;
  }
  if (ahead > 0) {
    if ((size_t)ahead <= cfg_.max_gap) counters_.lost_samples += write(nullptr, (size_t)ahead);
    else counters_.resyncs++;
  }
  counters_.datagrams++;
  counters_.samples += write(pcm, n);
  expected_ = index + (uint32_t)n;
  last_push_ms_ = now_ms;
}

size_t JitterBuffer::pull(int16_t *out, size_t n, uint32_t now_ms) {
  bool arriving = has_stream_ && now_ms - last_push_ms_ < cfg_.drain_after_ms;
  if (!playing_ && size_ > 0 && (size_ >= cfg_.prebuffer || !arriving)) playing_ = true;
  size_t got = 0;
  if (playing_) {
    got = n < size_ ? n : size_;
    for (size_t done = 0; done < got;) {
      size_t run = cap_ - head_;
      if (run > got - done) run = got - done;
      memcpy(out + done, buf_ + head_, run * sizeof(int16_t));
      done += run;
      head_ = (head_ + run) % cap_;
    }
    size_ -= got;
    if (got < n) {  // ran dry: prebuffer again before the next sample
      playing_ = false;
      head_ = 0;
      if (arriving) counters_.underruns++;
    }
  }
  if (got < n) memset(out + got, 0, (n - got) * sizeof(int16_t));
  return got;
}

void JitterBuffer::stop() {
  clear();
  if (has_stream_) {
    has_stopped_ = true;
    stopped_stream_ = stream_;
  }
  has_stream_ = false;
}

}  // namespace audio
