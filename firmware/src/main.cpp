// Marvin firmware, prototype stage: Wi-Fi link to the host and simulated sensors.
//
// Boot -> join Wi-Fi -> broadcast HELLO every 0.5 s -> the host answers HOST_ACK ->
// unicast lidar and radar frames to that host, HELLO every 2 s as a heartbeat.
// No ACK for 6 s: back to broadcasting. The LED blinks fast while searching, slowly once linked.
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
#include "sensors_sim.h"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Copy include/secrets.example.h to include/secrets.h and put your Wi-Fi in it."
#endif

#ifndef MARVIN_BOARD
#define MARVIN_BOARD 0
#endif
#ifndef MARVIN_SIM_LIDAR_MODEL
#define MARVIN_SIM_LIDAR_MODEL 1
#endif
#ifndef MARVIN_LED_ACTIVE_LOW
#define MARVIN_LED_ACTIVE_LOW 0
#endif

namespace {

constexpr int LIDAR_BATCH = 10;                 // LDROBOT packets per datagram
constexpr uint32_t LINK_TIMEOUT_MS = 6000;

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

void send(proto::Type type, size_t payload_len) {
  proto::header(buf, type, seq++, now_us());
  IPAddress to = linked ? host : IPAddress(255, 255, 255, 255);
  udp.beginPacket(to, proto::HOST_PORT);
  udp.write(buf, proto::HEADER_SIZE + payload_len);
  udp.endPacket();
}

void send_hello() {
  size_t n = proto::hello(buf + proto::HEADER_SIZE, mac, MARVIN_BOARD, proto::FLAG_SIMULATED,
                          (int8_t)WiFi.RSSI(), millis(), MARVIN_FW_VERSION);
  send(proto::HELLO, n);
}

void send_log(const char *text) {
  size_t n = strlen(text);
  if (n > sizeof(buf) - proto::HEADER_SIZE) n = sizeof(buf) - proto::HEADER_SIZE;
  memcpy(buf + proto::HEADER_SIZE, text, n);
  send(proto::LOG, n);
}

void poll_host() {
  uint8_t in[32];
  while (int size = udp.parsePacket()) {
    int n = udp.read(in, sizeof(in));
    if (n >= (int)proto::HEADER_SIZE + 8 && proto::valid(in, n) && in[3] == proto::HOST_ACK) {
      last_ack_ms = millis();
      if (!linked || udp.remoteIP() != host) {
        host = udp.remoteIP();
        linked = true;
        Serial.printf("linked to host %s\n", host.toString().c_str());
        char msg[96];
        snprintf(msg, sizeof(msg), "firmware %s up, simulated %s lidar", MARVIN_FW_VERSION,
                 MARVIN_SIM_LIDAR_MODEL == 2 ? "D800" : "D500");
        send_log(msg);
      }
    }
    (void)size;
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

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.printf("\nMarvin firmware %s, board %d\n", MARVIN_FW_VERSION, MARVIN_BOARD);
#ifdef LED_BUILTIN
  pinMode(LED_BUILTIN, OUTPUT);
#endif
  uint32_t t0 = millis();
  sim::begin(MARVIN_SIM_LIDAR_MODEL, [] { yield(); });
  Serial.printf("simulated room ready in %lu ms\n", (unsigned long)(millis() - t0));
  connect_wifi();
  WiFi.macAddress(mac);
  udp.begin(proto::DEVICE_PORT);
}

void loop() {
  static uint32_t next_hello = 0, next_radar = 0, sent_packets = 0, link_start_ms = 0;
  uint32_t ms = millis();
  float t = ms / 1000.0f;

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

  if (!linked) {
    link_start_ms = ms;
    sent_packets = 0;
    delay(10);
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
  delay(1);
}
