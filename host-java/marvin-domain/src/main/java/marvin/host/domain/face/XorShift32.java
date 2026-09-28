// SPDX-License-Identifier: MIT
package marvin.host.domain.face;

/** The tiny deterministic PRNG shared with the firmware (Marsaglia xorshift32, face.py). */
public final class XorShift32 {
    private long state;

    public XorShift32(long seed) {
        long s = (seed * 2654435761L + 0x9E3779B9L) & 0xFFFFFFFFL;
        this.state = s != 0 ? s : 0x6D2B79F5L;
    }

    public long nextU32() {
        long x = state;
        x ^= (x << 13) & 0xFFFFFFFFL;
        x ^= x >>> 17;
        x ^= (x << 5) & 0xFFFFFFFFL;
        state = x;
        return x;
    }

    public double uniform(double lo, double hi) {
        return lo + (hi - lo) * (nextU32() / 4294967296.0);
    }
}
