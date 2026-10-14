// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

import java.util.Arrays;
import java.util.List;

/** Medians and rounding for the vitals context. */
final class Medians {
    private Medians() {
    }

    /** The median ({@code null} for no values; the mean of the two middle ones for an even count). */
    static Double of(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(v);
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
    }

    /** Rounded to {@code digits} decimals ({@code null} stays null). */
    static Double round(Double v, int digits) {
        if (v == null) {
            return null;
        }
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
