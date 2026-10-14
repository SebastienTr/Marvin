// Marvin firmware: Wi-Fi link to the host, and the sensors, simulated or real.
//
// Boot -> join Wi-Fi -> broadcast HELLO every 0.5 s -> the host answers HOST_ACK ->
// unicast lidar and radar frames to that host, HELLO every 2 s as a heartbeat.
// No ACK for 6 s: back to broadcasting. The LED blinks fast while searching, slowly once linked.
//
// Sensor source, chosen at build time with MARVIN_SENSORS (see platformio.ini):
//   SIM  (default) simulated lidar, LD2450 and vital signs; HELLO carries the "simulated" flag.
//   REAL (ESP32 only) the lidar on UART0 and the LD2450 on UART1 (sensors_real.h); no VITALS,
//        the MR60BHA2 kit has its own bridge. A LOG with the sensor counters every 5 s.
//        MARVIN_LIDAR_MODEL=0: no lidar, the LD2450 only (the "looks" bench, esp32dev_looks).
//
// Optional media on the XIAO ESP32S3 Sense (flags in platformio.ini), announced in HELLO's flags:
//   MARVIN_HAS_AUDIO   speaker and microphone over UDP (audio/audio_app.h): AUDIO_OUT, AUDIO_CTRL
//                      and SOUND in, AUDIO_IN out.
//   MARVIN_HAS_CAMERA  MJPEG over HTTP on port 81 (camera/camera_app.h).
// A LOG with their counters every 10 s while they are in use.
//
// Bench parts on the ESP32 DevKits (MARVIN_HAS_BENCH_IO, bench/bench_io.h, docs/wiring-bench.md):
// the eyes on an SSD1306 OLED (FACE_STATE, FACE_EVENT and SOUND in), Talk and Mute buttons (BUTTON
// out), a buzzer playing SOUND, and the status LED. Announced in HELLO's flags.
//
// Room sensors on the ESP32 DevKits (MARVIN_HAS_ROOM_SENSORS, room/room_io.h): a PIR motion sensor,
// a DHT11 and, once wired, a light sensor (MARVIN_HAS_LDR), sent as ENVIRONMENT every 5 s and at once
// when motion starts or ends. Announced in HELLO's flags.
//
// Boot self-test on the XIAO bench (MARVIN_SELF_TEST, xiao_bench, docs/xiao-bench.md): one console
// line per part as it starts (screen, speaker, mic, radar, wifi, camera, host; wording in
// self_test.h), the radar and mic lines again every 5 s, all of them again on Enter. The screen
// shows red, green and blue, then the eyes wake up and look at you for 8 s; the speaker plays a
// short two-note chime. Each part may be missing: its line says what to check, nothing waits on it.
//
// With a screen and real sensors, the face follows the board's own LD2450 while the host sends no
// FACE_STATE: someone in front wakes it up and its eyes follow them.
//
// Head on the "looks" bench (MARVIN_HAS_HEAD, head/head_io.h, docs/looks-bench.md): a servo turning
// towards the nearest LD2450 target, read whether linked or not; HEAD to the host (5 Hz and on a
// change), HEAD_CTRL from it. Someone arriving or leaving plays a chirp on the buzzer
// (MARVIN_PRESENCE_CHIRPS, 1), and the OLED eyes follow the target while the host sends no FACE_STATE.
//
// SPDX-License-Identifier: MIT
#include <Arduino.h>

#if defined(ESP8266)
#include <ESP8266WiFi.h>
#else
#include <WiFi.h>
#endif
#include <WiFiUdp.h>

#include "head/head_logic.h"
#include "protocol.h"
#include "self_test.h"
#include "sensor_framing.h"
#include "sensors_real.h"
#include "sensors_sim.h"
#if defined(MARVIN_HAS_OTA)
#include "ota.h"
#endif
#if defined(MARVIN_HAS_SCREEN)
#include "face/face_app.h"
#endif
#if defined(MARVIN_HAS_AUDIO)
#include "audio/audio_app.h"
#include "audio/audio_packets.h"
#include "audio/earcons.h"
#endif
#if defined(MARVIN_HAS_CAMERA)
#include "camera/camera_app.h"
#endif
#if defined(MARVIN_HAS_BENCH_IO)
#include "audio/earcons.h"
#include "bench/bench_io.h"
#endif
#if defined(MARVIN_HAS_ROOM_SENSORS)
#include "room/room_io.h"
#endif
#if defined(MARVIN_HAS_HEAD)
#include "head/head_io.h"
#endif

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Copy include/secrets.example.h to include/secrets.h and put your Wi-Fi in it."
#endif

// ---- Build configuration ----------------------------------------------------------------------

#ifndef MARVIN_BOARD
#define MARVIN_BOARD 0
#endif
#ifndef MARVIN_SIM_LIDAR_MODEL
#define MARVIN_SIM_LIDAR_MODEL 1
#endif
#ifndef MARVIN_LIDAR_MODEL
#define MARVIN_LIDAR_MODEL 1
#endif
#ifndef MARVIN_SERIAL_BAUD
#define MARVIN_SERIAL_BAUD 115200
#endif
#ifndef MARVIN_LED_ACTIVE_LOW
#define MARVIN_LED_ACTIVE_LOW 0
#endif
#ifndef MARVIN_BOOT_SOUND
#define MARVIN_BOOT_SOUND 1                     // with audio: the "hello" earcon on the first link
#endif
#ifndef MARVIN_PRESENCE_CHIRPS
#define MARVIN_PRESENCE_CHIRPS 1                // with a head and a buzzer: "hello" and "bye" chirps (0: mute)
#endif
#ifndef MARVIN_EYES_TARGET_Z_MM
#define MARVIN_EYES_TARGET_Z_MM 300             // with a head or a screen: the height the eyes look at, above the desk
#endif
#ifndef MARVIN_LD2450_X_SIGN
#define MARVIN_LD2450_X_SIGN 1                  // -1 for an LD2450 mounted upside down
#endif
#ifndef MARVIN_SELF_TEST
#define MARVIN_SELF_TEST 0                      // 1: the boot self-test (the XIAO bench)
#endif

// MARVIN_SENSORS=SIM or MARVIN_SENSORS=REAL, turned into a number the preprocessor can compare.
#ifndef MARVIN_SENSORS
#define MARVIN_SENSORS SIM
#endif
#define MARVIN_SENSORS_ID_SIM 1
#define MARVIN_SENSORS_ID_REAL 2
#define MARVIN_PASTE_(a, b) a##b
#define MARVIN_PASTE(a, b) MARVIN_PASTE_(a, b)
#define MARVIN_SENSORS_ID MARVIN_PASTE(MARVIN_SENSORS_ID_, MARVIN_SENSORS)

#if MARVIN_SENSORS_ID == MARVIN_SENSORS_ID_REAL
#define MARVIN_REAL_SENSORS 1
#elif MARVIN_SENSORS_ID == MARVIN_SENSORS_ID_SIM
#define MARVIN_REAL_SENSORS 0
#else
#error "MARVIN_SENSORS must be SIM or REAL"
#endif

#if (defined(MARVIN_HAS_AUDIO) || defined(MARVIN_HAS_CAMERA)) && !defined(CONFIG_IDF_TARGET_ESP32S3)
#error "MARVIN_HAS_AUDIO and MARVIN_HAS_CAMERA are for the XIAO ESP32S3 Sense (pins in docs/wiring.md)."
#endif

#if defined(MARVIN_HAS_BENCH_IO) && !defined(ARDUINO_ARCH_ESP32)
#error "MARVIN_HAS_BENCH_IO is for the ESP32 DevKits (esp32dev, esp32s3; pins in docs/wiring-bench.md)."
#endif

#if defined(MARVIN_HAS_ROOM_SENSORS) && !defined(ARDUINO_ARCH_ESP32)
#error "MARVIN_HAS_ROOM_SENSORS is for the ESP32 DevKits (esp32dev, esp32s3; pins in docs/wiring-bench.md)."
#endif

#if defined(MARVIN_HAS_BENCH_IO) && defined(MARVIN_HAS_AUDIO)
#error "MARVIN_HAS_BENCH_IO's buzzer and MARVIN_HAS_AUDIO's speaker would both play SOUND: build one of them."
#endif

#if defined(MARVIN_HAS_HEAD) && !defined(ARDUINO_ARCH_ESP32)
#error "MARVIN_HAS_HEAD is for the ESP32 DevKits (esp32dev_looks; pins in docs/looks-bench.md)."
#endif

#if MARVIN_SELF_TEST && !(defined(CONFIG_IDF_TARGET_ESP32S3) && MARVIN_SENSORS_ID == MARVIN_SENSORS_ID_REAL)
#error "MARVIN_SELF_TEST is for the XIAO bench (xiao_bench: a XIAO ESP32S3 Sense with real sensors, docs/xiao-bench.md)."
#endif

#if MARVIN_REAL_SENSORS && !defined(ARDUINO_ARCH_ESP32)
#error "MARVIN_SENSORS=REAL needs an ESP32-S3 (lidar on UART0, LD2450 on UART1). The D1 mini (ESP8266) runs simulated sensors only: build it with MARVIN_SENSORS=SIM."
#endif

namespace {

constexpr int LIDAR_BATCH = 10;                 // LDROBOT packets per datagram
constexpr uint32_t LINK_TIMEOUT_MS = 6000;
constexpr uint32_t LIDAR_FLUSH_MS = 20;         // real sensors: send a partial batch after this
constexpr uint32_t STATS_PERIOD_MS = 5000;      // real sensors: counters LOG period
constexpr uint32_t MEDIA_STATS_PERIOD_MS = 10000;  // audio / camera counters LOG period, when in use
constexpr uint8_t LIDAR_MODEL = MARVIN_REAL_SENSORS ? MARVIN_LIDAR_MODEL : MARVIN_SIM_LIDAR_MODEL;

const char *lidar_name(uint8_t model) { return model == 0 ? "no" : model == 2 ? "D800" : "D500"; }

// ---- Link: Wi-Fi, HELLO / HOST_ACK, sending ---------------------------------------------------

WiFiUDP udp;
IPAddress host;
bool linked = false;
uint32_t last_ack_ms = 0;
uint32_t seq = 0;
uint8_t mac[6];
uint8_t buf[proto::HEADER_SIZE + 1 + LIDAR_BATCH * sim::LIDAR_PACKET];

uint64_t now_us() {
#if defined(ESP8266)
  return micros64();
#else
  return (uint64_t)esp_timer_get_time();
#endif
}

void led(bool on) {
#if defined(LED_BUILTIN) && !defined(MARVIN_HAS_BENCH_IO)  // with bench parts, bench_io drives the LED
  digitalWrite(LED_BUILTIN, (on ^ MARVIN_LED_ACTIVE_LOW) ? HIGH : LOW);
#else
  (void)on;
#endif
}

// Wi-Fi back-pressure. On a weak link the radio falls behind and lwIP runs out of buffers (endPacket()
// fails with ENOMEM, error 12). The sensor streams then pause for TX_PAUSE_MS, so the link messages
// (HELLO, BUTTON, ENVIRONMENT, LOG) still get through, and the drops are counted in one line every
// DROP_REPORT_MS instead of one core error per datagram.
constexpr uint32_t TX_PAUSE_MS = 250;
constexpr uint32_t DROP_REPORT_MS = 5000;
uint32_t tx_pause_until = 0;
uint32_t tx_dropped = 0;
uint32_t next_drop_report = 0;

bool is_stream(proto::Type type) { return type == proto::LIDAR || type == proto::LD2450; }

void report_drops(uint32_t ms) {
  if (tx_dropped == 0 || (int32_t)(ms - next_drop_report) < 0) return;
  Serial.printf("wifi: %u sensor datagrams dropped in %u s (RSSI %d dBm): the link is too slow, move closer to the router\n",
                (unsigned)tx_dropped, (unsigned)(DROP_REPORT_MS / 1000), (int)WiFi.RSSI());
  tx_dropped = 0;
  next_drop_report = ms + DROP_REPORT_MS;
}

// Sends the datagram in `dgram` (header space + payload_len bytes of payload), stamped t_us.
void send_datagram(uint8_t *dgram, proto::Type type, size_t payload_len, uint64_t t_us) {
  const uint32_t ms = millis();
  report_drops(ms);
  if (is_stream(type) && (int32_t)(tx_pause_until - ms) > 0) {
    tx_dropped++;
    return;
  }
  proto::header(dgram, type, seq++, t_us);
  IPAddress to = linked ? host : IPAddress(255, 255, 255, 255);
  udp.beginPacket(to, proto::HOST_PORT);
  udp.write(dgram, proto::HEADER_SIZE + payload_len);
  if (!udp.endPacket()) {
    tx_dropped++;
    tx_pause_until = ms + TX_PAUSE_MS;
  }
}

// Sends the payload already written in buf, stamped now.
void send(proto::Type type, size_t payload_len) { send_datagram(buf, type, payload_len, now_us()); }

void send_hello() {
  uint8_t flags = MARVIN_REAL_SENSORS ? 0 : proto::FLAG_SIMULATED;
#if defined(MARVIN_HAS_CAMERA)
  if (camera_app::ready()) flags |= proto::FLAG_CAMERA;
#endif
#if defined(MARVIN_HAS_AUDIO)
  if (audio_app::ready()) flags |= proto::FLAG_AUDIO;
#endif
#if defined(MARVIN_HAS_BENCH_IO)
  flags |= bench_io::hello_flags();
#endif
#if defined(MARVIN_HAS_ROOM_SENSORS)
  flags |= room_io::hello_flags();
#endif
  size_t n = proto::hello(buf + proto::HEADER_SIZE, mac, MARVIN_BOARD, flags, (int8_t)WiFi.RSSI(),
                          millis(), MARVIN_FW_VERSION);
  send(proto::HELLO, n);
}

void send_log(const char *text) {
  size_t n = strlen(text);
  if (n > sizeof(buf) - proto::HEADER_SIZE) n = sizeof(buf) - proto::HEADER_SIZE;
  memcpy(buf + proto::HEADER_SIZE, text, n);
  send(proto::LOG, n);
}

#if MARVIN_REAL_SENSORS
bool ld2450_configured = false;
#endif

// ---- Boot self-test (XIAO bench) ---------------------------------------------------------------

#if MARVIN_SELF_TEST
enum Part { PART_SCREEN, PART_SPEAKER, PART_MIC, PART_RADAR, PART_WIFI, PART_CAMERA, PART_HOST, PART_COUNT };
char part_lines[PART_COUNT][224];
uint32_t wifi_up_ms = 0;

// Prints a part's line and keeps it for the replay (Enter).
void part_line(Part part, const char *line) {
  snprintf(part_lines[part], sizeof(part_lines[part]), "%s", line);
  Serial.println(line);
}

void replay_self_test() {
  Serial.println("self-test, last results:");
  for (int i = 0; i < PART_COUNT; i++)
    if (part_lines[i][0]) Serial.printf("  %s\n", part_lines[i]);
}

// The USB console only shows what is printed while the monitor is open: wait for it a little.
void wait_for_console(uint32_t ms) {
  for (uint32_t t0 = millis(); !Serial && millis() - t0 < ms;) delay(10);
}
#endif

void on_host_ack() {
  last_ack_ms = millis();
  if (linked && udp.remoteIP() == host) return;
  host = udp.remoteIP();
  linked = true;
#if defined(MARVIN_HAS_AUDIO) && MARVIN_BOOT_SOUND
  static bool greeted = false;
  if (!greeted) greeted = audio_app::play_sound(audio::SOUND_HELLO);  // first link since boot
#endif
#if defined(MARVIN_HAS_BENCH_IO) && MARVIN_BOOT_SOUND
  static bool chimed = false;
  if (!chimed) chimed = bench_io::play_sound(audio::SOUND_HELLO);  // first link since boot, on the buzzer
#endif
#if MARVIN_SELF_TEST
  char line[64];
  selftest::host_linked_line(line, sizeof(line), host.toString().c_str());
  part_line(PART_HOST, line);
#else
  Serial.printf("linked to host %s\n", host.toString().c_str());
#endif
  char msg[96];
#if MARVIN_REAL_SENSORS
  snprintf(msg, sizeof(msg), "firmware %s up, real sensors, %s lidar, LD2450 %s", MARVIN_FW_VERSION,
           lidar_name(LIDAR_MODEL), ld2450_configured ? "in multi-target mode" : "did not answer at boot");
#else
  snprintf(msg, sizeof(msg), "firmware %s up, simulated %s lidar", MARVIN_FW_VERSION, lidar_name(LIDAR_MODEL));
#endif
  send_log(msg);
}

// Reads the datagrams from the host.
void poll_host() {
#if defined(MARVIN_HAS_AUDIO)
  static uint8_t in[proto::MAX_HOST_DATAGRAM];  // AUDIO_OUT is the largest host -> robot message
#else
  static uint8_t in[512];
#endif
  while (udp.parsePacket()) {
    int n = udp.read(in, sizeof(in));
    if (n < (int)proto::HEADER_SIZE || !proto::valid(in, n)) continue;
    const bool from_host = linked && udp.remoteIP() == host;
    (void)from_host;  // unused on boards with neither screen nor audio
    switch (in[3]) {
      case proto::HOST_ACK:
        if (n >= (int)proto::HEADER_SIZE + 8) on_host_ack();
        break;
#if defined(MARVIN_HAS_AUDIO)
      case proto::AUDIO_OUT:
      case proto::AUDIO_CTRL:
      case proto::SOUND:
        if (from_host) audio_app::on_message(in[3], in + proto::HEADER_SIZE, n - proto::HEADER_SIZE);
        break;
#endif
#if defined(MARVIN_HAS_HEAD)
      case proto::HEAD_CTRL:
        if (from_host) head_io::on_message(in[3], in + proto::HEADER_SIZE, n - proto::HEADER_SIZE, millis());
        break;
#endif
      default:
        // other host -> robot messages are dispatched here
        // (type in[3], payload in + proto::HEADER_SIZE, n - proto::HEADER_SIZE bytes, sender udp.remoteIP())
#if defined(MARVIN_HAS_SCREEN)
        if (from_host)
          face_app::on_message(in[3], in + proto::HEADER_SIZE, n - proto::HEADER_SIZE);  // queued, never blocks
#endif
#if defined(MARVIN_HAS_BENCH_IO)
        if (from_host) bench_io::on_message(in[3], in + proto::HEADER_SIZE, n - proto::HEADER_SIZE);  // never blocks
#endif
        break;
    }
  }
}

void head_frame(const uint8_t *frame, size_t n);
void head_step(uint32_t ms);

void connect_wifi() {
  WiFi.mode(WIFI_STA);
#if defined(ESP8266)
  WiFi.setSleepMode(WIFI_NONE_SLEEP);           // modem sleep adds latency and drops broadcast replies
#else
  WiFi.setSleep(false);
#endif
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.printf(MARVIN_SELF_TEST ? "wifi: joining \"%s" : "joining %s", WIFI_SSID);
#if defined(MARVIN_HAS_BENCH_IO)
  for (uint32_t i = 0; WiFi.status() != WL_CONNECTED; i++) {
    bench_io::step(millis());       // the LED blinks slowly; the eyes run in their own task
#if defined(MARVIN_HAS_ROOM_SENSORS)
    room_io::step(millis());        // the PIR's warm-up counts from here
#endif
#if defined(MARVIN_HAS_HEAD) && MARVIN_REAL_SENSORS
    // the head already follows people, Wi-Fi or not
    real::poll(nullptr, [](const uint8_t *f) { head_frame(f, framing::LD2450_FRAME); });
    head_step(millis());
#endif
    delay(10);
    if (i % 20 == 19) Serial.print('.');
  }
  bench_io::set_phase(bench::LinkPhase::SEARCHING);
#elif MARVIN_SELF_TEST
  Serial.println("\"");
  constexpr uint32_t HINT_MS = 20000;  // then every 20 s: what to check
  for (uint32_t t0 = millis(), next = t0 + HINT_MS; WiFi.status() != WL_CONNECTED;) {
    led(true); delay(100); led(false); delay(100);
    if ((int32_t)(millis() - next) >= 0) {
      char line[192];
      selftest::wifi_waiting_line(line, sizeof(line), WIFI_SSID, (millis() - t0) / 1000);
      part_line(PART_WIFI, line);
      next += HINT_MS;
    }
  }
  char line[64];
  selftest::wifi_line(line, sizeof(line), WiFi.localIP().toString().c_str(), WiFi.RSSI());
  part_line(PART_WIFI, line);
  wifi_up_ms = millis();
  return;
#else
  while (WiFi.status() != WL_CONNECTED) {
    led(true); delay(100); led(false); delay(100);
    Serial.print('.');
  }
#endif
  Serial.printf("\nIP %s, RSSI %d dBm\n", WiFi.localIP().toString().c_str(), WiFi.RSSI());
}

// Host datagrams, link timeout, HELLO, status LED.
void link_step(uint32_t ms) {
  static uint32_t next_hello = 0;
  poll_host();
  // signed: an ACK read just above is stamped with millis(), which can be later than ms
  if (linked && (int32_t)(ms - last_ack_ms) > (int32_t)LINK_TIMEOUT_MS) {
    linked = false;
    Serial.println("host lost, searching");
  }
  if ((int32_t)(ms - next_hello) >= 0) {
    send_hello();
    next_hello = ms + (linked ? 2000 : 500);
  }
  led(linked ? (ms % 2000) < 100 : (ms % 250) < 125);
#if defined(MARVIN_HAS_BENCH_IO)
  bench_io::set_phase(WiFi.status() != WL_CONNECTED ? bench::LinkPhase::WIFI  // Wi-Fi lost: reconnecting
                      : linked                        ? bench::LinkPhase::LINKED
                                                      : bench::LinkPhase::SEARCHING);
  bench_io::step(ms);
  uint8_t button;
  while (bench_io::next_press(&button)) {
    const char *name = button == proto::BUTTON_TALK ? "talk" : "mute";
    if (linked) {
      send(proto::BUTTON, proto::button(buf + proto::HEADER_SIZE, button, proto::BUTTON_PRESS));
      Serial.printf("button %s\n", name);
    } else {
      bench_io::play_sound(audio::SOUND_ERROR);  // no host to tell: a low double beep
      Serial.printf("button %s, but no host yet\n", name);
    }
  }
#endif
}

// ---- Room sensors (ESP32 DevKits) -------------------------------------------------------------

#if defined(MARVIN_HAS_ROOM_SENSORS)

// PIR, DHT11 and light: ENVIRONMENT every 5 s, and at once when motion starts or ends, and on linking.
void room_step(uint32_t ms) {
  static uint32_t next_send = 0;
  static bool was_linked = false;
  room_io::step(ms);
  const bool now = room_io::changed() || (linked && !was_linked);
  was_linked = linked;
  if (!linked || !(now || (int32_t)(ms - next_send) >= 0)) return;
  send(proto::ENVIRONMENT, room_io::payload(buf + proto::HEADER_SIZE, ms));
  next_send = ms + room_io::ENVIRONMENT_PERIOD_MS;
}

#else
void room_step(uint32_t) {}
#endif

// ---- Head (the "looks" bench) -----------------------------------------------------------------

#if defined(MARVIN_HAS_HEAD)

// Every LD2450 frame, linked or not: the head, the presence chirps, and the eyes' own target.
void head_frame(const uint8_t *frame, size_t n) {
  head_io::on_ld2450(frame, n, millis());
#if defined(MARVIN_HAS_BENCH_IO)
  double p[3];
  const bool present = head_io::eyes_point(MARVIN_EYES_TARGET_Z_MM, p);
  bench_io::set_local_presence(present, present ? p : nullptr);
#endif
}

void on_presence(head::PresenceEvent ev) {
  const bool hello = ev == head::PresenceEvent::HELLO;
  Serial.println(hello ? "head: someone arrived" : "head: they left");
#if defined(MARVIN_HAS_BENCH_IO) && MARVIN_PRESENCE_CHIRPS
  if (hello) bench_io::play_sound(audio::SOUND_HELLO);  // and a happy look in the eyes
  else bench_io::play_bye();
#endif
}

// The servo, the presence events, and HEAD to the host (HEAD_PERIOD_MS, and at once on a change).
void head_step(uint32_t ms) {
  static uint32_t next_send = 0;
  head_io::set_linked(linked, ms);
  head_io::step(ms);
  head::PresenceEvent ev;
  while (head_io::next_event(&ev)) on_presence(ev);
  const bool now = head_io::changed();
  if (!linked || !(now || (int32_t)(ms - next_send) >= 0)) return;
  size_t n = head_io::payload(buf + proto::HEADER_SIZE);
  if (n) send(proto::HEAD, n);
  next_send = ms + head_io::HEAD_PERIOD_MS;
}

#else
void head_frame(const uint8_t *, size_t) {}
void head_step(uint32_t) {}
#endif

// ---- Media: audio and camera (XIAO ESP32S3 Sense) ---------------------------------------------

#if defined(MARVIN_HAS_AUDIO) || defined(MARVIN_HAS_CAMERA)

#if defined(MARVIN_HAS_AUDIO)
uint8_t mic_dgram[proto::HEADER_SIZE + audio_packets::AUDIO_IN_HEADER + 2 * proto::AUDIO_IN_SAMPLES];
#endif

#if defined(MARVIN_HAS_AUDIO) && MARVIN_SELF_TEST
// The self-test's mic line: the level since the previous one.
void report_mic() {
  double sum_sq;
  uint32_t samples;
  uint16_t peak;
  const bool got = audio_app::mic_level(&sum_sq, &samples, &peak);
  char line[128];
  selftest::mic_line(line, sizeof(line), got, selftest::dbfs(sum_sq, samples), peak);
  part_line(PART_MIC, line);
  if (linked) send_log(line);
}
#endif

// Speaker and microphone. The self-test starts them before Wi-Fi (they do not need it), plays the
// chime, then listens for half a second once the chime is over.
void audio_begin() {
#if defined(MARVIN_HAS_AUDIO)
  const bool ok = audio_app::begin();
#if MARVIN_SELF_TEST
  char line[128];
  if (ok) audio_app::play_sound(audio::SOUND_WAKE);  // two notes up, 0.2 s
  selftest::speaker_line(line, sizeof(line), ok);
  part_line(PART_SPEAKER, line);
  if (ok) {
    audio_app::set_mic_monitor(true);  // from now on, for the level lines
    delay(400);                        // the chime and the microphone's warm-up
    double sum_sq;
    uint32_t samples;
    uint16_t peak;
    audio_app::mic_level(&sum_sq, &samples, &peak);  // forget what overlapped the chime
    delay(500);
  }
  report_mic();
#else
  Serial.println(ok ? "audio: speaker on I2S1 (GPIO8/43/3), PDM mic on I2S0 (GPIO42/41)" : "audio: disabled");
#endif
#endif
}

// After Wi-Fi is up (the camera server needs the network stack).
void media_begin() {
#if !MARVIN_SELF_TEST
  audio_begin();
#endif
#if defined(MARVIN_HAS_CAMERA)
#if MARVIN_SELF_TEST
  int w = 0, h = 0;
  size_t bytes = 0;
  const bool ok = camera_app::begin() && camera_app::test_frame(&w, &h, &bytes);
  char line[224];
  selftest::camera_line(line, sizeof(line), ok, w, h, camera_app::sensor_name(), WiFi.localIP().toString().c_str(),
                        proto::CAMERA_PORT, psramFound() ? selftest::CAMERA_NOT_FOUND : selftest::CAMERA_NO_PSRAM);
  part_line(PART_CAMERA, line);
#else
  if (camera_app::begin())
    Serial.printf("camera: %s, http://%s:%u/stream\n", camera_app::sensor_name(), WiFi.localIP().toString().c_str(),
                  (unsigned)proto::CAMERA_PORT);
#endif
#endif
}

void media_step(uint32_t ms) {
  static uint32_t last_stats = 0;
#if defined(MARVIN_HAS_AUDIO)
  audio_app::set_linked(linked);
  uint64_t t_us;
  while (size_t n = audio_app::next_mic_payload(mic_dgram + proto::HEADER_SIZE, sizeof(mic_dgram) - proto::HEADER_SIZE, &t_us))
    send_datagram(mic_dgram, proto::AUDIO_IN, n, t_us);
#endif
  if (ms - last_stats < MEDIA_STATS_PERIOD_MS) return;
  last_stats = ms;
  char msg[192];
#if defined(MARVIN_HAS_AUDIO)
  if (audio_app::stats_line(msg, sizeof(msg))) {
    Serial.println(msg);
    if (linked) send_log(msg);
  }
#endif
#if defined(MARVIN_HAS_CAMERA)
  if (camera_app::stats_line(msg, sizeof(msg))) {
    Serial.println(msg);
    if (linked) send_log(msg);
  }
#endif
}

#else
void audio_begin() {}
void media_begin() {}
void media_step(uint32_t) {}
#endif

// ---- Simulated sensors ------------------------------------------------------------------------

#if !MARVIN_REAL_SENSORS

void sensors_begin() {
  uint32_t t0 = millis();
  sim::begin(MARVIN_SIM_LIDAR_MODEL, [] { yield(); });
  Serial.printf("simulated room ready in %lu ms\n", (unsigned long)(millis() - t0));
}

void sensors_step(uint32_t ms) {
  static uint32_t next_radar = 0, sent_packets = 0, link_start_ms = 0;
  float t = ms / 1000.0f;
  if (!linked) {
    link_start_ms = ms;
    sent_packets = 0;
    return;
  }

  // lidar: keep up with the real packet rate, one datagram per LIDAR_BATCH packets
  uint32_t due = (uint64_t)(ms - link_start_ms) * sim::lidar_points_per_second() / 12000;
  while (due - sent_packets >= LIDAR_BATCH) {
    uint8_t *p = buf + proto::HEADER_SIZE;
    *p++ = MARVIN_SIM_LIDAR_MODEL;
    for (int i = 0; i < LIDAR_BATCH; i++, p += sim::LIDAR_PACKET) sim::lidar_packet(t, p);
    send(proto::LIDAR, 1 + LIDAR_BATCH * sim::LIDAR_PACKET);
    sent_packets += LIDAR_BATCH;
  }

  if ((int32_t)(ms - next_radar) >= 0) {
    sim::ld2450_frame(t, buf + proto::HEADER_SIZE);
    head_frame(buf + proto::HEADER_SIZE, sim::LD2450_FRAME);
    send(proto::LD2450, sim::LD2450_FRAME);
    send(proto::VITALS, sim::vitals(t, buf + proto::HEADER_SIZE));
    next_radar = ms + 100;
  }
}

#endif  // !MARVIN_REAL_SENSORS

// ---- Real sensors (ESP32) ---------------------------------------------------------------------

#if MARVIN_REAL_SENSORS

// Lidar packets are batched in their own datagram buffer, so HELLO and LOG can use buf meanwhile.
uint8_t lidar_dgram[proto::HEADER_SIZE + 1 + LIDAR_BATCH * framing::LIDAR_PACKET];
int lidar_count = 0;
uint64_t lidar_first_us = 0;                    // when the batch's first packet was read
uint32_t lidar_first_ms = 0;
real::Counters last_counters;
uint32_t last_stats_ms = 0;

void flush_lidar() {
  if (lidar_count && linked)
    send_datagram(lidar_dgram, proto::LIDAR, 1 + lidar_count * framing::LIDAR_PACKET, lidar_first_us);
  lidar_count = 0;
}

void on_lidar_packet(const uint8_t *packet) {
  if (!linked) return;
  if (lidar_count == 0) {
    lidar_first_us = now_us();
    lidar_first_ms = millis();
    lidar_dgram[proto::HEADER_SIZE] = LIDAR_MODEL;
  }
  memcpy(lidar_dgram + proto::HEADER_SIZE + 1 + lidar_count * framing::LIDAR_PACKET, packet,
         framing::LIDAR_PACKET);
  if (++lidar_count == LIDAR_BATCH) flush_lidar();
}

// The face's own idea of who is there, from the board's radar (used while the host is silent).
void face_frame(const uint8_t *frame) {
#if defined(MARVIN_HAS_SCREEN)
  head::Target t;
  if (head::nearest_target(frame, framing::LD2450_FRAME, MARVIN_LD2450_X_SIGN, t)) {
    double p[3];
    head::face_point(t, 0, MARVIN_EYES_TARGET_Z_MM, p);
    face_app::set_local_presence(true, p);
  } else {
    face_app::set_local_presence(false, nullptr);
  }
#else
  (void)frame;
#endif
}

void on_ld2450_frame(const uint8_t *frame) {
  head_frame(frame, framing::LD2450_FRAME);
  face_frame(frame);
  if (!linked) return;
  memcpy(buf + proto::HEADER_SIZE, frame, framing::LD2450_FRAME);
  send(proto::LD2450, framing::LD2450_FRAME);
}

// Serial log (always) and LOG to the host (when linked): rates and error counts since the last one.
void report_stats(uint32_t ms) {
  real::Counters c = real::counters(), &p = last_counters;
  float s = (ms - last_stats_ms) / 1000.0f;
  char msg[160];
#if MARVIN_SELF_TEST
  selftest::radar_line(msg, sizeof(msg), c.ld2450_frames - p.ld2450_frames, s, c.ld2450_bad_frames - p.ld2450_bad_frames,
                       c.uart_overflows - p.uart_overflows);
  part_line(PART_RADAR, msg);
  if (linked) send_log(msg);
#if defined(MARVIN_HAS_AUDIO)
  report_mic();
#endif
  last_counters = c;
  last_stats_ms = ms;
  return;
#endif
  if (LIDAR_MODEL == 0)  // the LD2450 only
    snprintf(msg, sizeof(msg), "LD2450 %.1f frame/s, %lu bad; %lu UART overflows",
             (c.ld2450_frames - p.ld2450_frames) / s, (unsigned long)(c.ld2450_bad_frames - p.ld2450_bad_frames),
             (unsigned long)(c.uart_overflows - p.uart_overflows));
  else
    snprintf(msg, sizeof(msg),
             "lidar %.0f pkt/s, %lu CRC err, %lu B dropped; LD2450 %.1f frame/s, %lu bad; %lu UART overflows",
             (c.lidar_packets - p.lidar_packets) / s, (unsigned long)(c.lidar_crc_errors - p.lidar_crc_errors),
             (unsigned long)(c.lidar_dropped_bytes - p.lidar_dropped_bytes),
             (c.ld2450_frames - p.ld2450_frames) / s, (unsigned long)(c.ld2450_bad_frames - p.ld2450_bad_frames),
             (unsigned long)(c.uart_overflows - p.uart_overflows));
  Serial.println(msg);
  if (linked) send_log(msg);
  last_counters = c;
  last_stats_ms = ms;
}

void sensors_begin() {
#if MARVIN_SELF_TEST
  // Configure the LD2450 quietly (up to ~3 s when it does not answer), then count its frames for 1.5 s.
  ld2450_configured = real::begin(LIDAR_MODEL, false);
  const real::Counters c0 = real::counters();
  const uint32_t t0 = millis();
  while (millis() - t0 < 1500) {
    real::poll(nullptr, on_ld2450_frame);
    delay(2);
  }
  const real::Counters c1 = real::counters();
  char line[96];
  selftest::radar_line(line, sizeof(line), c1.ld2450_frames - c0.ld2450_frames, (millis() - t0) / 1000.0f,
                       c1.ld2450_bad_frames - c0.ld2450_bad_frames, c1.uart_overflows - c0.uart_overflows);
  part_line(PART_RADAR, line);
  return;
#endif
  if (LIDAR_MODEL == 0) Serial.println("no lidar (MARVIN_LIDAR_MODEL=0)");
  else
    Serial.printf("lidar %s on its UART at %lu baud\n", lidar_name(LIDAR_MODEL),
                  (unsigned long)real::lidar_baud(LIDAR_MODEL));
  ld2450_configured = real::begin(LIDAR_MODEL);
  Serial.printf("LD2450 on UART1 at 256000 baud: %s\n",
                ld2450_configured ? "multi-target tracking set" : "no answer, reading its stream anyway");
}

// Called at the end of setup(): forget what piled up in the UARTs while joining Wi-Fi.
void sensors_ready() {
  real::poll(nullptr, nullptr);
  last_counters = real::counters();
  last_stats_ms = millis();
}

void sensors_step(uint32_t ms) {
  real::poll(on_lidar_packet, on_ld2450_frame);
  if (!linked) lidar_count = 0;
  else if (lidar_count && millis() - lidar_first_ms >= LIDAR_FLUSH_MS) flush_lidar();
  if (ms - last_stats_ms >= STATS_PERIOD_MS) report_stats(ms);
}

#endif  // MARVIN_REAL_SENSORS

#if MARVIN_SELF_TEST
// Enter in the monitor prints the self-test again; while no host answers, a hint every 30 s.
void self_test_step(uint32_t ms) {
  static uint32_t last_replay = 0, next_host_hint = 0;
  bool enter = false;
  while (Serial.available()) {
    const int ch = Serial.read();
    enter |= ch == '\n' || ch == '\r';
  }
  if (enter && ms - last_replay > 500) {  // CR LF is one Enter
    last_replay = ms;
    replay_self_test();
  }
  if (linked) {
    next_host_hint = 0;
    return;
  }
  if (next_host_hint == 0) next_host_hint = ms + 10000;
  if ((int32_t)(ms - next_host_hint) >= 0) {
    char line[160];
    selftest::host_waiting_line(line, sizeof(line), (ms - wifi_up_ms) / 1000);
    part_line(PART_HOST, line);
    next_host_hint = ms + 30000;
  }
}
#else
void self_test_step(uint32_t) {}
#endif

}  // namespace

// ---- Arduino entry points ---------------------------------------------------------------------

void setup() {
  Serial.begin(MARVIN_SERIAL_BAUD);
  delay(200);
#if MARVIN_SELF_TEST
  wait_for_console(3000);
#endif
  Serial.printf("\nMarvin firmware %s, board %d, %s sensors\n", MARVIN_FW_VERSION, MARVIN_BOARD,
                MARVIN_REAL_SENSORS ? "real" : "simulated");
#if MARVIN_SELF_TEST
  Serial.println("self-test: one line per part (docs/xiao-bench.md); press Enter to see them again");
#endif
#if defined(MARVIN_HAS_BENCH_IO)
  // the eyes run in their own task from here on; the buttons, buzzer and LED from the loop
  if (!bench_io::begin()) Serial.println("bench: the eyes' task did not start");
#elif defined(LED_BUILTIN)
  pinMode(LED_BUILTIN, OUTPUT);
#endif
#if defined(MARVIN_HAS_ROOM_SENSORS)
  // the DHT11 is read in its own task from here on; the PIR and the light from the loop
  if (!room_io::begin()) Serial.println("room: the DHT11's task did not start");
#endif
#if defined(MARVIN_HAS_HEAD)
  head_io::begin(esp_random());  // the servo stays silent for its first second
#endif
#if defined(MARVIN_HAS_SCREEN)
  // the face runs in its own task from here on, asleep until the host says someone is there
  if (!face_app::begin()) {
    Serial.println("face: screen init failed");
  }
#if MARVIN_SELF_TEST
  else {
    char line[192];
    selftest::screen_line(line, sizeof(line), face_app::screen_id());
    part_line(PART_SCREEN, line);
    face_app::greet(8000);  // after the colours: the eyes wake up and look at you
  }
#endif
#endif
#if MARVIN_SELF_TEST
  audio_begin();  // the chime and the mic level, before Wi-Fi
#endif
  sensors_begin();
  connect_wifi();
  WiFi.macAddress(mac);
  udp.begin(proto::DEVICE_PORT);
#if defined(MARVIN_HAS_OTA)
  ota::Callbacks ota_callbacks;
  ota_callbacks.on_start = [] { if (linked) send_log("OTA update started, back after the reboot"); };
  ota::begin(mac, ota_callbacks);
#endif
  media_begin();
#if MARVIN_REAL_SENSORS
  sensors_ready();
#endif
}

void loop() {
#if defined(MARVIN_HAS_OTA)
  ota::handle();                     // an update blocks here until it reboots or fails
#endif
  uint32_t ms = millis();
  link_step(ms);
  self_test_step(ms);
  room_step(ms);
  head_step(ms);
  media_step(ms);
  sensors_step(ms);
  // Simulated sensors idle for 10 ms while searching for the host; real ones must keep the UARTs drained.
  delay(linked || MARVIN_REAL_SENSORS ? 1 : 10);
}
