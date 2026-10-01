// SPDX-License-Identifier: MIT
package marvin.host.domain.vision.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import marvin.host.domain.shared.PyNumbers;

/**
 * A picture one of Marvin's cameras has just taken: a JPEG of at most {@link #MAX_SIDE} pixels a side (the size the app
 * gives an image shown in Talk). It lives in memory for as long as it is looked at, and is never stored.
 *
 * @param jpeg    the JPEG
 * @param source  {@code computer} or {@code robot}
 * @param detail  which camera, in words ("this computer's camera", "marvin-a1b2c3 at 192.168.1.40")
 * @param seconds how long taking it took
 */
public record Frame(byte[] jpeg, int width, int height, String source, String detail, double seconds) {
    /** The longest side of a picture, in pixels. */
    public static final int MAX_SIDE = 1280;
    /** The JPEG quality a larger picture is encoded at once made smaller (the app's 0.85). */
    public static final float QUALITY = 0.85f;

    public Frame {
        Objects.requireNonNull(jpeg, "jpeg");
        Objects.requireNonNull(source, "source");
        detail = detail == null ? "" : detail;
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) {
            throw new IllegalArgumentException("a picture is 1 to " + MAX_SIDE + " pixels a side, not " + width + "x" + height);
        }
    }

    /** What the app and the log show of it: never the picture. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("detail", detail);
        m.put("width", width);
        m.put("height", height);
        m.put("bytes", jpeg.length);
        m.put("seconds", PyNumbers.round(seconds, 3));
        return m;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Frame f && java.util.Arrays.equals(jpeg, f.jpeg) && width == f.width && height == f.height
                && source.equals(f.source) && detail.equals(f.detail) && seconds == f.seconds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(java.util.Arrays.hashCode(jpeg), width, height, source, detail, seconds);
    }

    @Override
    public String toString() {
        return "Frame[" + source + " " + width + "x" + height + ", " + jpeg.length + " bytes]";
    }
}
