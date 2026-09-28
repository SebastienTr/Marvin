// SPDX-License-Identifier: MIT
package marvin.host.domain.face;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eye geometry for one expression (face.py {@code FaceParams}): the table the firmware copies. Sizes in
 * pixels; lid values are fractions of the visible eye height; {@code lidTilt > 0} lowers the upper lid
 * on the outer side of each eye.
 */
public record FaceParams(double width, double height, double radius, double spacing, double dy, double open,
                         double lidTop, double lidTilt, double lidBottom, double brightness, double warmth) {

    public static final FaceParams DEFAULT = new FaceParams(64, 82, 22, 104, 0, 1, 0, 0, 0, 1, 0.1);

    /** The expressions, by name, in face.py's order. */
    public static final Map<String, FaceParams> EXPRESSIONS;

    static {
        Map<String, FaceParams> m = new LinkedHashMap<>();
        m.put("neutral", DEFAULT);
        m.put("awake", new FaceParams(66, 88, 24, 106, 0, 1, 0, 0, 0, 1, 0.1));
        m.put("surprised", new FaceParams(72, 94, 32, 110, -4, 1, 0, 0, 0, 1, 0.1));
        m.put("attentive", new FaceParams(60, 88, 20, 104, 0, 1, 0, 0, 0, 1, 0.1));
        m.put("content", new FaceParams(68, 76, 26, 106, 0, 1, 0, 0, 0.36, 1, 0.2));
        m.put("calm", new FaceParams(68, 68, 22, 104, 2, 1, 0, 0, 0, 1, 0.15));
        m.put("concerned", new FaceParams(64, 80, 16, 104, 0, 1, 0.40, 0.40, 0, 1, 0.2));
        m.put("sleepy", new FaceParams(66, 74, 22, 104, 4, 1, 0.55, 0, 0, 0.9, 0.45));
        m.put("asleep", new FaceParams(60, 74, 22, 104, 14, 0, 0, 0, 0, 0.7, 0.9));
        EXPRESSIONS = java.util.Collections.unmodifiableMap(m);
    }

    /** Index of {@code open} in {@link #toArray()}. */
    static final int OPEN = 5;

    public double[] toArray() {
        return new double[] {width, height, radius, spacing, dy, open, lidTop, lidTilt, lidBottom, brightness, warmth};
    }

    public static FaceParams fromArray(double[] a) {
        return new FaceParams(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10]);
    }

    public FaceParams withBrightness(double b) {
        return new FaceParams(width, height, radius, spacing, dy, open, lidTop, lidTilt, lidBottom, b, warmth);
    }

    /** The expression's name, if it is one of {@link #EXPRESSIONS}. */
    public static FaceParams expression(String name) {
        FaceParams p = EXPRESSIONS.get(name);
        if (p == null) {
            throw new IllegalArgumentException("unknown expression " + name);
        }
        return p;
    }
}
