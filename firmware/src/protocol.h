// Marvin UDP protocol, version 1. Mirrors host/marvin_host/protocol.py; see docs/protocol.md.
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace proto {

constexpr uint8_t VERSION = 1;
constexpr uint16_t HOST_PORT = 47100;
constexpr uint16_t DEVICE_PORT = 47101;
constexpr size_t HEADER_SIZE = 16;

enum Type : uint8_t {
  HELLO = 0x01,
  LIDAR = 0x02,
  LD2450 = 0x03,
  LOG = 0x04,
  VITALS = 0x05,
  AUDIO_IN = 0x06,    // robot -> host: microphone PCM (audio/audio_packets.h)
  HOST_ACK = 0x81,
  FACE_STATE = 0x82,  // host -> robot: presence state for the face (FACE_STATE_SIZE bytes, ~10 Hz)
  FACE_EVENT = 0x83,  // host -> robot: one brain event, u8 code (face::Event in face/face.h)
  AUDIO_OUT = 0x84,   // host -> robot: PCM to play (audio/audio_packets.h)
  AUDIO_CTRL = 0x85,  // host -> robot: command u8 + argument u8 (AudioCommand)
  SOUND = 0x86,       // host -> robot: play a built-in sound, u8 id (audio/earcons.h)
};

// HELLO flags. Parsers that only know bit 0 keep working: the other bits are capabilities.
constexpr uint8_t FLAG_SIMULATED = 0x01;  // the sensor data is simulated
constexpr uint8_t FLAG_CAMERA = 0x02;     // MJPEG camera at http://<robot ip>:CAMERA_PORT/stream
constexpr uint8_t FLAG_AUDIO = 0x04;      // speaker and microphone: AUDIO_IN / AUDIO_OUT / AUDIO_CTRL / SOUND

constexpr uint16_t CAMERA_PORT = 81;

// The largest host -> robot datagram the robot reads (AUDIO_OUT is the biggest: 16 + 6 + 960).
constexpr size_t MAX_HOST_DATAGRAM = 1024;

// Audio: 16 kHz mono signed 16-bit little-endian PCM in both directions.
constexpr uint32_t AUDIO_RATE = 16000;
constexpr size_t AUDIO_IN_SAMPLES = 320;       // 20 ms per AUDIO_IN datagram
constexpr size_t AUDIO_OUT_MAX_SAMPLES = 480;  // 30 ms, 960 bytes: the most one AUDIO_OUT may carry

// AUDIO_CTRL commands (payload: command u8, argument u8). Unknown commands are ignored.
enum AudioCommand : uint8_t {
  AUDIO_MIC_START = 1,  // start (or keep) streaming AUDIO_IN; the host repeats it every second
  AUDIO_MIC_STOP = 2,
  AUDIO_PLAY_STOP = 3,  // drop the queued AUDIO_OUT and any sound being played
  AUDIO_VOLUME = 4,     // argument: speaker volume 0..100 (capped in firmware for the 1 W speaker)
  AUDIO_MIC_GAIN = 5,   // argument: microphone gain in dB, 0..36
};

// FACE_STATE payload: flags u8, head x/y/z i16 mm, position x/y/z i16 mm, distance u16 mm,
// heart rate u16 (0.01/min). Invalid fields are 0. Decoded by face/face_link.h.
constexpr size_t FACE_STATE_SIZE = 17;
enum FaceStateFlag : uint8_t {
  FACE_PRESENT = 0x01,
  FACE_SEATED = 0x02,
  FACE_HEAD = 0x04,        // head x/y/z valid
  FACE_POSITION = 0x08,    // position x/y/z valid
  FACE_DISTANCE = 0x10,    // distance valid
  FACE_HEART_RATE = 0x20,  // heart rate valid
};

// Little-endian writers (both ESP8266 and ESP32 are little-endian, but keep it explicit).
inline uint8_t *put16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; return p + 2; }
inline uint8_t *put32(uint8_t *p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = v >> (8 * i); return p + 4; }
inline uint8_t *put64(uint8_t *p, uint64_t v) { for (int i = 0; i < 8; i++) p[i] = v >> (8 * i); return p + 8; }
inline uint16_t get16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
inline uint32_t get32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
inline uint64_t get64(const uint8_t *p) { uint64_t v = 0; for (int i = 7; i >= 0; i--) v = (v << 8) | p[i]; return v; }

// Writes the 16-byte header, returns a pointer to the payload.
inline uint8_t *header(uint8_t *buf, Type type, uint32_t seq, uint64_t t_us) {
  buf[0] = 'M';
  buf[1] = 'V';
  buf[2] = VERSION;
  buf[3] = type;
  put32(buf + 4, seq);
  put64(buf + 8, t_us);
  return buf + HEADER_SIZE;
}

inline bool valid(const uint8_t *buf, size_t len) {
  return len >= HEADER_SIZE && buf[0] == 'M' && buf[1] == 'V' && buf[2] == VERSION;
}

// HELLO payload: mac[6], board u8, flags u8, rssi i8, uptime_ms u32, firmware (u8 length + text).
inline size_t hello(uint8_t *p, const uint8_t mac[6], uint8_t board, uint8_t flags, int8_t rssi,
                    uint32_t uptime_ms, const char *fw) {
  uint8_t *s = p;
  memcpy(p, mac, 6);
  p += 6;
  *p++ = board;
  *p++ = flags;
  *p++ = (uint8_t)rssi;
  p = put32(p, uptime_ms);
  size_t n = strlen(fw);
  if (n > 255) n = 255;
  *p++ = n;
  memcpy(p, fw, n);
  return p + n - s;
}

}  // namespace proto
