// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * HLK-LD2450 24 GHz radar target frames: 30 bytes, {@code AA FF 03 00}, 3 targets × (x i16 mm, y i16
 * mm, speed i16 cm/s, distance resolution u16 mm), {@code 55 CC}. x, y and speed use a sign bit (bit
 * 15 set = positive). An all-zero slot is empty. Radar frame: x to the right, y straight ahead.
 */
public final class Ld2450 {
    public static final int SIZE = 30;
    private static final byte[] HEAD = {(byte) 0xAA, (byte) 0xFF, 0x03, 0x00};
    private static final byte[] TAIL = {0x55, (byte) 0xCC};

    /** One target, in the radar's frame. */
    public record Target(int xMm, int yMm, int speedCms, int resolutionMm) {
    }

    private Ld2450() {
    }

    public static List<Target> parse(byte[] buf) {
        if (buf.length != SIZE || !startsWith(buf, HEAD) || buf[SIZE - 2] != TAIL[0] || buf[SIZE - 1] != TAIL[1]) {
            throw new ProtocolException("not an LD2450 frame");
        }
        ByteBuffer b = Wire.le(buf);
        List<Target> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            int o = 4 + 8 * i;
            int x = b.getShort(o) & 0xffff;
            int y = b.getShort(o + 2) & 0xffff;
            int s = b.getShort(o + 4) & 0xffff;
            int r = b.getShort(o + 6) & 0xffff;
            if (x == 0 && y == 0 && s == 0 && r == 0) {
                continue;
            }
            out.add(new Target(dec(x), dec(y), dec(s), r));
        }
        return out;
    }

    public static byte[] build(List<Target> targets) {
        ByteBuffer b = Wire.le(SIZE);
        b.put(HEAD);
        for (int i = 0; i < 3; i++) {
            if (i < targets.size()) {
                Target t = targets.get(i);
                b.putShort((short) enc(t.xMm())).putShort((short) enc(t.yMm())).putShort((short) enc(t.speedCms()))
                        .putShort((short) t.resolutionMm());
            } else {
                b.put(new byte[8]);
            }
        }
        b.put(TAIL);
        return b.array();
    }

    private static int dec(int v) {
        return (v & 0x8000) != 0 ? v & 0x7FFF : -(v & 0x7FFF);
    }

    private static int enc(int v) {
        return v >= 0 ? 0x8000 | Math.min(v, 0x7FFF) : Math.min(-v, 0x7FFF);
    }

    private static boolean startsWith(byte[] buf, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (buf[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
