// SPDX-License-Identifier: MIT
package marvin.host.domain.face;

/**
 * A float RGB frame buffer with anti-aliased primitives (raster.py {@code Canvas}). Every step is done
 * in float32, in the order numpy does it, so the frames match the Python renderer pixel for pixel.
 *
 * <p>Coordinates are floats in pixels, the origin at the top-left corner of the top-left pixel; the
 * centre of pixel (i, j) is (i + 0.5, j + 0.5). Colours are RGB in 0..255. Only the bounding box of
 * each primitive is touched.
 */
public final class Canvas {
    private final int width;
    private final int height;
    private final float[] buf;              // row-major, 3 floats per pixel

    public Canvas(int width, int height, double[] background) {
        this.width = width;
        this.height = height;
        this.buf = new float[width * height * 3];
        fill(background);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void fill(double[] color) {
        float r = (float) color[0];
        float g = (float) color[1];
        float b = (float) color[2];
        for (int i = 0; i < buf.length; i += 3) {
            buf[i] = r;
            buf[i + 1] = g;
            buf[i + 2] = b;
        }
    }

    /** The frame as RGB888 bytes, row-major (numpy {@code clip(buf + 0.5, 0, 255).astype(uint8)}). */
    public byte[] toRgb888() {
        return rows(0, height);
    }

    /** Rows {@code [from, to)} as RGB888 bytes. */
    public byte[] rows(int from, int to) {
        byte[] out = new byte[(to - from) * width * 3];
        int o = 0;
        for (int i = from * width * 3; i < to * width * 3; i++) {
            float v = buf[i] + 0.5f;
            v = v < 0f ? 0f : (v > 255f ? 255f : v);
            out[o++] = (byte) (int) v;
        }
        return out;
    }

    // ------------------------------------------------------------------ primitives

    /** Rounded rectangle centred on (cx, cy), size w x h, corner radius r (clamped to fit). */
    public void fillRoundRect(double cx, double cy, double w, double h, double r, double[] color, double alpha) {
        double hw = w / 2.0;
        double hh = h / 2.0;
        r = Math.max(0.0, Math.min(r, Math.min(hw, hh)));
        int[] win = window(cx - hw, cy - hh, cx + hw, cy + hh);
        if (win == null) {
            return;
        }
        float fcx = (float) cx;
        float fcy = (float) cy;
        float ax = (float) (hw - r);
        float ay = (float) (hh - r);
        float fr = (float) r;
        Distance d = (x, y) -> {
            float qx = Math.abs(x - fcx) - ax;
            float qy = Math.abs(y - fcy) - ay;
            float outside = hypot(Math.max(qx, 0f), Math.max(qy, 0f));
            float inside = Math.min(Math.max(qx, qy), 0f);
            return outside + inside - fr;
        };
        blend(win, d, color, alpha);
    }

    /** Axis-aligned ellipse centred on (cx, cy) with radii rx, ry. */
    public void fillEllipse(double cx, double cy, double rx, double ry, double[] color, double alpha) {
        if (rx <= 0 || ry <= 0) {
            return;
        }
        int[] win = window(cx - rx, cy - ry, cx + rx, cy + ry);
        if (win == null) {
            return;
        }
        float fcx = (float) cx;
        float fcy = (float) cy;
        float frx = (float) rx;
        float fry = (float) ry;
        float eps = (float) 1e-6;
        Distance d = (x, y) -> {
            float ux = (x - fcx) / frx;
            float uy = (y - fcy) / fry;
            float k0 = hypot(ux, uy);
            float k1 = hypot(ux / frx, uy / fry);
            return k0 * (k0 - 1.0f) / Math.max(k1, eps);
        };
        blend(win, d, color, alpha);
    }

    public void fillCircle(double cx, double cy, double r, double[] color, double alpha) {
        if (r <= 0) {
            return;
        }
        int[] win = window(cx - r, cy - r, cx + r, cy + r);
        if (win == null) {
            return;
        }
        float fcx = (float) cx;
        float fcy = (float) cy;
        float fr = (float) r;
        blend(win, (x, y) -> hypot(x - fcx, y - fcy) - fr, color, alpha);
    }

    /** Triangle with vertices in any winding order. */
    public void fillTriangle(double x0, double y0, double x1, double y1, double x2, double y2, double[] color,
                             double alpha) {
        fillConvex(new double[][] {{x0, y0}, {x1, y1}, {x2, y2}}, color, alpha);
    }

    /** Convex polygon, points in order (a quadrilateral is rasterized in one pass: no seam). */
    public void fillConvex(double[][] pts, double[] color, double alpha) {
        int n = pts.length;
        double area = 0;
        for (int i = 0; i < n; i++) {
            double[] p = pts[i];
            double[] q = pts[(i - 1 + n) % n];
            area += p[0] * q[1] - q[0] * p[1];
        }
        if (Math.abs(area) < 1e-9) {
            return;
        }
        double[][] ps = pts;
        if (area > 0) {                                    // make the winding consistent
            ps = new double[n][];
            for (int i = 0; i < n; i++) {
                ps[i] = pts[n - 1 - i];
            }
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] p : ps) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        int[] win = window(minX, minY, maxX, maxY);
        if (win == null) {
            return;
        }
        int edges = 0;
        float[][] e = new float[n][];
        for (int i = 0; i < n; i++) {
            double[] a = ps[i];
            double[] b = ps[(i + 1) % n];
            double ex = b[0] - a[0];
            double ey = b[1] - a[1];
            double len = Math.hypot(ex, ey);
            if (len < 1e-9) {
                continue;
            }
            e[edges++] = new float[] {(float) a[0], (float) a[1], (float) ex, (float) ey, (float) len};
        }
        if (edges == 0) {
            return;
        }
        int m = edges;
        Distance d = (x, y) -> {
            float dist = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < m; i++) {
                float[] k = e[i];
                float v = ((x - k[0]) * k[3] - (y - k[1]) * k[2]) / k[4];     // signed distance, + outside
                dist = i == 0 ? v : Math.max(dist, v);
            }
            return dist;
        };
        blend(win, d, color, alpha);
    }

    // ------------------------------------------------------------------ internals

    @FunctionalInterface
    private interface Distance {
        float at(float x, float y);
    }

    /** {i0, j0, i1, j1}: the pixel window of a bounding box, with a 1 px anti-aliasing margin. */
    private int[] window(double x0, double y0, double x1, double y1) {
        int i0 = Math.max(0, (int) Math.floor(x0) - 1);
        int j0 = Math.max(0, (int) Math.floor(y0) - 1);
        int i1 = Math.min(width, (int) Math.ceil(x1) + 1);
        int j1 = Math.min(height, (int) Math.ceil(y1) + 1);
        return i0 >= i1 || j0 >= j1 ? null : new int[] {i0, j0, i1, j1};
    }

    private void blend(int[] win, Distance distance, double[] color, double alpha) {
        float r = (float) color[0];
        float g = (float) color[1];
        float b = (float) color[2];
        float a = (float) alpha;
        boolean partial = alpha < 1.0;
        for (int j = win[1]; j < win[3]; j++) {
            float y = (float) j + 0.5f;
            for (int i = win[0]; i < win[2]; i++) {
                float x = (float) i + 0.5f;
                float cov = 0.5f - distance.at(x, y);
                cov = cov < 0f ? 0f : (cov > 1f ? 1f : cov);
                if (partial) {
                    cov = cov * a;
                }
                int p = (j * width + i) * 3;
                buf[p] = buf[p] + cov * (r - buf[p]);
                buf[p + 1] = buf[p + 1] + cov * (g - buf[p + 1]);
                buf[p + 2] = buf[p + 2] + cov * (b - buf[p + 2]);
            }
        }
    }

    /** float32 hypot as C's {@code hypotf} computes it: in double, rounded once. */
    static float hypot(float x, float y) {
        double dx = x;
        double dy = y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
