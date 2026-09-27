// SPDX-License-Identifier: MIT
#include "camera_app.h"

#if defined(MARVIN_HAS_CAMERA) && defined(ARDUINO) && defined(ESP32)

#include <Arduino.h>
#include <esp_camera.h>
#include <esp_timer.h>
#include <fcntl.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <lwip/sockets.h>
#include <stdio.h>
#include <string.h>

#include "http_request.h"
#include "protocol.h"

#ifndef MARVIN_CAMERA_FPS
#define MARVIN_CAMERA_FPS 10
#endif
#ifndef MARVIN_CAMERA_QUALITY
#define MARVIN_CAMERA_QUALITY 12
#endif

namespace camera_app {
namespace {

// Seeed XIAO ESP32S3 Sense camera connector (Arduino-ESP32 CameraWebServer camera_pins.h,
// CAMERA_MODEL_XIAO_ESP32S3). None of these pins is used by the rest of the robot (docs/wiring.md).
constexpr int PIN_XCLK = 10, PIN_SIOD = 40, PIN_SIOC = 39;
constexpr int PIN_Y9 = 48, PIN_Y8 = 11, PIN_Y7 = 12, PIN_Y6 = 14, PIN_Y5 = 16, PIN_Y4 = 18, PIN_Y3 = 17, PIN_Y2 = 15;
constexpr int PIN_VSYNC = 38, PIN_HREF = 47, PIN_PCLK = 13;

constexpr int MAX_CLIENTS = 4;
constexpr uint32_t FRAME_MS = 1000 / (MARVIN_CAMERA_FPS > 0 ? MARVIN_CAMERA_FPS : 1);
constexpr uint32_t REQUEST_TIMEOUT_MS = 5000;
constexpr int SEND_TIMEOUT_S = 2;  // a stalled client is dropped after this

// Core 0 like Wi-Fi and the face, priority 1: the Arduino loop (UDP sensors) and the audio tasks
// keep core 1 to themselves. The task sleeps in select() or send() nearly all the time.
constexpr BaseType_t CORE = 0;
constexpr UBaseType_t PRIORITY = 1;

enum class State : uint8_t { Request, Stream, Capture };

struct Client {
  int fd = -1;
  State state = State::Request;
  uint32_t since_ms = 0;
  size_t len = 0;
  char req[512];
};

bool ok = false;
const char *sensor = "none";
Client clients[MAX_CLIENTS];

volatile uint32_t n_frames = 0, n_parts = 0, n_captures = 0, n_bytes = 0, n_clients = 0;
uint32_t last_frames = 0, last_parts = 0, last_captures = 0, last_bytes = 0, last_clients = 0, last_ms = 0;

const char INDEX_HTML[] =
    "<!doctype html><html><head><meta name=viewport content='width=device-width'><title>Marvin camera</title>"
    "</head><body style='margin:0;background:#000'><img src='/stream' style='width:100%'></body></html>";

bool send_all(int fd, const void *data, size_t n) {
  const uint8_t *p = (const uint8_t *)data;
  while (n) {
    int r = send(fd, p, n, 0);
    if (r <= 0) return false;
    p += r;
    n -= (size_t)r;
  }
  return true;
}

void drop(Client &c) {
  if (c.fd >= 0) close(c.fd);
  c.fd = -1;
}

void reply(Client &c, const char *status, const char *type, const char *body) {
  char head[192];
  int n = snprintf(head, sizeof(head), "HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %u\r\nConnection: close\r\n\r\n",
                   status, type, (unsigned)strlen(body));
  if (send_all(c.fd, head, (size_t)n)) send_all(c.fd, body, strlen(body));
  drop(c);
}

void accept_clients(int ls) {
  for (;;) {
    int fd = accept(ls, nullptr, nullptr);
    if (fd < 0) return;
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) & ~O_NONBLOCK);
    timeval tv = {SEND_TIMEOUT_S, 0};
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    Client *slot = nullptr;
    for (Client &c : clients)
      if (c.fd < 0) { slot = &c; break; }
    if (!slot) {
      static const char BUSY[] = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
      send_all(fd, BUSY, sizeof(BUSY) - 1);
      close(fd);
      continue;
    }
    slot->fd = fd;
    slot->state = State::Request;
    slot->len = 0;
    slot->since_ms = millis();
    n_clients = n_clients + 1;
  }
}

// Reads what a client sent: its request head, or (once served) only notices that it left.
void read_client(Client &c) {
  if (c.state != State::Request) {
    char junk[64];
    int r = recv(c.fd, junk, sizeof(junk), MSG_DONTWAIT);
    if (r == 0 || (r < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) drop(c);
    return;
  }
  int r = recv(c.fd, c.req + c.len, sizeof(c.req) - c.len, MSG_DONTWAIT);
  if (r <= 0) {
    if (r == 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) drop(c);
    return;
  }
  c.len += (size_t)r;
  switch (camera_http::route(c.req, c.len, c.len == sizeof(c.req))) {
    case camera_http::Route::Incomplete: break;
    case camera_http::Route::Stream: {
      char head[256];
      size_t n = camera_http::stream_head(head, sizeof(head));
      if (send_all(c.fd, head, n)) c.state = State::Stream;
      else drop(c);
      break;
    }
    case camera_http::Route::Capture: c.state = State::Capture; break;
    case camera_http::Route::Index: reply(c, "200 OK", "text/html", INDEX_HTML); break;
    case camera_http::Route::NotFound: reply(c, "404 Not Found", "text/plain", "not found: try /stream or /capture\n"); break;
    case camera_http::Route::BadRequest: reply(c, "400 Bad Request", "text/plain", "bad request\n"); break;
  }
}

// Grabs one frame and hands it to every stream and capture client.
void serve_frame() {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) return;
  uint64_t t_us = (uint64_t)fb->timestamp.tv_sec * 1000000ull + (uint64_t)fb->timestamp.tv_usec;
  if (!t_us) t_us = (uint64_t)esp_timer_get_time();
  n_frames = n_frames + 1;
  char head[256];
  for (Client &c : clients) {
    if (c.fd < 0) continue;
    if (c.state == State::Stream) {
      size_t n = camera_http::part_head(head, sizeof(head), fb->len, t_us);
      if (send_all(c.fd, head, n) && send_all(c.fd, fb->buf, fb->len) && send_all(c.fd, "\r\n", 2)) {
        n_parts = n_parts + 1;
        n_bytes = n_bytes + fb->len;
      } else {
        drop(c);
      }
    } else if (c.state == State::Capture) {
      size_t n = camera_http::capture_head(head, sizeof(head), fb->len, t_us);
      if (send_all(c.fd, head, n) && send_all(c.fd, fb->buf, fb->len)) {
        n_captures = n_captures + 1;
        n_bytes = n_bytes + fb->len;
      }
      drop(c);
    }
  }
  esp_camera_fb_return(fb);
}

void server_task(void *) {
  int ls = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  int one = 1;
  setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  sockaddr_in addr = {};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(proto::CAMERA_PORT);
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  if (ls < 0 || bind(ls, (sockaddr *)&addr, sizeof(addr)) != 0 || listen(ls, MAX_CLIENTS) != 0) {
    Serial.println("camera: cannot listen on port 81");
    vTaskDelete(nullptr);
    return;
  }
  fcntl(ls, F_SETFL, fcntl(ls, F_GETFL, 0) | O_NONBLOCK);
  uint32_t last_frame_ms = 0;
  for (;;) {
    fd_set rd;
    FD_ZERO(&rd);
    FD_SET(ls, &rd);
    int maxfd = ls;
    bool streaming = false, capture = false;
    uint32_t now = millis();
    for (Client &c : clients) {
      if (c.fd < 0) continue;
      if (c.state == State::Request && now - c.since_ms > REQUEST_TIMEOUT_MS) {
        drop(c);
        continue;
      }
      FD_SET(c.fd, &rd);
      if (c.fd > maxfd) maxfd = c.fd;
      streaming |= c.state == State::Stream;
      capture |= c.state == State::Capture;
    }
    uint32_t wait_ms = 100;
    if (capture) wait_ms = 0;
    else if (streaming) wait_ms = now - last_frame_ms >= FRAME_MS ? 0 : FRAME_MS - (now - last_frame_ms);
    timeval tv = {(time_t)(wait_ms / 1000), (suseconds_t)((wait_ms % 1000) * 1000)};
    if (select(maxfd + 1, &rd, nullptr, nullptr, &tv) > 0) {
      if (FD_ISSET(ls, &rd)) accept_clients(ls);
      for (Client &c : clients)
        if (c.fd >= 0 && FD_ISSET(c.fd, &rd)) read_client(c);
    }
    now = millis();
    capture = streaming = false;
    for (Client &c : clients) {
      if (c.fd < 0) continue;
      streaming |= c.state == State::Stream;
      capture |= c.state == State::Capture;
    }
    if (capture || (streaming && now - last_frame_ms >= FRAME_MS)) {
      last_frame_ms = now;
      serve_frame();
    }
  }
}

}  // namespace

bool begin() {
  if (ok) return true;
  if (!psramFound()) {
    Serial.println("camera: no PSRAM, camera disabled");
    return false;
  }
  camera_config_t cfg = {};
  cfg.pin_pwdn = -1;
  cfg.pin_reset = -1;
  cfg.pin_xclk = PIN_XCLK;
  cfg.pin_sccb_sda = PIN_SIOD;
  cfg.pin_sccb_scl = PIN_SIOC;
  cfg.pin_d7 = PIN_Y9;
  cfg.pin_d6 = PIN_Y8;
  cfg.pin_d5 = PIN_Y7;
  cfg.pin_d4 = PIN_Y6;
  cfg.pin_d3 = PIN_Y5;
  cfg.pin_d2 = PIN_Y4;
  cfg.pin_d1 = PIN_Y3;
  cfg.pin_d0 = PIN_Y2;
  cfg.pin_vsync = PIN_VSYNC;
  cfg.pin_href = PIN_HREF;
  cfg.pin_pclk = PIN_PCLK;
  cfg.xclk_freq_hz = 20000000;
  cfg.ledc_timer = LEDC_TIMER_0;
  cfg.ledc_channel = LEDC_CHANNEL_0;
  cfg.pixel_format = PIXFORMAT_JPEG;
  cfg.frame_size = FRAMESIZE_VGA;
  cfg.jpeg_quality = MARVIN_CAMERA_QUALITY;
  cfg.fb_count = 2;
  cfg.fb_location = CAMERA_FB_IN_PSRAM;
  cfg.grab_mode = CAMERA_GRAB_LATEST;
  esp_err_t err = esp_camera_init(&cfg);
  if (err != ESP_OK) {
    Serial.printf("camera: init failed (0x%x), camera disabled\n", (unsigned)err);
    return false;
  }
  sensor_t *s = esp_camera_sensor_get();
  if (s) {
    switch (s->id.PID) {
      case OV3660_PID:
        sensor = "OV3660";
        s->set_vflip(s, 1);  // this module comes up upside down (as in the Arduino CameraWebServer)
        s->set_brightness(s, 1);
        s->set_saturation(s, -2);
        break;
      case OV2640_PID: sensor = "OV2640"; break;
      case OV5640_PID: sensor = "OV5640"; break;
      default: sensor = "unknown sensor"; break;
    }
#ifdef MARVIN_CAMERA_VFLIP
    s->set_vflip(s, MARVIN_CAMERA_VFLIP);
#endif
#ifdef MARVIN_CAMERA_HMIRROR
    s->set_hmirror(s, MARVIN_CAMERA_HMIRROR);
#endif
  }
  if (xTaskCreatePinnedToCore(server_task, "camera", 6144, nullptr, PRIORITY, nullptr, CORE) != pdPASS) return false;
  last_ms = millis();
  ok = true;
  return true;
}

bool ready() { return ok; }

const char *sensor_name() { return sensor; }

bool stats_line(char *out, size_t cap) {
  if (!ok) return false;
  uint32_t ms = millis();
  float s = (ms - last_ms) / 1000.0f;
  if (s <= 0) s = 1;
  int streams = 0;
  for (const Client &c : clients) streams += c.fd >= 0 && c.state == State::Stream;
  uint32_t frames = n_frames, parts = n_parts, captures = n_captures, bytes = n_bytes, cl = n_clients;
  snprintf(out, cap, "camera %s: %d streaming, %.1f frames/s, %lu parts, %lu captures, %.0f kB/s, %lu connections",
           sensor, streams, (frames - last_frames) / s, (unsigned long)(parts - last_parts),
           (unsigned long)(captures - last_captures), (bytes - last_bytes) / s / 1000.0f,
           (unsigned long)(cl - last_clients));
  bool busy = streams > 0 || cl != last_clients;
  last_frames = frames;
  last_parts = parts;
  last_captures = captures;
  last_bytes = bytes;
  last_clients = cl;
  last_ms = ms;
  return busy;
}

}  // namespace camera_app

#endif
