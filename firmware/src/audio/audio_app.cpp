// SPDX-License-Identifier: MIT
#include "audio_app.h"

#if defined(MARVIN_HAS_AUDIO) && defined(ARDUINO) && defined(ESP32)

#include <Arduino.h>
#include <driver/gpio.h>
#include <driver/i2s.h>
#include <esp_heap_caps.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#include <freertos/task.h>
#include <stdio.h>
#include <string.h>

#include "audio_dsp.h"
#include "audio_packets.h"
#include "earcons.h"
#include "jitter_buffer.h"
#include "protocol.h"

#ifndef MARVIN_AUDIO_VOLUME
#define MARVIN_AUDIO_VOLUME 60
#endif
#ifndef MARVIN_AUDIO_GAIN_CAP
#define MARVIN_AUDIO_GAIN_CAP 50
#endif
#ifndef MARVIN_MIC_GAIN_DB
#define MARVIN_MIC_GAIN_DB 12
#endif
#ifndef MARVIN_MIC_RIGHT_SLOT
#define MARVIN_MIC_RIGHT_SLOT 0
#endif

namespace audio_app {
namespace {

// Pins, docs/wiring.md.
constexpr int AMP_BCLK = 8;    // D9
constexpr int AMP_WS = 43;     // D6, UART0 TX at boot
constexpr int AMP_DIN = 3;     // D2
constexpr int MIC_CLK = 42;    // Sense board, internal
constexpr int MIC_DATA = 41;
constexpr i2s_port_t SPK_PORT = I2S_NUM_1;
constexpr i2s_port_t MIC_PORT = I2S_NUM_0;  // PDM RX exists on I2S0 only

constexpr uint32_t RATE = proto::AUDIO_RATE;
constexpr size_t SPK_BLOCK = 160;                   // 10 ms per i2s_write
constexpr size_t MIC_BLOCK = proto::AUDIO_IN_SAMPLES;  // 20 ms per AUDIO_IN
constexpr size_t JITTER_CAPACITY = 12000;           // 750 ms
constexpr int MIC_QUEUE = 8;                        // 160 ms of microphone blocks
constexpr int MIC_WARMUP_BLOCKS = 5;                // the PDM filter settles in ~100 ms: dropped
constexpr float GAIN_CAP = MARVIN_AUDIO_GAIN_CAP / 100.0f;

// Core 1 with the Arduino loop (priority 1): Wi-Fi, the face and the camera live on core 0.
// Above the loop so a busy loop cannot starve the I2S DMA; each block takes well under 1 ms.
constexpr BaseType_t CORE = 1;
constexpr UBaseType_t SPK_PRIORITY = 6;
constexpr UBaseType_t MIC_PRIORITY = 5;

struct MicBlock {
  uint64_t t_us;
  uint32_t index;
  int16_t pcm[MIC_BLOCK];
};

bool ok = false;
SemaphoreHandle_t lock = nullptr;  // guards jitter, earcon and the counters below
audio::JitterBuffer *jitter = nullptr;
audio::Earcon earcon(RATE);
QueueHandle_t mic_queue = nullptr;
TaskHandle_t mic_task_handle = nullptr;

volatile float speaker_gain = 0.0f;
volatile float mic_gain = 1.0f;
volatile bool mic_wanted = false;  // the host asked for the microphone
volatile bool linked = false;

// counters (under lock)
uint32_t sounds = 0, mic_blocks = 0, mic_dropped = 0;
uint16_t mic_peak = 0;
audio::JitterBuffer::Counters last;
uint32_t last_sounds = 0, last_mic_blocks = 0, last_mic_dropped = 0;

struct Lock {
  Lock() { xSemaphoreTake(lock, portMAX_DELAY); }
  ~Lock() { xSemaphoreGive(lock); }
};

void set_volume(uint8_t v) { speaker_gain = audio::volume_gain(v, GAIN_CAP); }

// Speaker: 10 ms blocks at the I2S clock, forever.
void speaker_task(void *) {
  static int16_t mono[SPK_BLOCK], fx[SPK_BLOCK], stereo[2 * SPK_BLOCK];
  for (;;) {
    {
      Lock l;
      jitter->pull(mono, SPK_BLOCK, millis());
      if (earcon.active()) {
        earcon.render(fx, SPK_BLOCK);
        audio::mix(mono, fx, SPK_BLOCK);
      }
    }
    audio::scale(mono, SPK_BLOCK, speaker_gain);
    for (size_t i = 0; i < SPK_BLOCK; i++) stereo[2 * i] = stereo[2 * i + 1] = mono[i];
    size_t written = 0;
    i2s_write(SPK_PORT, stereo, sizeof(stereo), &written, portMAX_DELAY);
  }
}

// Microphone: runs the PDM clock only while wanted and linked.
void mic_task(void *) {
  static MicBlock block;
  audio::DcBlocker dc;
  bool running = false;
  int warmup = 0;
  uint32_t index = 0;
  for (;;) {
    if (!(mic_wanted && linked)) {
      if (running) {
        i2s_stop(MIC_PORT);
        running = false;
      }
      ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(100));  // woken by MIC_START / link up
      continue;
    }
    if (!running) {
      i2s_zero_dma_buffer(MIC_PORT);
      i2s_start(MIC_PORT);
      running = true;
      warmup = MIC_WARMUP_BLOCKS;
      index = 0;  // a new stream starts at sample 0
      xQueueReset(mic_queue);  // nothing from a previous stream
      dc.reset();
    }
    size_t got = 0;
    if (i2s_read(MIC_PORT, block.pcm, sizeof(block.pcm), &got, pdMS_TO_TICKS(100)) != ESP_OK ||
        got != sizeof(block.pcm))
      continue;
    block.t_us = (uint64_t)esp_timer_get_time() - MIC_BLOCK * 1000000ull / RATE;
    if (warmup > 0) {
      warmup--;
      dc.process(block.pcm, MIC_BLOCK);  // let the filter settle too
      continue;
    }
    dc.process(block.pcm, MIC_BLOCK, mic_gain);
    block.index = index;
    index += MIC_BLOCK;
    uint16_t pk = audio::peak(block.pcm, MIC_BLOCK);
    bool queued = xQueueSend(mic_queue, &block, 0) == pdTRUE;
    Lock l;
    if (queued) mic_blocks++;
    else mic_dropped++;
    if (pk > mic_peak) mic_peak = pk;
  }
}

bool begin_speaker() {
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
  cfg.sample_rate = RATE;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 4;  // 40 ms in flight
  cfg.dma_buf_len = SPK_BLOCK;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = true;  // silence, not a looped buffer, if the task is ever late
  if (i2s_driver_install(SPK_PORT, &cfg, 0, nullptr) != ESP_OK) return false;

  // Take GPIO43 back from UART0 TX (see audio_app.h), then give it to I2S1 WS.
  gpio_reset_pin((gpio_num_t)AMP_WS);
  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;  // the MAX98357A needs no MCLK; never let it default to GPIO0
  pins.bck_io_num = AMP_BCLK;
  pins.ws_io_num = AMP_WS;
  pins.data_out_num = AMP_DIN;
  pins.data_in_num = I2S_PIN_NO_CHANGE;
  if (i2s_set_pin(SPK_PORT, &pins) != ESP_OK) return false;
  i2s_zero_dma_buffer(SPK_PORT);
  return true;
}

bool begin_mic() {
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_PDM);
  cfg.sample_rate = RATE;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = MARVIN_MIC_RIGHT_SLOT ? I2S_CHANNEL_FMT_ONLY_RIGHT : I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 4;  // 80 ms
  cfg.dma_buf_len = MIC_BLOCK;
  cfg.use_apll = false;
  if (i2s_driver_install(MIC_PORT, &cfg, 0, nullptr) != ESP_OK) return false;
  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = I2S_PIN_NO_CHANGE;
  pins.ws_io_num = MIC_CLK;  // in PDM mode the clock is on the WS pin
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num = MIC_DATA;
  if (i2s_set_pin(MIC_PORT, &pins) != ESP_OK) return false;
  i2s_stop(MIC_PORT);  // started on MIC_START
  return true;
}

}  // namespace

bool begin() {
  if (ok) return true;
  lock = xSemaphoreCreateMutex();
  mic_queue = xQueueCreate(MIC_QUEUE, sizeof(MicBlock));
  auto *storage = (int16_t *)heap_caps_malloc(JITTER_CAPACITY * sizeof(int16_t), MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
  if (!lock || !mic_queue || !storage) return false;
  jitter = new audio::JitterBuffer(storage, JITTER_CAPACITY);
  set_volume(MARVIN_AUDIO_VOLUME);
  mic_gain = audio::db_to_gain(MARVIN_MIC_GAIN_DB);
  if (!begin_speaker()) {
    Serial.println("audio: speaker I2S init failed");
    return false;
  }
  if (!begin_mic()) {
    Serial.println("audio: microphone I2S init failed");
    return false;
  }
  if (xTaskCreatePinnedToCore(speaker_task, "speaker", 4096, nullptr, SPK_PRIORITY, nullptr, CORE) != pdPASS ||
      xTaskCreatePinnedToCore(mic_task, "mic", 4096, nullptr, MIC_PRIORITY, &mic_task_handle, CORE) != pdPASS)
    return false;
  ok = true;
  return true;
}

bool ready() { return ok; }

bool play_sound(uint8_t id) {
  if (!ok) return false;
  Lock l;
  if (!earcon.start(id)) return false;
  sounds++;
  return true;
}

void on_message(uint8_t type, const uint8_t *payload, size_t n) {
  if (!ok) return;
  switch (type) {
    case proto::AUDIO_OUT: {
      audio_packets::AudioOut pkt;
      if (!audio_packets::decode_audio_out(payload, n, &pkt)) return;
      int16_t pcm[proto::AUDIO_OUT_MAX_SAMPLES];
      for (size_t i = 0; i < pkt.n; i++) pcm[i] = pkt.sample(i);
      Lock l;
      jitter->push(pkt.stream, pkt.sample_index, pcm, pkt.n, millis());
      break;
    }
    case proto::AUDIO_CTRL: {
      audio_packets::AudioCtrl c;
      if (!audio_packets::decode_audio_ctrl(payload, n, &c)) return;
      switch (c.command) {
        case proto::AUDIO_MIC_START:
          if (!mic_wanted) {
            mic_wanted = true;
            xTaskNotifyGive(mic_task_handle);
          }
          break;
        case proto::AUDIO_MIC_STOP: mic_wanted = false; break;
        case proto::AUDIO_PLAY_STOP: {
          Lock l;
          jitter->stop();
          earcon.stop();
          break;
        }
        case proto::AUDIO_VOLUME: set_volume(c.argument); break;
        case proto::AUDIO_MIC_GAIN: mic_gain = audio::db_to_gain(c.argument > 36 ? 36 : c.argument); break;
        default: break;
      }
      break;
    }
    case proto::SOUND: {
      uint8_t id;
      if (audio_packets::decode_sound(payload, n, &id)) play_sound(id);
      break;
    }
    default: break;
  }
}

void set_linked(bool up) {
  if (!ok || up == linked) return;
  linked = up;
  if (!up) {
    mic_wanted = false;  // the next host asks again
    Lock l;
    jitter->stop();
  } else if (mic_task_handle) {
    xTaskNotifyGive(mic_task_handle);
  }
}

size_t next_mic_payload(uint8_t *payload, size_t cap, uint64_t *t_us) {
  static MicBlock block;
  if (!ok || cap < audio_packets::AUDIO_IN_HEADER + sizeof(block.pcm)) return 0;
  if (xQueueReceive(mic_queue, &block, 0) != pdTRUE) return 0;
  if (!linked || !mic_wanted) return 0;  // stopped meanwhile: drop
  *t_us = block.t_us;
  return audio_packets::encode_audio_in(payload, block.index, block.pcm, MIC_BLOCK);
}

bool stats_line(char *out, size_t cap) {
  if (!ok) return false;
  Lock l;
  const audio::JitterBuffer::Counters &c = jitter->counters();
  bool busy = c.datagrams != last.datagrams || sounds != last_sounds || mic_blocks != last_mic_blocks ||
              mic_dropped != last_mic_dropped;
  snprintf(out, cap,
           "audio: speaker %lu dgram, %lu lost / %lu late / %lu overflow samples, %lu underruns, %lu sounds; "
           "mic %lu blocks, %lu dropped, peak %u",
           (unsigned long)(c.datagrams - last.datagrams), (unsigned long)(c.lost_samples - last.lost_samples),
           (unsigned long)(c.late_samples - last.late_samples),
           (unsigned long)(c.overflow_samples - last.overflow_samples),
           (unsigned long)(c.underruns - last.underruns), (unsigned long)(sounds - last_sounds),
           (unsigned long)(mic_blocks - last_mic_blocks), (unsigned long)(mic_dropped - last_mic_dropped),
           (unsigned)mic_peak);
  last = c;
  last_sounds = sounds;
  last_mic_blocks = mic_blocks;
  last_mic_dropped = mic_dropped;
  mic_peak = 0;
  return busy;
}

}  // namespace audio_app

#endif
