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
//
// SPDX-License-Identifier: MIT
#include <Arduino.h>

#if defined(ESP8266)
#include <ESP8266WiFi.h>
#else
#include <WiFi.h>
#endif
#include <WiFiUdp.h>

#include "protocol.h"
#include "sensor_framing.h"
#include "sensors_real.h"
#include "sensors_sim.h"
#if defined(MARVIN_HAS_SCREEN)
#include "face/face_app.h"
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

#if MARVIN_REAL_SENSORS && !defined(ARDUINO_ARCH_ESP32)
#error "MARVIN_SENSORS=REAL needs an ESP32-S3 (lidar on UART0, LD2450 on UART1). The D1 mini (ESP8266) runs simulated sensors only: build it with MARVIN_SENSORS=SIM."
#endif

namespace {

constexpr int LIDAR_BATCH = 10;                 // LDROBOT packets per datagram
constexpr uint32_t LINK_TIMEOUT_MS = 6000;
constexpr uint32_t LIDAR_FLUSH_MS = 20;         // real sensors: send a partial batch after this
constexpr uint32_t STATS_PERIOD_MS = 5000;      // real sensors: counters LOG period
constexpr uint8_t LIDAR_MODEL = MARVIN_REAL_SENSORS ? MARVIN_LIDAR_MODEL : MARVIN_SIM_LIDAR_MODEL;

const char *lidar_name(uint8_t model) { return model == 2 ? "D800" : "D500"; }

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
#ifdef LED_BUILTIN
  digitalWrite(LED_BUILTIN, (on ^ MARVIN_LED_ACTIVE_LOW) ? HIGH : LOW);
#endif
}

// Sends the datagram in `dgram` (header space + payload_len bytes of payload), stamped t_us.
void send_datagram(uint8_t *dgram, proto::Type type, size_t payload_len, uint64_t t_us) {
  proto::header(dgram, type, seq++, t_us);
  IPAddress to = linked ? host : IPAddress(255, 255, 255, 255);
  udp.beginPacket(to, proto::HOST_PORT);
  udp.write(dgram, proto::HEADER_SIZE + payload_len);
  udp.endPacket();
}

// Sends the payload already written in buf, stamped now.
void send(proto::Type type, size_t payload_len) { send_datagram(buf, type, payload_len, now_us()); }

void send_hello() {
  const uint8_t flags = MARVIN_REAL_SENSORS ? 0 : proto::FLAG_SIMULATED;
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

void on_host_ack() {
  last_ack_ms = millis();
  if (linked && udp.remoteIP() == host) return;
  host = udp.remoteIP();
  linked = true;
  Serial.printf("linked to host %s\n", host.toString().c_str());
  char msg[96];
#if MARVIN_REAL_SENSORS
  snprintf(msg, sizeof(msg), "firmware %s up, real %s lidar, LD2450 %s", MARVIN_FW_VERSION,
           lidar_name(LIDAR_MODEL), ld2450_configured ? "in multi-target mode" : "did not answer at boot");
#else
  snprintf(msg, sizeof(msg), "firmware %s up, simulated %s lidar", MARVIN_FW_VERSION, lidar_name(LIDAR_MODEL));
#endif
  send_log(msg);
}

// Reads the datagrams from the host.
void poll_host() {
  static uint8_t in[512];
  while (udp.parsePacket()) {
    int n = udp.read(in, sizeof(in));
    if (n < (int)proto::HEADER_SIZE || !proto::valid(in, n)) continue;
    switch (in[3]) {
      case proto::HOST_ACK:
        if (n >= (int)proto::HEADER_SIZE + 8) on_host_ack();
        break;
      default:
        // other host -> robot messages are dispatched here
        // (type in[3], payload in + proto::HEADER_SIZE, n - proto::HEADER_SIZE bytes, sender udp.remoteIP())
#if defined(MARVIN_HAS_SCREEN)
        if (linked && udp.remoteIP() == host && n >= (int)proto::HEADER_SIZE)
          face_app::on_message(in[3], in + proto::HEADER_SIZE, n - proto::HEADER_SIZE);  // queued, never blocks
#endif
        break;
    }
  }
}

void connect_wifi() {
  WiFi.mode(WIFI_STA);
#if defined(ESP8266)
  WiFi.setSleepMode(WIFI_NONE_SLEEP);           // modem sleep adds latency and drops broadcast replies
#else
  WiFi.setSleep(false);
#endif
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.printf("joining %s", WIFI_SSID);
  while (WiFi.status() != WL_CONNECTED) {
    led(true); delay(100); led(false); delay(100);
    Serial.print('.');
  }
  Serial.printf("\nIP %s, RSSI %d dBm\n", WiFi.localIP().toString().c_str(), WiFi.RSSI());
}

// Host datagrams, link timeout, HELLO, status LED.
void link_step(uint32_t ms) {
  static uint32_t next_hello = 0;
  poll_host();
  if (linked && ms - last_ack_ms > LINK_TIMEOUT_MS) {
    linked = false;
    Serial.println("host lost, searching");
  }
  if ((int32_t)(ms - next_hello) >= 0) {
    send_hello();
    next_hello = ms + (linked ? 2000 : 500);
  }
  led(linked ? (ms % 2000) < 100 : (ms % 250) < 125);
}

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

void on_ld2450_frame(const uint8_t *frame) {
  if (!linked) return;
  memcpy(buf + proto::HEADER_SIZE, frame, framing::LD2450_FRAME);
  send(proto::LD2450, framing::LD2450_FRAME);
}

// Serial log (always) and LOG to the host (when linked): rates and error counts since the last one.
void report_stats(uint32_t ms) {
  real::Counters c = real::counters(), &p = last_counters;
  float s = (ms - last_stats_ms) / 1000.0f;
  char msg[160];
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
  Serial.printf("lidar %s on UART0 RX GPIO44 at %lu baud\n", lidar_name(LIDAR_MODEL),
                (unsigned long)real::lidar_baud(LIDAR_MODEL));
  ld2450_configured = real::begin(LIDAR_MODEL);
  Serial.printf("LD2450 on UART1 (RX GPIO5, TX GPIO6) at 256000 baud: %s\n",
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

}  // namespace

// ---- Arduino entry points ---------------------------------------------------------------------

void setup() {
  Serial.begin(MARVIN_SERIAL_BAUD);
  delay(200);
  Serial.printf("\nMarvin firmware %s, board %d, %s sensors\n", MARVIN_FW_VERSION, MARVIN_BOARD,
                MARVIN_REAL_SENSORS ? "real" : "simulated");
#ifdef LED_BUILTIN
  pinMode(LED_BUILTIN, OUTPUT);
#endif
#if defined(MARVIN_HAS_SCREEN)
  // the face runs in its own task from here on, asleep until the host says someone is there
  if (!face_app::begin()) Serial.println("face: screen init failed");
#endif
  sensors_begin();
  connect_wifi();
  WiFi.macAddress(mac);
  udp.begin(proto::DEVICE_PORT);
#if MARVIN_REAL_SENSORS
  sensors_ready();
#endif
}

void loop() {
  uint32_t ms = millis();
  link_step(ms);
  sensors_step(ms);
  // Simulated sensors idle for 10 ms while searching for the host; real ones must keep the UARTs drained.
  delay(linked || MARVIN_REAL_SENSORS ? 1 : 10);
}
