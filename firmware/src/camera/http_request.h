// The camera server's tiny HTTP layer: request routing and the MJPEG multipart framing.
// Portable C++ (no Arduino), unit-tested with `pio test -e native` (test/test_audio).
//
// SPDX-License-Identifier: MIT
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace camera_http {

enum class Route : uint8_t {
  Incomplete,  // the request head has not fully arrived yet
  Stream,      // GET /stream   multipart/x-mixed-replace MJPEG
  Capture,     // GET /capture  one JPEG
  Index,       // GET /         a small HTML page showing the stream
  NotFound,    // any other path
  BadRequest,  // not a GET, malformed, or a head larger than the buffer
};

constexpr const char *BOUNDARY = "marvinframe";

// Routes a request from the bytes received so far (need not be NUL-terminated). The head is
// complete once "\r\n\r\n" arrived; `full` says the receive buffer is full, so an incomplete head
// is rejected. The query string (e.g. /capture?t=123 against caching) is ignored.
inline Route route(const char *req, size_t len, bool full) {
  bool complete = false;
  for (size_t i = 3; i < len && !complete; i++)
    complete = req[i - 3] == '\r' && req[i - 2] == '\n' && req[i - 1] == '\r' && req[i] == '\n';
  if (!complete) return full ? Route::BadRequest : Route::Incomplete;
  if (len < 5 || memcmp(req, "GET ", 4) != 0) return Route::BadRequest;
  size_t p = 4, e = p;
  while (e < len && req[e] != ' ' && req[e] != '?' && req[e] != '\r') e++;
  size_t n = e - p;
  if (n == 0 || req[p] != '/') return Route::BadRequest;
  auto is = [&](const char *path) { return n == strlen(path) && memcmp(req + p, path, n) == 0; };
  if (is("/stream")) return Route::Stream;
  if (is("/capture") || is("/capture.jpg")) return Route::Capture;
  if (is("/")) return Route::Index;
  return Route::NotFound;
}

// Response head of /stream.
inline size_t stream_head(char *out, size_t cap) {
  int n = snprintf(out, cap,
                   "HTTP/1.1 200 OK\r\n"
                   "Content-Type: multipart/x-mixed-replace; boundary=%s\r\n"
                   "Cache-Control: no-cache, no-store\r\n"
                   "Access-Control-Allow-Origin: *\r\n"
                   "Connection: close\r\n\r\n",
                   BOUNDARY);
  return n < 0 ? 0 : (size_t)n < cap ? (size_t)n : cap - 1;
}

// Head of one multipart part (a JPEG of `len` bytes grabbed at robot time t_us). The part is
// this head, the JPEG, then "\r\n".
inline size_t part_head(char *out, size_t cap, size_t len, uint64_t t_us) {
  int n = snprintf(out, cap,
                   "--%s\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\nX-Marvin-Time-Us: %llu\r\n\r\n",
                   BOUNDARY, (unsigned)len, (unsigned long long)t_us);
  return n < 0 ? 0 : (size_t)n < cap ? (size_t)n : cap - 1;
}

// Response head of /capture.
inline size_t capture_head(char *out, size_t cap, size_t len, uint64_t t_us) {
  int n = snprintf(out, cap,
                   "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n"
                   "X-Marvin-Time-Us: %llu\r\nCache-Control: no-cache, no-store\r\n"
                   "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n",
                   (unsigned)len, (unsigned long long)t_us);
  return n < 0 ? 0 : (size_t)n < cap ? (size_t)n : cap - 1;
}

}  // namespace camera_http
