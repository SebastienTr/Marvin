"""A tiny anti-aliased rasterizer for the face: the handful of primitives an embedded graphics
library offers (filled rounded rectangle, ellipse, circle, triangle), nothing more.

It exists so the host can render the robot's face exactly as the ESP32-S3 will. Every call maps to
one TFT_eSPI / LovyanGFX call (fillRoundRect, fillEllipse, fillCircle, fillTriangle); the only
extra is edge anti-aliasing, done from a signed distance per pixel (coverage = 0.5 - distance,
clipped to [0, 1]). On the device, the same shapes can be drawn without anti-aliasing, or with
the library's smooth variants (LovyanGFX fillSmoothRoundRect, fillSmoothCircle).

Coordinates are floats in pixels, the origin at the top-left corner of the top-left pixel, x to
the right, y down; the centre of pixel (i, j) is (i + 0.5, j + 0.5). Colours are RGB tuples in
0..255. Only the bounding box of each primitive is touched, so drawing cost scales with the
shape's area, not with the frame.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math

import numpy as np

Color = tuple[float, float, float]


def rgb565(color: Color) -> int:
    """Pack an RGB colour (0..255 each) into the 16-bit RGB565 value the ST7789 expects."""
    r, g, b = (int(round(max(0.0, min(255.0, c)))) for c in color)
    return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)


class Canvas:
    """A float RGB frame buffer with anti-aliased primitives."""

    def __init__(self, width: int, height: int, background: Color = (0, 0, 0)):
        self.width = width
        self.height = height
        self.buf = np.empty((height, width, 3), np.float32)
        self.fill(background)

    def fill(self, color: Color) -> None:
        self.buf[:] = color

    def to_uint8(self) -> np.ndarray:
        """The frame as an (height, width, 3) uint8 RGB image."""
        return np.clip(self.buf + 0.5, 0, 255).astype(np.uint8)

    # -- primitives ------------------------------------------------------------------------

    def fill_round_rect(self, cx: float, cy: float, w: float, h: float, r: float,
                        color: Color, alpha: float = 1.0) -> None:
        """Rounded rectangle centred on (cx, cy), size w x h, corner radius r (clamped to fit)."""
        hw, hh = w / 2.0, h / 2.0
        r = max(0.0, min(r, hw, hh))
        win = self._window(cx - hw, cy - hh, cx + hw, cy + hh)
        if win is None:
            return
        x, y, sl = win
        qx = np.abs(x - cx) - (hw - r)
        qy = np.abs(y - cy) - (hh - r)
        outside = np.hypot(np.maximum(qx, 0.0), np.maximum(qy, 0.0))
        inside = np.minimum(np.maximum(qx, qy), 0.0)
        self._blend(sl, outside + inside - r, color, alpha)

    def fill_ellipse(self, cx: float, cy: float, rx: float, ry: float,
                     color: Color, alpha: float = 1.0) -> None:
        """Axis-aligned ellipse centred on (cx, cy) with radii rx, ry."""
        if rx <= 0 or ry <= 0:
            return
        win = self._window(cx - rx, cy - ry, cx + rx, cy + ry)
        if win is None:
            return
        x, y, sl = win
        # First-order distance to the ellipse: f / |grad f| with f = |p / radii| - 1.
        ux, uy = (x - cx) / rx, (y - cy) / ry
        k0 = np.hypot(ux, uy)
        k1 = np.hypot(ux / rx, uy / ry)
        dist = k0 * (k0 - 1.0) / np.maximum(k1, 1e-6)
        self._blend(sl, dist, color, alpha)

    def fill_circle(self, cx: float, cy: float, r: float, color: Color, alpha: float = 1.0) -> None:
        if r <= 0:
            return
        win = self._window(cx - r, cy - r, cx + r, cy + r)
        if win is None:
            return
        x, y, sl = win
        self._blend(sl, np.hypot(x - cx, y - cy) - r, color, alpha)

    def fill_triangle(self, x0: float, y0: float, x1: float, y1: float, x2: float, y2: float,
                      color: Color, alpha: float = 1.0) -> None:
        """Triangle with vertices in any winding order."""
        self._fill_convex([(x0, y0), (x1, y1), (x2, y2)], color, alpha)

    def fill_quad(self, pts: list[tuple[float, float]], color: Color, alpha: float = 1.0) -> None:
        """Convex quadrilateral, four points in order.

        On the device this is two fillTriangle calls; here it is rasterized in one pass so the
        shared diagonal does not leave an anti-aliasing seam.
        """
        self._fill_convex(pts, color, alpha)

    def _fill_convex(self, pts: list[tuple[float, float]], color: Color, alpha: float) -> None:
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        area = sum(xs[i] * ys[i - 1] - xs[i - 1] * ys[i] for i in range(len(pts)))
        if abs(area) < 1e-9:
            return
        if area > 0:                                   # make the winding consistent
            pts = pts[::-1]
        win = self._window(min(xs), min(ys), max(xs), max(ys))
        if win is None:
            return
        x, y, sl = win
        dist = None
        for (ax, ay), (bx, by) in zip(pts, pts[1:] + pts[:1]):
            ex, ey = bx - ax, by - ay
            n = math.hypot(ex, ey)
            if n < 1e-9:
                continue
            d = ((x - ax) * ey - (y - ay) * ex) / n    # signed distance to the edge, + outside
            dist = d if dist is None else np.maximum(dist, d)
        if dist is not None:
            self._blend(sl, dist, color, alpha)

    # -- internals -------------------------------------------------------------------------

    def _window(self, x0: float, y0: float, x1: float, y1: float):
        """Pixel-centre coordinates and buffer slice for a bounding box (with 1 px AA margin)."""
        i0 = max(0, int(np.floor(x0)) - 1)
        j0 = max(0, int(np.floor(y0)) - 1)
        i1 = min(self.width, int(np.ceil(x1)) + 1)
        j1 = min(self.height, int(np.ceil(y1)) + 1)
        if i0 >= i1 or j0 >= j1:
            return None
        x = np.arange(i0, i1, dtype=np.float32)[None, :] + 0.5
        y = np.arange(j0, j1, dtype=np.float32)[:, None] + 0.5
        return x, y, (slice(j0, j1), slice(i0, i1))

    def _blend(self, sl, dist: np.ndarray, color: Color, alpha: float) -> None:
        cov = np.clip(0.5 - dist, 0.0, 1.0)
        if alpha < 1.0:
            cov = cov * alpha
        region = self.buf[sl]
        region += cov[..., None] * (np.asarray(color, np.float32) - region)
