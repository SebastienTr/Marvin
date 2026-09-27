// SPDX-License-Identifier: MIT
#include "raster.h"

#include <math.h>

namespace face {

namespace {

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

inline int floor_i(double v) {
  v = floor(v);
  return v < -1e6 ? -1000000 : (v > 1e6 ? 1000000 : (int)v);
}

inline int ceil_i(double v) {
  v = ceil(v);
  return v < -1e6 ? -1000000 : (v > 1e6 ? 1000000 : (int)v);
}

inline uint16_t pack(const float *px) {
  // raster.Canvas.to_uint8: clip(buf + 0.5, 0, 255) then truncate.
  uint8_t c[3];
  for (int k = 0; k < 3; k++) {
    float v = px[k] + 0.5f;
    v = v < 0.0f ? 0.0f : (v > 255.0f ? 255.0f : v);
    c[k] = (uint8_t)v;
  }
  return (uint16_t)(((c[0] >> 3) << 11) | ((c[1] >> 2) << 5) | (c[2] >> 3));
}

}  // namespace

uint16_t rgb565(Rgb c) {
  // raster.rgb565: round to the nearest integer (half to even, like Python's round), then pack.
  float in[3] = {c.r, c.g, c.b};
  int v[3];
  for (int k = 0; k < 3; k++) {
    float x = in[k] < 0.0f ? 0.0f : (in[k] > 255.0f ? 255.0f : in[k]);
    v[k] = (int)nearbyintf(x);
  }
  return (uint16_t)(((v[0] >> 3) << 11) | ((v[1] >> 2) << 5) | (v[2] >> 3));
}

void Raster::begin(Rgb background) {
  n_ = 0;
  background_ = background;
}

bool Raster::window(double x0, double y0, double x1, double y1, Shape &s) const {
  int i0 = floor_i(x0) - 1, j0 = floor_i(y0) - 1;
  int i1 = ceil_i(x1) + 1, j1 = ceil_i(y1) + 1;
  s.i0 = i0 < 0 ? 0 : i0;
  s.j0 = j0 < 0 ? 0 : j0;
  s.i1 = i1 > SCREEN_W ? SCREEN_W : i1;
  s.j1 = j1 > SCREEN_H ? SCREEN_H : j1;
  return s.i0 < s.i1 && s.j0 < s.j1;
}

void Raster::push(Shape &s, Rgb color, double alpha) {
  if (n_ >= MAX_SHAPES) return;
  s.color[0] = color.r;
  s.color[1] = color.g;
  s.color[2] = color.b;
  s.alpha = alpha < 1.0 ? (float)alpha : 1.0f;
  shapes_[n_++] = s;
}

void Raster::fill_round_rect(double cx, double cy, double w, double h, double r, Rgb color, double alpha) {
  double hw = w / 2.0, hh = h / 2.0;
  if (r > hw) r = hw;
  if (r > hh) r = hh;
  if (r < 0.0) r = 0.0;
  Shape s;
  s.kind = ROUND_RECT;
  if (!window(cx - hw, cy - hh, cx + hw, cy + hh, s)) return;
  s.p[0] = (float)cx;
  s.p[1] = (float)cy;
  s.p[2] = (float)(hw - r);
  s.p[3] = (float)(hh - r);
  s.p[4] = (float)r;
  push(s, color, alpha);
}

void Raster::fill_ellipse(double cx, double cy, double rx, double ry, Rgb color, double alpha) {
  if (rx <= 0 || ry <= 0) return;
  Shape s;
  s.kind = ELLIPSE;
  if (!window(cx - rx, cy - ry, cx + rx, cy + ry, s)) return;
  s.p[0] = (float)cx;
  s.p[1] = (float)cy;
  s.p[2] = (float)rx;
  s.p[3] = (float)ry;
  push(s, color, alpha);
}

void Raster::fill_circle(double cx, double cy, double r, Rgb color, double alpha) {
  if (r <= 0) return;
  Shape s;
  s.kind = CIRCLE;
  if (!window(cx - r, cy - r, cx + r, cy + r, s)) return;
  s.p[0] = (float)cx;
  s.p[1] = (float)cy;
  s.p[2] = (float)r;
  push(s, color, alpha);
}

void Raster::fill_triangle(double x0, double y0, double x1, double y1, double x2, double y2, Rgb color,
                           double alpha) {
  const double xy[6] = {x0, y0, x1, y1, x2, y2};
  convex(xy, 3, color, alpha);
}

void Raster::fill_quad(const double xy[8], Rgb color, double alpha) { convex(xy, 4, color, alpha); }

void Raster::convex(const double *xy, int n, Rgb color, double alpha) {
  // Shoelace sum in the same order as raster.py; reverse to a consistent winding.
  double area = 0.0;
  for (int i = 0; i < n; i++) {
    int k = (i + n - 1) % n;
    area += xy[2 * i] * xy[2 * k + 1] - xy[2 * k] * xy[2 * i + 1];
  }
  if (fabs(area) < 1e-9) return;
  double pts[8];
  for (int i = 0; i < n; i++) {
    int src = area > 0 ? n - 1 - i : i;
    pts[2 * i] = xy[2 * src];
    pts[2 * i + 1] = xy[2 * src + 1];
  }
  double x0 = pts[0], x1 = pts[0], y0 = pts[1], y1 = pts[1];
  for (int i = 1; i < n; i++) {
    x0 = fmin(x0, pts[2 * i]);
    x1 = fmax(x1, pts[2 * i]);
    y0 = fmin(y0, pts[2 * i + 1]);
    y1 = fmax(y1, pts[2 * i + 1]);
  }
  Shape s;
  s.kind = CONVEX;
  if (!window(x0, y0, x1, y1, s)) return;
  int e = 0;
  for (int i = 0; i < n; i++) {
    double ax = pts[2 * i], ay = pts[2 * i + 1];
    double bx = pts[2 * ((i + 1) % n)], by = pts[2 * ((i + 1) % n) + 1];
    double ex = bx - ax, ey = by - ay;
    double len = hypot(ex, ey);
    if (len < 1e-9) continue;
    float *q = s.p + 5 * e++;
    q[0] = (float)ax;
    q[1] = (float)ay;
    q[2] = (float)ex;
    q[3] = (float)ey;
    q[4] = (float)len;
  }
  if (e == 0) return;
  s.edges = (uint8_t)e;
  push(s, color, alpha);
}

template <bool FAST>
inline float hyp(float a, float b) {
  return FAST ? sqrtf(a * a + b * b) : hypotf(a, b);
}

template <bool FAST>
void Raster::draw_row(const Shape &s, int j, float *row) {
  const float y = (float)j + 0.5f;
  const float *p = s.p;
  const float c0 = s.color[0], c1 = s.color[1], c2 = s.color[2];
  // Row-constant parts, computed as raster.py computes its (h, 1) column arrays.
  float ry0 = 0, ry1 = 0, ty[4] = {0, 0, 0, 0};
  switch (s.kind) {
    case ROUND_RECT: ry0 = fabsf(y - p[1]) - p[3]; break;
    case ELLIPSE: ry0 = (y - p[1]) / p[3]; ry1 = ry0 / p[3]; break;
    case CIRCLE: ry0 = y - p[1]; break;
    case CONVEX:
      for (int e = 0; e < s.edges; e++) ty[e] = (y - p[5 * e + 1]) * p[5 * e + 2];
      break;
  }
  for (int i = s.i0; i < s.i1; i++) {
    const float x = (float)i + 0.5f;
    float dist;
    switch (s.kind) {
      case ROUND_RECT: {
        float qx = fabsf(x - p[0]) - p[2];
        float outside = hyp<FAST>(qx > 0.0f ? qx : 0.0f, ry0 > 0.0f ? ry0 : 0.0f);
        float m = qx > ry0 ? qx : ry0;
        float inside = m < 0.0f ? m : 0.0f;
        dist = outside + inside - p[4];
        break;
      }
      case ELLIPSE: {
        float ux = (x - p[0]) / p[2];
        float k0 = hyp<FAST>(ux, ry0);
        float k1 = hyp<FAST>(ux / p[2], ry1);
        dist = k0 * (k0 - 1.0f) / (k1 > 1e-6f ? k1 : 1e-6f);
        break;
      }
      case CIRCLE:
        dist = hyp<FAST>(x - p[0], ry0) - p[2];
        break;
      default: {
        dist = ((x - p[0]) * p[3] - ty[0]) / p[4];
        for (int e = 1; e < s.edges; e++) {
          const float *q = p + 5 * e;
          float d = ((x - q[0]) * q[3] - ty[e]) / q[4];
          if (d > dist) dist = d;
        }
        break;
      }
    }
    float cov = clamp01(0.5f - dist);
    if (cov <= 0.0f) continue;       // adding 0 * (color - px) leaves the pixel unchanged
    if (s.alpha < 1.0f) cov = cov * s.alpha;
    float *px = row + 3 * i;
    px[0] += cov * (c0 - px[0]);
    px[1] += cov * (c1 - px[1]);
    px[2] += cov * (c2 - px[2]);
  }
}

Rect Raster::render(uint16_t *fb) const {
  float row[3 * SCREEN_W];
  const float bg[3] = {background_.r, background_.g, background_.b};
  const uint16_t bg565 = pack(bg);
  int dx0 = SCREEN_W, dx1 = 0, dy0 = SCREEN_H, dy1 = 0;

  for (int j = 0; j < SCREEN_H; j++) {
    int x0 = SCREEN_W, x1 = 0;
    for (int k = 0; k < n_; k++) {
      const Shape &s = shapes_[k];
      if (j < s.j0 || j >= s.j1) continue;
      if (s.i0 < x0) x0 = s.i0;
      if (s.i1 > x1) x1 = s.i1;
    }
    if (x0 < x1) {
      for (int i = x0; i < x1; i++) {
        row[3 * i] = bg[0];
        row[3 * i + 1] = bg[1];
        row[3 * i + 2] = bg[2];
      }
      for (int k = 0; k < n_; k++) {
        const Shape &s = shapes_[k];
        if (j < s.j0 || j >= s.j1) continue;
        if (fast_)
          draw_row<true>(s, j, row);
        else
          draw_row<false>(s, j, row);
      }
    }
    uint16_t *out = fb + j * SCREEN_W;
    int cx0 = SCREEN_W, cx1 = 0;
    for (int i = 0; i < SCREEN_W; i++) {
      uint16_t v = (i >= x0 && i < x1) ? pack(row + 3 * i) : bg565;
      if (out[i] != v) {
        out[i] = v;
        if (i < cx0) cx0 = i;
        cx1 = i + 1;
      }
    }
    if (cx0 < cx1) {
      if (cx0 < dx0) dx0 = cx0;
      if (cx1 > dx1) dx1 = cx1;
      if (j < dy0) dy0 = j;
      dy1 = j + 1;
    }
  }
  Rect r;
  if (dx0 < dx1) {
    r.x0 = dx0 & ~1;
    r.x1 = dx1;
    r.y0 = dy0;
    r.y1 = dy1;
  }
  return r;
}

}  // namespace face
