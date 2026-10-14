// Marvin MR60BHA2 bridge: firmware for the XIAO ESP32C6 of Seeed's MR60BHA2 kit.
//
// Reads the 60 GHz radar on UART (tiny-frame protocol) and sends its vital signs to the Marvin
// host as VITALS messages, speaking protocol v1 exactly like the robot:
// boot -> join Wi-Fi -> broadcast HELLO every 0.5 s -> the host answers HOST_ACK ->
// unicast VITALS at 10 Hz to that host, HELLO every 2 s as a heartbeat.
// No ACK for 6 s: back to broadcasting. Wi-Fi lost: rejoins with a backoff (5 s doubling to 60 s) while the
// radar keeps being read; radar silent for 10 s: its UART is reopened (backoff 10 s to 60 s).
// Built with -DMARVIN_HAS_OTA, it takes firmware updates over Wi-Fi (the main firmware's src/ota.cpp).
//
// SPDX-License-Identifier: MIT
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>

#include "mr60.h"
#include "protocol.h"
#if defined(MARVIN_HAS_OTA)
#include "ota.h"
#endif

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Copy include/secrets.example.h to include/secrets.h and put your Wi-Fi in it."
#endif

#ifndef MARVIN_BOARD
#define MARVIN_BOARD 4
#endif

namespace {

// Kit wiring (same as Seeed's stock ESPHome configuration).
constexpr int RADAR_RX = 17;          // D7, radar TX -> C6
constexpr int RADAR_TX = 16;          // D6, C6 -> radar RX
constexpr uint32_t RADAR_BAUD = 115200;
constexpr int RGB_PIN = 1;            // D1, one WS2812

constexpr uint32_t LINK_TIMEOUT_MS = 6000;
constexpr uint32_t VITALS_PERIOD_MS = 100;
constexpr uint32_t STATUS_PERIOD_MS = 5000;
constexpr uint32_t RADAR_REOPEN_MS = 10000;   // radar silent this long: reopen its UART

HardwareSerial radar(1);
mr60::Parser parser;
mr60::Tracker tracker;

WiFiUDP udp;
IPAddress host;
bool linked = false;
uint32_t last_ack_ms = 0;
uint32_t seq = 0;
uint8_t mac[6];
uint8_t buf[proto::HEADER_SIZE + 128];
bool online = false;                  // Wi-Fi joined (UDP and OTA started on the first join)
bool network_started = false;
mr60::Backoff wifi_retry(5000, 60000);   // a join itself takes a few seconds
mr60::Backoff radar_retry(RADAR_REOPEN_MS, 60000);
uint32_t radar_heard_ms = 0;

uint64_t now_us() { return (uint64_t)esp_timer_get_time(); }

void send(proto::Type type, size_t payload_len) {
  proto::header(buf, type, seq++, now_us());
  IPAddress to = linked ? host : IPAddress(255, 255, 255, 255);
  udp.beginPacket(to, proto::HOST_PORT);
  udp.write(buf, proto::HEADER_SIZE + payload_len);
  udp.endPacket();
}

void send_hello() {
  size_t n = proto::hello(buf + proto::HEADER_SIZE, mac, MARVIN_BOARD, 0, (int8_t)WiFi.RSSI(), millis(),
                          MARVIN_FW_VERSION);
  send(proto::HELLO, n);
}

void send_log(const char *text) {
  size_t n = strlen(text);
  if (n > sizeof(buf) - proto::HEADER_SIZE) n = sizeof(buf) - proto::HEADER_SIZE;
  memcpy(buf + proto::HEADER_SIZE, text, n);
  send(proto::LOG, n);
}

void radar_version(char *out, size_t size) {
  mr60::FirmwareVersion fw;
  if (tracker.firmware(fw))
    snprintf(out, size, "%u.%u.%u (project %u)", fw.major, fw.minor, fw.patch, fw.project);
  else
    snprintf(out, size, "%s", tracker.radar_alive(millis()) ? "not reported" : "silent");
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
        char fw[32], msg[96];
        radar_version(fw, sizeof(fw));
        snprintf(msg, sizeof(msg), "firmware %s up, MR60BHA2 radar %s", MARVIN_FW_VERSION, fw);
        send_log(msg);
      }
    }
    (void)size;
  }
}

void poll_radar() {
  uint32_t ms = millis();
  // bounded, so a babbling UART cannot starve the network side
  for (int budget = 512; budget > 0 && radar.available(); budget--) {
    if (parser.feed((uint8_t)radar.read())) {
      const mr60::Frame &f = parser.frame();
      bool had_fw = tracker.has_firmware();
      tracker.apply(f.type, f.payload, f.len, ms);
      mr60::FirmwareVersion fw;
      if (!had_fw && tracker.firmware(fw))
        Serial.printf("radar firmware %u.%u.%u (project %u)\n", fw.major, fw.minor, fw.patch, fw.project);
    }
  }
}

// Status light, kept dim: searching = blue blink, linked = slow green pulse,
// a person measured = soft red heartbeat at the measured rate.
void update_led(uint32_t ms, const mr60::Vitals &v) {
  static uint8_t last[3] = {255, 255, 255};
  uint8_t c[3] = {0, 0, 0};
  if (!online) {
    if (ms % 200 < 100) c[2] = 10;                                  // joining Wi-Fi: fast blue blink
  } else if (!linked) {
    if (ms % 1000 < 80) c[2] = 10;
  } else if (v.valid) {
    uint32_t period = (uint32_t)(60000.0f / (v.heart_rate > 30 ? v.heart_rate : 60));
    uint32_t t = ms % period;
    if (t < 90 || (t >= 180 && t < 250)) c[0] = t < 90 ? 14 : 7;   // lub-dub
  } else {
    float s = 0.5f - 0.5f * cosf(2 * (float)M_PI * (ms % 4000) / 4000.0f);
    c[1] = (uint8_t)(1 + 7 * s);
  }
  if (memcmp(c, last, 3) != 0) {
    rgbLedWrite(RGB_PIN, c[0], c[1], c[2]);   // handles the WS2812's GRB order
    memcpy(last, c, 3);
  }
}

void log_status(uint32_t ms, const mr60::Vitals &v) {
  if (!tracker.radar_alive(ms)) {
    Serial.printf("radar: no data (frames %lu, errors %lu)\n", (unsigned long)parser.frames(),
                  (unsigned long)parser.errors());
    return;
  }
  Serial.printf("radar: %s, breath %.1f/min, heart %.1f/min, distance %u mm%s%s (frames %lu, errors %lu)\n",
                tracker.present(ms) ? "person" : "nobody", v.breath_rate, v.heart_rate, v.distance_mm,
                v.valid ? ", valid" : "", v.steady ? ", steady" : "", (unsigned long)parser.frames(),
                (unsigned long)parser.errors());
}

#if defined(MARVIN_HAS_OTA)
void ota_started() {
  rgbLedWrite(RGB_PIN, 8, 0, 8);               // purple while an update is written
  if (linked) send_log("OTA update starting");
}
void ota_ended(bool ok) { rgbLedWrite(RGB_PIN, ok ? 0 : 14, ok ? 8 : 0, 0); }
#endif

// The first join starts UDP (and OTA); a lost network is rejoined with a backoff, never by blocking:
// the radar keeps being read and the LED keeps blinking meanwhile.
void keep_wifi(uint32_t ms) {
  bool up = WiFi.status() == WL_CONNECTED;
  if (up && !online) {
    online = true;
    wifi_retry.succeeded();
    WiFi.macAddress(mac);
    Serial.printf("Wi-Fi: joined %s, IP %s, RSSI %d dBm\n", WIFI_SSID, WiFi.localIP().toString().c_str(), WiFi.RSSI());
    if (!network_started) {
      network_started = true;
      udp.begin(proto::DEVICE_PORT);
#if defined(MARVIN_HAS_OTA)
      ota::Callbacks cb;
      cb.on_start = ota_started;
      cb.on_end = ota_ended;
      ota::begin(mac, cb);
#endif
    }
  } else if (!up) {
    if (online) {
      online = false;
      linked = false;
      Serial.println("Wi-Fi: lost, rejoining");
    }
    if (wifi_retry.due(ms)) {
      if (wifi_retry.delay_ms() > 5000)
        Serial.printf("Wi-Fi: %s not joined yet, next try in %lu s\n", WIFI_SSID, (unsigned long)(wifi_retry.delay_ms() / 1000));
      WiFi.disconnect();
      WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    }
  }
}

// A radar that stops talking (loose contact, brown-out, a stuck UART) gets its UART reopened.
void keep_radar(uint32_t ms) {
  if (tracker.radar_alive(ms)) {
    radar_heard_ms = ms;
    radar_retry.succeeded();
    return;
  }
  if (ms - radar_heard_ms < RADAR_REOPEN_MS || !radar_retry.due(ms)) return;
  Serial.printf("radar: silent for %lu s, reopening its UART\n", (unsigned long)((ms - radar_heard_ms) / 1000));
  if (linked) send_log("MR60BHA2 radar silent, reopening its UART");
  radar.end();
  radar.setRxBufferSize(2048);
  radar.begin(RADAR_BAUD, SERIAL_8N1, RADAR_RX, RADAR_TX);
  parser = mr60::Parser();
}

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.printf("\nMarvin MR60BHA2 bridge %s, board %d\n", MARVIN_FW_VERSION, MARVIN_BOARD);
  rgbLedWrite(RGB_PIN, 0, 0, 0);
  radar.setRxBufferSize(2048);
  radar.begin(RADAR_BAUD, SERIAL_8N1, RADAR_RX, RADAR_TX);
  Serial.printf("radar on UART1, RX GPIO%d, TX GPIO%d, %lu baud\n", RADAR_RX, RADAR_TX, (unsigned long)RADAR_BAUD);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);                         // modem sleep adds latency and drops broadcast replies
  WiFi.setAutoReconnect(false);                 // keep_wifi() rejoins, with its own backoff
  Serial.printf("Wi-Fi: joining %s\n", WIFI_SSID);
}

void loop() {
  static uint32_t next_hello = 0, next_vitals = 0, next_status = 0;
#if defined(MARVIN_HAS_OTA)
  if (network_started) {
    ota::handle();
    if (ota::in_progress()) return;
  }
#endif
  poll_radar();
  uint32_t ms = millis();
  keep_wifi(ms);
  keep_radar(ms);
  if (online) poll_host();
  if (linked && ms - last_ack_ms > LINK_TIMEOUT_MS) {
    linked = false;
    Serial.println("host lost, searching");
  }
  if (online && (int32_t)(ms - next_hello) >= 0) {
    send_hello();
    next_hello = ms + (linked ? 2000 : 500);
  }
  mr60::Vitals v = tracker.vitals(ms);
  if (linked && (int32_t)(ms - next_vitals) >= 0) {
    uint8_t *p = buf + proto::HEADER_SIZE;
    send(proto::VITALS, mr60::encode_vitals(v, p));
    next_vitals = ms + VITALS_PERIOD_MS;
  }
  if ((int32_t)(ms - next_status) >= 0) {
    log_status(ms, v);
    next_status = ms + STATUS_PERIOD_MS;
  }
  update_led(ms, v);
  delay(2);
}
