// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * LDROBOT lidar packets (LD19 family: D500 / STL-19P and D800 / STL-27L). A packet is 47 bytes,
 * little-endian: {@code 0x54, 0x2C}, speed u16 (deg/s), start angle u16 (0.01°), 12 × (distance u16
 * mm, intensity u8), end angle u16 (0.01°), timestamp u16 (ms), CRC-8 (polynomial 0x4D) over the
 * first 46 bytes. Angles are clockwise seen from above.
 */
public final class Ldrobot {
    public static final int HEADER = 0x54;
    public static final int VER_LEN = 0x2C;
    public static final int POINTS = 12;
    public static final int SIZE = 47;

    private static final int[] CRC_TABLE = crcTable();

    /**
     * One parsed packet.
     *
     * @param anglesDeg    12 angles, degrees in [0, 360), clockwise
     * @param distancesMm  12 distances, 0 = no return
     * @param intensities  12 values 0..255
     * @param timestampMs  wraps at 30 000
     */
    public record Packet(int speedDps, double[] anglesDeg, float[] distancesMm, int[] intensities, int timestampMs) {
    }

    private Ldrobot() {
    }

    private static int[] crcTable() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 0x80) != 0 ? ((c << 1) ^ 0x4D) & 0xff : (c << 1) & 0xff;
            }
            t[i] = c;
        }
        return t;
    }

    public static int crc8(byte[] data, int from, int to) {
        int c = 0;
        for (int i = from; i < to; i++) {
            c = CRC_TABLE[(c ^ data[i]) & 0xff];
        }
        return c;
    }

    /** A copy of the CRC table, for tests. */
    public static int[] crcTableCopy() {
        return CRC_TABLE.clone();
    }

    /** Parses one 47-byte packet at {@code off}; a {@link ProtocolException} for a bad header or CRC. */
    public static Packet parse(byte[] buf, int off, int len) {
        if (len != SIZE || (buf[off] & 0xff) != HEADER || (buf[off + 1] & 0xff) != VER_LEN) {
            throw new ProtocolException("not an LDROBOT packet");
        }
        if (crc8(buf, off, off + SIZE - 1) != (buf[off + SIZE - 1] & 0xff)) {
            throw new ProtocolException("CRC mismatch");
        }
        ByteBuffer b = Wire.le(buf);
        int speed = b.getShort(off + 2) & 0xffff;
        double start = (b.getShort(off + 4) & 0xffff) / 100.0;
        float[] dist = new float[POINTS];
        int[] inten = new int[POINTS];
        for (int i = 0; i < POINTS; i++) {
            dist[i] = b.getShort(off + 6 + 3 * i) & 0xffff;
            inten[i] = buf[off + 8 + 3 * i] & 0xff;
        }
        double end = (b.getShort(off + 6 + 3 * POINTS) & 0xffff) / 100.0;
        int ts = b.getShort(off + 8 + 3 * POINTS) & 0xffff;
        double span = pyMod(end - start, 360.0);
        double[] angles = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            angles[i] = pyMod(start + span * i / (POINTS - 1), 360.0);
        }
        return new Packet(speed, angles, dist, inten, ts);
    }

    public static Packet parse(byte[] packet) {
        return parse(packet, 0, packet.length);
    }

    /** Builds a packet (the simulator's and the tests' way), CRC included. */
    public static byte[] build(int speedDps, double startDeg, double endDeg, int[] distancesMm, int[] intensities,
                               int timestampMs) {
        ByteBuffer b = Wire.le(SIZE);
        b.put((byte) HEADER).put((byte) VER_LEN).putShort((short) speedDps)
                .putShort((short) Math.floorMod(Wire.round(startDeg * 100), 36000));
        for (int i = 0; i < POINTS; i++) {
            b.putShort((short) distancesMm[i]).put((byte) intensities[i]);
        }
        b.putShort((short) Math.floorMod(Wire.round(endDeg * 100), 36000)).putShort((short) Math.floorMod(timestampMs, 30000));
        byte[] out = b.array();
        out[SIZE - 1] = (byte) crc8(out, 0, SIZE - 1);
        return out;
    }

    /** The offsets of the whole 47-byte packets in a {@code LIDAR} payload, after its model byte at {@code from}. */
    public static List<Integer> split(int from, int length) {
        List<Integer> out = new ArrayList<>((length - from) / SIZE);
        for (int i = from; i + SIZE <= length; i += SIZE) {
            out.add(i);
        }
        return out;
    }

    /** Python's float modulo: the result has the sign of the divisor. */
    static double pyMod(double a, double b) {
        double r = a % b;
        if (r != 0 && (r < 0) != (b < 0)) {
            r += b;
        }
        return r == 0 ? Math.copySign(0.0, b) : r;
    }
}
