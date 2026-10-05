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
  BUTTON = 0x07,      // robot -> host: a button was pressed, button u8 + action u8 (ButtonId, ButtonAction)
  ENVIRONMENT = 0x08, // robot -> host: the room sensors (PIR, DHT11, light), ENVIRONMENT_SIZE bytes (Environment)
  HEAD = 0x09,        // robot -> host: the head's pose, HEAD_SIZE bytes (Head), 5 Hz and at once on a change
  HOST_ACK = 0x81,
  FACE_STATE = 0x82,  // host -> robot: presence state for the face (FACE_STATE_SIZE bytes, ~10 Hz)
  FACE_EVENT = 0x83,  // host -> robot: one brain event, u8 code (face::Event in face/face.h)
  AUDIO_OUT = 0x84,   // host -> robot: PCM to play (audio/audio_packets.h)
  AUDIO_CTRL = 0x85,  // host -> robot: command u8 + argument u8 (AudioCommand)
  SOUND = 0x86,       // host -> robot: play a built-in sound, u8 id (audio/earcons.h)
  HEAD_CTRL = 0x87,   // host -> robot: head mode u8 (HeadMode) + yaw i16 (0.1 deg, for HEAD_MANUAL)
};

// HELLO flags. Parsers that only know bit 0 keep working: the other bits are capabilities.
constexpr uint8_t FLAG_SIMULATED = 0x01;  // the sensor data is simulated
constexpr uint8_t FLAG_CAMERA = 0x02;     // MJPEG camera at http://<robot ip>:CAMERA_PORT/stream
constexpr uint8_t FLAG_AUDIO = 0x04;      // speaker and microphone: AUDIO_IN / AUDIO_OUT / AUDIO_CTRL / SOUND
constexpr uint8_t FLAG_BUTTONS = 0x08;    // has buttons: sends BUTTON
constexpr uint8_t FLAG_BUZZER = 0x10;     // plays SOUND on a buzzer (no AUDIO_OUT, no microphone)
constexpr uint8_t FLAG_FACE = 0x20;       // shows the face on a small screen of its own: send it FACE_STATE / FACE_EVENT
constexpr uint8_t FLAG_ENVIRONMENT = 0x40;  // has room sensors: sends ENVIRONMENT (which ones: its `sensors` byte)

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

// BUTTON payload: button u8, action u8. The host ignores buttons and actions it does not know.
constexpr size_t BUTTON_SIZE = 2;
enum ButtonId : uint8_t {
  BUTTON_TALK = 1,  // Talk now: the app's Talk now button
  BUTTON_MUTE = 2,  // mute or unmute the microphone
};
enum ButtonAction : uint8_t {
  BUTTON_PRESS = 1,
};

inline size_t button(uint8_t *p, uint8_t id, uint8_t action) {
  p[0] = id;
  p[1] = action;
  return BUTTON_SIZE;
}

// ENVIRONMENT payload, 12 bytes: sensors u8 (EnvSensor: fitted), flags u8 (EnvFlag), temperature i16
// (0.1 degC), humidity u16 (0.1 %RH), light level u16 (0..1000, higher is brighter), light raw u16 (ADC,
// 0..4095), seconds since the last motion u16 (0 while there is motion; ENV_NO_MOTION: none since the
// PIR warmed up). Invalid fields are 0. The host reads these 12 bytes of a longer payload.
constexpr size_t ENVIRONMENT_SIZE = 12;
constexpr uint16_t ENV_NO_MOTION = 0xFFFF;
enum EnvSensor : uint8_t {
  ENV_PIR = 0x01,      // a PIR motion sensor
  ENV_CLIMATE = 0x02,  // a temperature and humidity sensor (DHT11)
  ENV_LIGHT = 0x04,    // a light sensor (LDR)
};
enum EnvFlag : uint8_t {
  ENV_MOTION = 0x01,             // the PIR sees motion now
  ENV_WARMING_UP = 0x02,         // the PIR is still warming up: no motion reported yet
  ENV_TEMPERATURE_VALID = 0x04,
  ENV_HUMIDITY_VALID = 0x08,
  ENV_LIGHT_VALID = 0x10,
  ENV_SIMULATED = 0x20,          // the readings are simulated
};

struct Environment {
  uint8_t sensors = 0;             // EnvSensor bits
  uint8_t flags = 0;               // EnvFlag bits
  int16_t temperature_dc = 0;      // 0.1 degC
  uint16_t humidity_dpct = 0;      // 0.1 %RH
  uint16_t light = 0;              // 0..1000
  uint16_t light_raw = 0;          // 0..4095
  uint16_t motion_age_s = ENV_NO_MOTION;
};

// HEAD payload, 10 bytes: mode u8 (HeadMode), flags u8 (HeadFlag), yaw i16 (0.1 deg: where the head
// points, 0 straight ahead, positive towards the device frame's +x), goal i16 (0.1 deg: where it is
// going), target yaw i16 (0.1 deg, from the head's centre), target distance u16 (mm). The target
// fields are 0 without HEAD_TARGET. No HELLO flag (bit 7 is kept for a second flags byte): a robot
// with a head sends HEAD, and a host that has had one may send HEAD_CTRL. The host reads these 10
// bytes of a longer payload.
constexpr size_t HEAD_SIZE = 10;
// HEAD_CTRL payload: mode u8, yaw i16 (0.1 deg, used by HEAD_MANUAL, clamped to the head's limits).
constexpr size_t HEAD_CTRL_SIZE = 3;
enum HeadMode : uint8_t {
  HEAD_AUTO = 1,    // follows the nearest LD2450 target; looks around now and then when nobody is there
  HEAD_MANUAL = 2,  // holds the yaw the host gave, until another HEAD_CTRL or the host is lost
  HEAD_CENTER = 3,  // straight ahead
};
enum HeadFlag : uint8_t {
  HEAD_TARGET = 0x01,    // the last LD2450 frame had a target: target yaw and distance valid
  HEAD_TRACKING = 0x02,  // following a target (seen in the last 3 s)
  HEAD_GLANCING = 0x04,  // an idle glance around
  HEAD_SERVO_ON = 0x08,  // the servo is driven (off at boot and while the head rests)
};

struct Head {
  uint8_t mode = HEAD_AUTO;
  uint8_t flags = 0;
  int16_t yaw_dd = 0;          // 0.1 deg
  int16_t goal_dd = 0;
  int16_t target_yaw_dd = 0;
  uint16_t target_distance_mm = 0;
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

// ENVIRONMENT payload (Environment above), ENVIRONMENT_SIZE bytes.
inline size_t environment(uint8_t *p, const Environment &e) {
  p[0] = e.sensors;
  p[1] = e.flags;
  uint8_t *q = p + 2;
  q = put16(q, (uint16_t)e.temperature_dc);
  q = put16(q, e.humidity_dpct);
  q = put16(q, e.light);
  q = put16(q, e.light_raw);
  put16(q, e.motion_age_s);
  return ENVIRONMENT_SIZE;
}

// HEAD payload (Head above), HEAD_SIZE bytes.
inline size_t head(uint8_t *p, const Head &h) {
  p[0] = h.mode;
  p[1] = h.flags;
  uint8_t *q = put16(p + 2, (uint16_t)h.yaw_dd);
  q = put16(q, (uint16_t)h.goal_dd);
  q = put16(q, (uint16_t)h.target_yaw_dd);
  put16(q, h.target_distance_mm);
  return HEAD_SIZE;
}

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
// Board ids (MARVIN_BOARD; docs/protocol.md): 1 D1 mini, 2 ESP32-S3 DevKitC, 3 XIAO ESP32S3 Sense, 4 MR60BHA2 kit,
// 5 ESP32 DevKit, 6 USB sensor bridge (the computer, host/marvin_host/usb_sensors.py), 255 host simulator.
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
