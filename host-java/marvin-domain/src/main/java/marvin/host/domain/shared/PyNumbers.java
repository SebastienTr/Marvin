// SPDX-License-Identifier: MIT
package marvin.host.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Python's number rounding and formatting, which the app's texts and values follow: {@code round(x, n)}
 * and {@code f"{x:.nf}"} both round the exact binary value half to even, unlike {@code String.format}.
 */
public final class PyNumbers {
    private PyNumbers() {
    }

    /** {@code round(x, n)}: NaN and infinities are returned as they are. */
    public static double round(double x, int n) {
        if (!Double.isFinite(x)) {
            return x;
        }
        return new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** {@code round(x)}: to the nearest integer, halves to even. */
    public static long roundToLong(double x) {
        return (long) Math.rint(x);
    }

    /** {@code f"{x:.nf}"}. */
    public static String fixed(double x, int n) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        String s = new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toPlainString();
        return x < 0 && s.matches("-?0(\\.0*)?") ? "-" + s.replace("-", "") : s;
    }
}
