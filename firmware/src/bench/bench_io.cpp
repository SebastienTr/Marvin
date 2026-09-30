// SPDX-License-Identifier: MIT
#include "bench_io.h"

#if defined(MARVIN_HAS_BENCH_IO) && defined(ARDUINO) && defined(ESP32)

#include <Arduino.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <esp_random.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>
#include <math.h>
#include <string.h>

#include "../protocol.h"
#include "eyes.h"

#ifndef MARVIN_OLED_SDA
#define MARVIN_OLED_SDA -1
#endif
#ifndef MARVIN_OLED_SCL
#define MARVIN_OLED_SCL -1
#endif
#ifndef MARVIN_OLED_FPS
#define MARVIN_OLED_FPS 25
#endif
#ifndef MARVIN_BUTTON_TALK
#define MARVIN_BUTTON_TALK -1
#endif
#ifndef MARVIN_BUTTON_MUTE
#define MARVIN_BUTTON_MUTE -1
#endif
#ifndef MARVIN_BUZZER_PIN
#define MARVIN_BUZZER_PIN -1
#endif
#ifndef MARVIN_BUZZER_LEDC_CHANNEL
#define MARVIN_BUZZER_LEDC_CHANNEL 6
#endif
#ifndef MARVIN_STATUS_RGB
#define MARVIN_STATUS_RGB -1
#endif
#ifndef MARVIN_STATUS_RGB_ALT
#define MARVIN_STATUS_RGB_ALT -1
#endif
#ifndef MARVIN_LED_ACTIVE_LOW
#define MARVIN_LED_ACTIVE_LOW 0
#endif

namespace bench_io {

namespace {

constexpr bool HAS_OLED = MARVIN_OLED_SDA >= 0 && MARVIN_OLED_SCL >= 0;
constexpr bool HAS_BUZZER = MARVIN_BUZZER_PIN >= 0;
constexpr uint8_t OLED_ADDRESSES[2] = {0x3C, 0x3D};
constexpr uint32_t OLED_PROBE_MS = 5000;  // no screen: look for one this often
constexpr uint16_t I2C_TIMEOUT_MS = 20;

// ---- OLED: the eyes' task ---------------------------------------------------------------------

struct Msg {
  uint8_t type;
  uint8_t n;
  uint8_t data[24];  // FACE_STATE is 17 bytes
};

QueueHandle_t queue = nullptr;
bench::Eyes *eyes = nullptr;
volatile bool oled_present = false;
volatile bench::LinkPhase phase = bench::LinkPhase::WIFI;
volatile uint32_t i2c_errors = 0;

// U8g2's I2C transport over Arduino's Wire, counting the transfers the screen did not acknowledge
// (U8g2's own Arduino transport ignores them): that is how a screen that went away is noticed.
uint8_t i2c_byte_cb(u8x8_t *u8x8, uint8_t msg, uint8_t arg_int, void *arg_ptr) {
  switch (msg) {
    case U8X8_MSG_BYTE_SEND: {
      const uint8_t *d = (const uint8_t *)arg_ptr;
      Wire.write(d, arg_int);
      break;
    }
    case U8X8_MSG_BYTE_INIT:  // Wire is started by begin()
    case U8X8_MSG_BYTE_SET_DC:
      break;
    case U8X8_MSG_BYTE_START_TRANSFER:
      Wire.beginTransmission(u8x8_GetI2CAddress(u8x8) >> 1);
      break;
    case U8X8_MSG_BYTE_END_TRANSFER:
      if (Wire.endTransmission() != 0) i2c_errors = i2c_errors + 1;
      break;
    default:
      return 0;
  }
  return 1;
}

// An SSD1306 128 x 64 on I2C, full frame buffer (1 kB), through i2c_byte_cb.
class Oled : public U8G2 {
 public:
  Oled() : U8G2() { u8g2_Setup_ssd1306_i2c_128x64_noname_f(&u8g2, U8G2_R0, i2c_byte_cb, u8x8_gpio_and_delay_arduino); }
};

Oled *oled = nullptr;

// The address that answers, 0 if none.
uint8_t probe() {
  for (uint8_t a : OLED_ADDRESSES) {
    Wire.beginTransmission(a);
    if (Wire.endTransmission() == 0) return a;
  }
  return 0;
}

// Horizontal run from x0 to x1 (inclusive) on row y, clipped to the screen.
void hline(int x0, int x1, int y) {
  if (y < 0 || y >= bench::Eyes::HEIGHT) return;
  if (x0 < 0) x0 = 0;
  if (x1 >= bench::Eyes::WIDTH) x1 = bench::Eyes::WIDTH - 1;
  if (x1 >= x0) oled->drawHLine(x0, y, x1 - x0 + 1);
}

void fill_box(int x, int y, int w, int h, int r) {
  const bool inside = x >= 0 && y >= 0 && x + w <= bench::Eyes::WIDTH && y + h <= bench::Eyes::HEIGHT;
  if (inside && r > 0) {
    oled->drawRBox(x, y, w, h, r);
    return;
  }
  for (int row = y; row < y + h; row++) hline(x, x + w - 1, row);  // clipped: square corners at the edge
}

void fill_ellipse(int cx, int cy, int rx, int ry) {
  if (rx <= 0 || ry <= 0) return;
  for (int dy = -ry; dy <= ry; dy++) {
    float u = (float)dy / (float)ry;
    int half = (int)lroundf(rx * sqrtf(1.0f - u * u));
    hline(cx - half, cx + half, cy + dy);
  }
}

void draw_eyes(const bench::EyesLayout &l) {
  for (const bench::EyeShape &e : l.eye) {
    oled->setDrawColor(1);
    fill_box(e.x, e.y, e.w, e.h, e.r);
    oled->setDrawColor(0);
    if (e.top_lid) {
      oled->drawTriangle(e.lid_x[0], e.lid_y[0], e.lid_x[1], e.lid_y[1], e.lid_x[2], e.lid_y[2]);
      oled->drawTriangle(e.lid_x[0], e.lid_y[0], e.lid_x[2], e.lid_y[2], e.lid_x[3], e.lid_y[3]);
    }
    if (e.bottom_lid) fill_ellipse(e.ell_x, e.ell_y, e.ell_rx, e.ell_ry);
  }
  oled->setDrawColor(1);
}

const char *status_text(bench::LinkPhase p) {
  switch (p) {
    case bench::LinkPhase::WIFI: return "joining Wi-Fi";
    case bench::LinkPhase::SEARCHING: return "looking for the host";
    case bench::LinkPhase::LINKED: return nullptr;
  }
  return nullptr;
}

// Starts the screen found at `address`. False if it stopped answering meanwhile.
bool start_oled(uint8_t address) {
  const uint32_t errors = i2c_errors;
  oled->setI2CAddress(address << 1);
  oled->begin();  // init sequence, clears the screen, display on
  if (i2c_errors != errors) return false;
  Serial.printf("oled: SSD1306 at 0x%02X, eyes on\n", address);
  return true;
}

void task(void *) {
  const TickType_t period = pdMS_TO_TICKS(1000 / MARVIN_OLED_FPS) ? pdMS_TO_TICKS(1000 / MARVIN_OLED_FPS) : 1;
  uint32_t next_probe = 0;
  bool announced_missing = false;
  TickType_t wake = xTaskGetTickCount();
  for (;;) {
    const uint32_t now = millis();
    Msg m;
    while (xQueueReceive(queue, &m, 0) == pdTRUE) eyes->on_message(m.type, m.data, m.n, now);

    if (!oled_present) {
      if ((int32_t)(now - next_probe) >= 0) {
        next_probe = now + OLED_PROBE_MS;
        uint8_t a = probe();
        oled_present = a && start_oled(a);
        if (!oled_present && !announced_missing) {
          Serial.println("oled: no SSD1306 at 0x3C or 0x3D, looking again every 5 s");
          announced_missing = true;
        }
      }
      if (!oled_present) {
        vTaskDelay(pdMS_TO_TICKS(50));
        wake = xTaskGetTickCount();
        continue;
      }
    }

    const char *text = status_text(phase);
    bench::EyesLayout layout;
    eyes->frame(now, text ? 27 : bench::Eyes::HEIGHT / 2, layout);
    const uint32_t errors = i2c_errors;
    oled->clearBuffer();
    draw_eyes(layout);
    if (text) {
      oled->setFont(u8g2_font_5x7_tr);
      int w = oled->getStrWidth(text);
      oled->drawStr((bench::Eyes::WIDTH - w) / 2, bench::Eyes::HEIGHT - 1, text);
    }
    oled->sendBuffer();
    if (i2c_errors != errors) {  // the screen stopped answering: probe again, soon
      oled_present = false;
      announced_missing = false;
      next_probe = now + 1000;
      Serial.println("oled: the screen stopped answering");
    }

    // Keep the frame period, but always give up at least one tick (the idle task's watchdog).
    if ((TickType_t)(xTaskGetTickCount() - wake) >= period) {
      vTaskDelay(1);
      wake = xTaskGetTickCount();
    } else {
      vTaskDelayUntil(&wake, period);
    }
  }
}

void queue_for_eyes(uint8_t type, const uint8_t *payload, size_t n) {
  if (!queue) return;
  Msg m;
  m.type = type;
  m.n = n < sizeof(m.data) ? (uint8_t)n : (uint8_t)sizeof(m.data);
  memcpy(m.data, payload, m.n);
  xQueueSend(queue, &m, 0);  // full: dropped (the next FACE_STATE follows within 100 ms)
}

// ---- Buttons ----------------------------------------------------------------------------------

struct Key {
  int pin;
  uint8_t id;
  bench::Button button;
};
Key keys[] = {{MARVIN_BUTTON_TALK, proto::BUTTON_TALK, {}}, {MARVIN_BUTTON_MUTE, proto::BUTTON_MUTE, {}}};

uint8_t presses[4];
uint8_t n_presses = 0;

// ---- Buzzer and status LED --------------------------------------------------------------------

bench::Chime chime;
uint16_t playing_hz = 0;

#if MARVIN_STATUS_RGB >= 0
// The RGB LED (WS2812) through the RMT, one channel per data pin. neopixelWrite() is not used:
// it only ever drives the first pin it was called with.
rmt_obj_t *rgb[2] = {nullptr, nullptr};

void rgb_write(rmt_obj_t *ch, bench::Rgb8 c) {
  if (!ch) return;
  rmt_data_t bits[24];
  const uint8_t grb[3] = {c.g, c.r, c.b};
  int i = 0;
  for (uint8_t v : grb)
    for (int b = 7; b >= 0; b--, i++) {
      const bool one = v & (1 << b);
      bits[i].level0 = 1;
      bits[i].duration0 = one ? 8 : 4;  // 0.8 / 0.4 us at a 100 ns tick
      bits[i].level1 = 0;
      bits[i].duration1 = one ? 4 : 8;
    }
  rmtWriteBlocking(ch, bits, 24);  // 30 us, only when the colour changes
}

rmt_obj_t *rgb_begin(int pin) {
  if (pin < 0) return nullptr;
  rmt_obj_t *ch = rmtInit(pin, RMT_TX_MODE, RMT_MEM_64);
  if (ch) rmtSetTick(ch, 100);
  return ch;
}

bench::Rgb8 shown = {1, 1, 1};

void status_led(uint32_t now) {
  const bench::Rgb8 c = bench::status_color(phase, now);
  if (c.r == shown.r && c.g == shown.g && c.b == shown.b) return;
  shown = c;
  rgb_write(rgb[0], c);
  rgb_write(rgb[1], c);
}
#else
int led_shown = -1;

void status_led(uint32_t now) {
#ifdef LED_BUILTIN
  const int on = bench::status_led_on(phase, now) ? 1 : 0;
  if (on == led_shown) return;
  led_shown = on;
  digitalWrite(LED_BUILTIN, (on ^ MARVIN_LED_ACTIVE_LOW) ? HIGH : LOW);
#else
  (void)now;
#endif
}
#endif

}  // namespace

bool begin() {
  for (Key &k : keys)
    if (k.pin >= 0) pinMode(k.pin, INPUT_PULLUP);
  if (HAS_BUZZER) {
    ledcSetup(MARVIN_BUZZER_LEDC_CHANNEL, 2000, 10);
    ledcAttachPin(MARVIN_BUZZER_PIN, MARVIN_BUZZER_LEDC_CHANNEL);
    ledcWrite(MARVIN_BUZZER_LEDC_CHANNEL, 0);
  }
#if MARVIN_STATUS_RGB >= 0
  rgb[0] = rgb_begin(MARVIN_STATUS_RGB);
  rgb[1] = rgb_begin(MARVIN_STATUS_RGB_ALT);
#elif defined(LED_BUILTIN)
  pinMode(LED_BUILTIN, OUTPUT);
#endif
  status_led(millis());
  if (!HAS_OLED) return true;
  Wire.begin(MARVIN_OLED_SDA, MARVIN_OLED_SCL, 400000);
  Wire.setTimeOut(I2C_TIMEOUT_MS);
  queue = xQueueCreate(16, sizeof(Msg));
  eyes = new bench::Eyes(esp_random());
  oled = new Oled();
  if (!queue || !eyes || !oled) return false;
  // Core 0, below Wi-Fi; the Arduino loop keeps core 1.
  return xTaskCreatePinnedToCore(task, "eyes", 6144, nullptr, 1, nullptr, 0) == pdPASS;
}

void set_phase(bench::LinkPhase p) { phase = p; }

void step(uint32_t now_ms) {
  for (Key &k : keys) {
    if (k.pin < 0) continue;
    if (k.button.update(digitalRead(k.pin) == LOW, now_ms)) {
      chime.click(now_ms);
      if (n_presses < sizeof(presses)) presses[n_presses++] = k.id;
    }
  }
  if (HAS_BUZZER) {
    const uint16_t hz = chime.freq(now_ms);
    if (hz != playing_hz) {
      playing_hz = hz;
      ledcWriteTone(MARVIN_BUZZER_LEDC_CHANNEL, hz);  // 0: silent
    }
  }
  status_led(now_ms);
}

void on_message(uint8_t type, const uint8_t *payload, size_t n) {
  if (type == proto::SOUND) {
    if (n >= 1) play_sound(payload[0]);
    return;
  }
  if (type == proto::FACE_STATE || type == proto::FACE_EVENT) queue_for_eyes(type, payload, n);
}

bool play_sound(uint8_t id) {
  queue_for_eyes(proto::SOUND, &id, 1);
  return HAS_BUZZER && chime.start(id, millis());
}

bool next_press(uint8_t *button) {
  if (n_presses == 0) return false;
  *button = presses[0];
  memmove(presses, presses + 1, --n_presses);
  return true;
}

uint8_t hello_flags() {
  uint8_t f = 0;
  if (MARVIN_BUTTON_TALK >= 0 || MARVIN_BUTTON_MUTE >= 0) f |= proto::FLAG_BUTTONS;
  if (HAS_BUZZER) f |= proto::FLAG_BUZZER;
  if (oled_present) f |= proto::FLAG_FACE;
  return f;
}

}  // namespace bench_io

#endif
