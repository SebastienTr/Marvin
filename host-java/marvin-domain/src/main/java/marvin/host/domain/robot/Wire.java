// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Packs and unpacks protocol v1 datagrams: the header, then the payload (docs/protocol.md). */
public final class Wire {
    private Wire() {
    }

    /** A whole datagram: header with {@code seq} (wrapped to 32 bits) and {@code tUs}, then the payload. */
    public static byte[] pack(int type, long seq, long tUs, byte[] payload) {
        ByteBuffer b = le(ProtocolV1.HEADER_SIZE + payload.length);
        b.put(ProtocolV1.MAGIC).put((byte) ProtocolV1.VERSION).put((byte) type)
                .putInt((int) seq).putLong(tUs).put(payload);
        return b.array();
    }

    public static byte[] pack(MessageType type, long seq, long tUs, byte[] payload) {
        return pack(type.code(), seq, tUs, payload);
    }

    /** The header of a datagram, or a {@link ProtocolException} for one that is not protocol v1. */
    public static Header header(byte[] datagram, int length) {
        if (length < ProtocolV1.HEADER_SIZE) {
            throw new ProtocolException("datagram shorter than the header");
        }
        if (datagram[0] != ProtocolV1.MAGIC[0] || datagram[1] != ProtocolV1.MAGIC[1]) {
            throw new ProtocolException("bad magic");
        }
        int version = datagram[2] & 0xff;
        if (version != ProtocolV1.VERSION) {
            throw new ProtocolException("unsupported protocol version " + version);
        }
        ByteBuffer b = ByteBuffer.wrap(datagram, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        return new Header(datagram[3] & 0xff, Integer.toUnsignedLong(b.getInt(4)), b.getLong(8));
    }

    public static Header header(byte[] datagram) {
        return header(datagram, datagram.length);
    }

    /** The payload of a datagram (a copy of everything after the header). */
    public static byte[] payload(byte[] datagram, int length) {
        return Arrays.copyOfRange(datagram, ProtocolV1.HEADER_SIZE, length);
    }

    public static byte[] payload(byte[] datagram) {
        return payload(datagram, datagram.length);
    }

    static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    static ByteBuffer le(byte[] payload) {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Python's {@code round()}: halves to even. */
    static long round(double v) {
        return (long) Math.rint(v);
    }

    static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
