// SPDX-License-Identifier: MIT
//
// The face rasterizer: a C++ port of host/marvin_host/raster.py.
//
// A frame is a short display list (a few rounded rectangles, triangles, ellipses and one circle)
// drawn in order over a background colour, with the same edge anti-aliasing as the reference:
// coverage = clamp(0.5 - signed distance, 0, 1), blended in float RGB, then rounded to 8 bits and
// packed to RGB565. The arithmetic follows raster.py operation by operation in single precision
// (numpy float32), so the output matches the Python frames bit for bit on a PC.
//
// Rendering goes row by row through a one-row float buffer, so it needs 3 KB of scratch memory,
// not a float frame. Each row is compared with the framebuffer as it is written: `render`
// returns the rectangle that actually changed, and the display driver only pushes that.
//
// Portable C++ (no Arduino): it is built and tested natively with `pio test -e native`.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace face {

constexpr int SCREEN_W = 240;  // px
constexpr int SCREEN_H = 280;  // px

// A colour, 0..255 per channel (float, as in raster.py).
struct Rgb {
  float r, g, b;
};

// RGB565 as the ST7789 expects it (value in native byte order; the driver sends it MSB first).
uint16_t rgb565(Rgb c);

// Half-open pixel rectangle [x0, x1) x [y0, y1). Empty when x0 >= x1 or y0 >= y1.
struct Rect {
  int x0 = 0, y0 = 0, x1 = 0, y1 = 0;
  constexpr Rect() = default;
  constexpr Rect(int x0_, int y0_, int x1_, int y1_) : x0(x0_), y0(y0_), x1(x1_), y1(y1_) {}
  bool empty() const { return x0 >= x1 || y0 >= y1; }
  int width() const { return x1 - x0; }
  int height() const { return y1 - y0; }
};

class Raster {
 public:
  static constexpr int MAX_SHAPES = 16;

  // Start a new frame: empty display list, this background colour.
  void begin(Rgb background);

  // Primitives, same signatures and conventions as raster.Canvas: coordinates in pixels, origin at
  // the top-left corner of the top-left pixel, pixel (i, j) centred on (i + 0.5, j + 0.5).
  void fill_round_rect(double cx, double cy, double w, double h, double r, Rgb color, double alpha = 1.0);
  void fill_ellipse(double cx, double cy, double rx, double ry, Rgb color, double alpha = 1.0);
  void fill_circle(double cx, double cy, double r, Rgb color, double alpha = 1.0);
  void fill_triangle(double x0, double y0, double x1, double y1, double x2, double y2, Rgb color,
                     double alpha = 1.0);
  // Convex quadrilateral, points in order: xy = {x0, y0, x1, y1, x2, y2, x3, y3}.
  void fill_quad(const double xy[8], Rgb color, double alpha = 1.0);

  // Draw the display list into `fb` (SCREEN_W x SCREEN_H, row-major RGB565) and return the
  // rectangle whose pixels changed (x0 rounded down to an even column for 32-bit aligned pushes).
  Rect render(uint16_t *fb) const;

  int shape_count() const { return n_; }

  // Distance maths: exact (hypotf, bit-identical to numpy's float32 hypot, the default off-target)
  // or fast (sqrtf(x*x + y*y), the default on ESP32, where newlib's hypotf is several times slower).
  // Fast mode may move an edge pixel by one RGB565 step; the parity test measures both.
  void set_fast_math(bool fast) { fast_ = fast; }
  bool fast_math() const { return fast_; }

 private:
  enum Kind : uint8_t { ROUND_RECT, ELLIPSE, CIRCLE, CONVEX };

  struct Shape {
    Kind kind;
    uint8_t edges;           // CONVEX: number of edges (3 or 4)
    int i0, j0, i1, j1;      // pixel window, with the 1 px anti-aliasing margin
    float color[3];
    float alpha;             // < 1: coverage is scaled
    // ROUND_RECT: cx, cy, hw - r, hh - r, r.  ELLIPSE: cx, cy, rx, ry.  CIRCLE: cx, cy, r.
    // CONVEX: per edge ax, ay, ex, ey, n.
    float p[20];
  };

  bool window(double x0, double y0, double x1, double y1, Shape &s) const;
  void push(Shape &s, Rgb color, double alpha);
  void convex(const double *xy, int n, Rgb color, double alpha);
  template <bool FAST>
  static void draw_row(const Shape &s, int j, float *row);

  Shape shapes_[MAX_SHAPES];
  int n_ = 0;
  Rgb background_{0, 0, 0};
#if defined(ESP32) || defined(ESP_PLATFORM)
  bool fast_ = true;
#else
  bool fast_ = false;
#endif
};

}  // namespace face
