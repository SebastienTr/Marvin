// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.List;

/**
 * Embeddings projected to two dimensions for the app's meaning map (docs/memory.md, "Seeing memory"): principal
 * component analysis by power iteration, in plain Java. Deterministic: the same vectors give the same picture, the
 * start vector is fixed and each component's sign is chosen so that its largest loading is positive. Cost: a few
 * hundred passes of {@code 2 · n · d} multiplications (a few milliseconds for a thousand facts of 1024 numbers).
 */
public final class Projection {
    static final int MAX_ITERATIONS = 300;
    static final double TOLERANCE = 1e-10;

    /**
     * @param points    each vector's two coordinates, in the input's order
     * @param explained the share of the total variance each axis holds (0 to 1)
     */
    public record Result(double[][] points, double[] explained) {
    }

    private Projection() {
    }

    /** The first two principal components' scores of these vectors (all of the same length). */
    public static Result pca2(List<float[]> vectors) {
        int n = vectors.size();
        if (n == 0) {
            return new Result(new double[0][], new double[] {0, 0});
        }
        int d = vectors.getFirst().length;
        for (float[] v : vectors) {
            if (v.length != d) {
                throw new IllegalArgumentException("vectors of different lengths");
            }
        }
        double[] mean = new double[d];
        for (float[] v : vectors) {
            for (int j = 0; j < d; j++) {
                mean[j] += v[j];
            }
        }
        for (int j = 0; j < d; j++) {
            mean[j] /= n;
        }
        double[][] x = new double[n][d];
        double total = 0;
        for (int i = 0; i < n; i++) {
            float[] v = vectors.get(i);
            for (int j = 0; j < d; j++) {
                x[i][j] = v[j] - mean[j];
                total += x[i][j] * x[i][j];
            }
        }
        double[][] points = new double[n][2];
        double[] explained = new double[2];
        if (n < 2 || total <= 1e-18) {
            return new Result(points, explained);   // one point, or all the same: the centre
        }
        double[] first = component(x, d, null);
        double[] second = component(x, d, first);
        double[][] axes = {first, second};
        for (int k = 0; k < 2; k++) {
            if (axes[k] == null) {
                continue;
            }
            double sum = 0;
            for (int i = 0; i < n; i++) {
                points[i][k] = dot(x[i], axes[k]);
                sum += points[i][k] * points[i][k];
            }
            explained[k] = Math.min(1, sum / total);
        }
        return new Result(points, explained);
    }

    /** The leading eigenvector of XᵀX (orthogonal to {@code previous} when given); {@code null} when nothing is left. */
    static double[] component(double[][] x, int d, double[] previous) {
        double[] v = new double[d];
        for (int j = 0; j < d; j++) {
            // a fixed start that is not orthogonal to any usual direction
            v[j] = 1.0 + ((j * 7919L) % 101) / 100.0;
        }
        orthogonalise(v, previous);
        if (!normalise(v)) {
            return null;
        }
        double[] xv = new double[x.length];
        for (int it = 0; it < MAX_ITERATIONS; it++) {
            for (int i = 0; i < x.length; i++) {
                xv[i] = dot(x[i], v);
            }
            double[] w = new double[d];
            for (int i = 0; i < x.length; i++) {
                double c = xv[i];
                if (c != 0) {
                    double[] row = x[i];
                    for (int j = 0; j < d; j++) {
                        w[j] += c * row[j];
                    }
                }
            }
            orthogonalise(w, previous);
            if (!normalise(w)) {
                return null;                // no variance left in this direction
            }
            double change = 0;
            for (int j = 0; j < d; j++) {
                change += (w[j] - v[j]) * (w[j] - v[j]);
            }
            v = w;
            if (change < TOLERANCE) {
                break;
            }
        }
        // deterministic sign: the largest loading is positive
        int big = 0;
        for (int j = 1; j < d; j++) {
            if (Math.abs(v[j]) > Math.abs(v[big]) + 1e-12) {
                big = j;
            }
        }
        if (v[big] < 0) {
            for (int j = 0; j < d; j++) {
                v[j] = -v[j];
            }
        }
        return v;
    }

    private static void orthogonalise(double[] v, double[] against) {
        if (against == null) {
            return;
        }
        double c = dot(v, against);
        for (int j = 0; j < v.length; j++) {
            v[j] -= c * against[j];
        }
    }

    private static boolean normalise(double[] v) {
        double norm = Math.sqrt(dot(v, v));
        if (norm < 1e-12) {
            return false;
        }
        for (int j = 0; j < v.length; j++) {
            v[j] /= norm;
        }
        return true;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int j = 0; j < a.length; j++) {
            s += a[j] * b[j];
        }
        return s;
    }
}
