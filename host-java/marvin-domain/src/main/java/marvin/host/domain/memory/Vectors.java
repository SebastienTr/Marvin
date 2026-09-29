// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

/** Vector arithmetic for the exact search used without pgvector, and for tests. */
public final class Vectors {

    private Vectors() {
    }

    /** Cosine similarity; 0 when either vector is zero or they differ in size. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
