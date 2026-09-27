// SPDX-License-Identifier: MIT
//
// Sound in and out on the XIAO ESP32S3 Sense, as main.cpp sees it. Built only with
// -DMARVIN_HAS_AUDIO (platformio.ini) on an ESP32-S3 with Arduino.
//
//   setup(), after the sensors:  audio_app::begin();
//   UDP receive, per message:    audio_app::on_message(type, payload, payload_len);
//   loop():                      audio_app::set_linked(linked);
//                                while ((n = audio_app::next_mic_payload(p, cap, &t_us))) send AUDIO_IN
//
// Speaker: MAX98357A on I2S1 (BCLK GPIO8, LRC/WS GPIO43, DIN GPIO3), 16 kHz, 16-bit, the mono
// stream duplicated on both slots (the amp plays (L+R)/2 with SD unconnected). A task on core 1
// pulls 10 ms blocks from the jitter buffer (audio/jitter_buffer.h), mixes the earcon being played
// (audio/earcons.h), applies the volume (hard-capped, docs/audio.md) and blocks in i2s_write.
// When nothing is queued it writes silence, so the I2S clock paces the task.
//
// Microphone: the Sense board's PDM mic on I2S0 (CLK GPIO42, DATA GPIO41), 16 kHz. The PDM clock
// only runs while the host asked for the stream (AUDIO_CTRL MIC_START) and the link is up; a task
// on core 1 reads 20 ms blocks, removes the DC offset, applies the gain and queues them for the
// main loop, which sends them as AUDIO_IN (the loop owns the UDP socket, so AUDIO_IN leaves from
// the robot's port like every other message).
//
// GPIO43 is UART0 TX at boot (the ROM and ESP-IDF logs print there; with USB CDC on boot,
// `Serial` is USB and UART0 is only used for the lidar's RX on GPIO44). begin() takes GPIO43 back
// from UART0 (gpio_reset_pin) before routing I2S1 WS to it, so console bytes never reach the amp's
// LRC input. Do not call Serial0.begin() or Serial0.setPins() after begin().
//
// Build flags (all optional): MARVIN_AUDIO_VOLUME (default 60, 0..100), MARVIN_AUDIO_GAIN_CAP
// (percent of full scale at volume 100, default 50), MARVIN_MIC_GAIN_DB (default 12),
// MARVIN_MIC_RIGHT_SLOT (1: read the PDM mic on the right slot instead of the left).
#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(MARVIN_HAS_AUDIO) && defined(ARDUINO) && defined(ESP32)

namespace audio_app {

// Installs both I2S drivers and starts the speaker and microphone tasks. False on failure
// (the rest of the firmware runs without audio).
bool begin();
bool ready();

// Hands over a host -> robot message (type = header byte 3, payload after the header). Handles
// AUDIO_OUT, AUDIO_CTRL and SOUND; ignores anything else. Cheap, never blocks for long.
void on_message(uint8_t type, const uint8_t *payload, size_t n);

// Plays a built-in sound (audio/earcons.h) locally. False if the id is unknown.
bool play_sound(uint8_t id);

// The main loop reports the link state: losing the host stops the microphone stream.
void set_linked(bool linked);

// Takes the next microphone block ready to send: writes the AUDIO_IN payload (4 + 640 bytes) into
// payload and the capture time of its first sample (robot clock, us) into t_us. Returns the
// payload size, 0 when nothing is waiting.
size_t next_mic_payload(uint8_t *payload, size_t cap, uint64_t *t_us);

// One-line summary of the counters since the previous call (for LOG), and whether anything
// happened (speaker stream, sound or microphone) since then.
bool stats_line(char *out, size_t cap);

}  // namespace audio_app

#endif
