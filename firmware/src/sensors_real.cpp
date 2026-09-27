// SPDX-License-Identifier: MIT
#include "sensors_real.h"

#if defined(ARDUINO_ARCH_ESP32)

#include <Arduino.h>

#include "sensor_framing.h"

namespace real {
namespace {

constexpr int LIDAR_RX = 44;          // XIAO D7, UART0 RX
constexpr int LD2450_RX = 5;          // XIAO D4, UART1 RX  <- LD2450 TX
constexpr int LD2450_TX = 6;          // XIAO D5, UART1 TX  -> LD2450 RX
constexpr uint32_t LD2450_BAUD = 256000;
constexpr size_t LIDAR_RX_BUFFER = 4096;    // ~44 ms at 921 600 baud
constexpr size_t LD2450_RX_BUFFER = 1024;
constexpr uint32_t ACK_TIMEOUT_MS = 250;
constexpr int INIT_ATTEMPTS = 3;

// With USB CDC on boot, `Serial` is the USB port and UART0 is free for the lidar as Serial0.
// Only its RX pin is used; TX keeps its boot-time pin (GPIO43), which the I2S amplifier takes
// over when audio is initialised.
HardwareSerial &lidar_uart = Serial0;
HardwareSerial &radar_uart = Serial1;

framing::LidarFramer lidar;
framing::Ld2450Framer radar;
volatile uint32_t overflows = 0;

void count_overflow(hardwareSerial_error_t err) {
  if (err == UART_BUFFER_FULL_ERROR || err == UART_FIFO_OVF_ERROR) overflows = overflows + 1;
}

// Sends one LD2450 command and waits for its ACK. Target frames arriving meanwhile are skipped.
bool command(const uint8_t *frame, size_t len, uint16_t word, const char *name) {
  radar_uart.write(frame, len);
  radar_uart.flush();
  bool acked = false, ok = false;
  auto on_frame = [&](framing::Ld2450Framer::Kind kind, const uint8_t *f, size_t n) {
    uint16_t cmd, status;
    if (kind == framing::Ld2450Framer::ACK && framing::ld2450_cmd::parse_ack(f, n, &cmd, &status) &&
        cmd == word) {
      acked = true;
      ok = status == 0;
    }
  };
  uint32_t t0 = millis();
  while (!acked && millis() - t0 < ACK_TIMEOUT_MS) {
    uint8_t in[64];
    size_t n = radar_uart.read(in, sizeof(in));
    if (n) radar.feed(in, n, on_frame);
    else delay(1);
  }
  if (!acked) Serial.printf("LD2450: no ACK for %s\n", name);
  else if (!ok) Serial.printf("LD2450: %s refused\n", name);
  return ok;
}

bool configure_ld2450() {
  namespace cmd = framing::ld2450_cmd;
  uint8_t f[cmd::MAX_SIZE];
  for (int attempt = 1; attempt <= INIT_ATTEMPTS; attempt++) {
    bool ok = command(f, cmd::enable_config(f), cmd::ENABLE_CONFIG, "enable configuration");
    ok = ok && command(f, cmd::multi_target(f), cmd::MULTI_TARGET, "multi-target tracking");
    // always leave configuration mode, or the radar stops reporting targets
    bool ended = command(f, cmd::end_config(f), cmd::END_CONFIG, "end configuration");
    if (ok && ended) return true;
    delay(100);
  }
  return false;
}

}  // namespace

uint32_t lidar_baud(uint8_t lidar_model) { return lidar_model == 2 ? 921600 : 230400; }

bool begin(uint8_t lidar_model) {
  lidar_uart.setRxBufferSize(LIDAR_RX_BUFFER);        // must come before begin()
  lidar_uart.begin(lidar_baud(lidar_model), SERIAL_8N1, LIDAR_RX, -1);
  lidar_uart.onReceiveError(count_overflow);
  radar_uart.setRxBufferSize(LD2450_RX_BUFFER);
  radar_uart.begin(LD2450_BAUD, SERIAL_8N1, LD2450_RX, LD2450_TX);
  radar_uart.onReceiveError(count_overflow);
  return configure_ld2450();
}

void poll(FrameFn on_lidar, FrameFn on_ld2450) {
  uint8_t in[256];
  auto lidar_cb = [on_lidar](const uint8_t *p) { if (on_lidar) on_lidar(p); };
  auto radar_cb = [on_ld2450](framing::Ld2450Framer::Kind kind, const uint8_t *f, size_t) {
    if (kind == framing::Ld2450Framer::TARGETS && on_ld2450) on_ld2450(f);
  };
  // Bounded work per call: at most what was already buffered when we started.
  for (size_t left = lidar_uart.available(); left;) {
    size_t n = lidar_uart.read(in, left < sizeof(in) ? left : sizeof(in));
    if (!n) break;
    lidar.feed(in, n, lidar_cb);
    left -= n < left ? n : left;
  }
  for (size_t left = radar_uart.available(); left;) {
    size_t n = radar_uart.read(in, left < sizeof(in) ? left : sizeof(in));
    if (!n) break;
    radar.feed(in, n, radar_cb);
    left -= n < left ? n : left;
  }
}

Counters counters() {
  Counters c;
  c.lidar_packets = lidar.packets();
  c.lidar_crc_errors = lidar.crc_errors();
  c.lidar_dropped_bytes = lidar.dropped_bytes();
  c.ld2450_frames = radar.frames();
  c.ld2450_bad_frames = radar.bad_frames();
  c.ld2450_dropped_bytes = radar.dropped_bytes();
  c.uart_overflows = overflows;
  return c;
}

}  // namespace real

#endif  // ARDUINO_ARCH_ESP32
