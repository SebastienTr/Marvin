// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * {@code HELLO} payload: MAC address (6), board id (u8), flags (u8), Wi-Fi RSSI dBm (i8), uptime ms
 * (u32), firmware version (u8 length + UTF-8).
 *
 * @param deviceId the 6-byte Wi-Fi MAC address
 * @param uptimeMs unsigned 32 bits
 */
public record Hello(byte[] deviceId, int board, int flags, int rssi, long uptimeMs, String firmware) {
    private static final int FIXED = 13;

    public Hello {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(firmware, "firmware");
        if (deviceId.length != 6) {
            throw new IllegalArgumentException("a device id is 6 bytes");
        }
        deviceId = deviceId.clone();
    }

    @Override
    public byte[] deviceId() {
        return deviceId.clone();
    }

    public byte[] encode() {
        byte[] fw = firmware.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(fw.length, 255);
        ByteBuffer b = Wire.le(FIXED + 1 + n);
        b.put(deviceId).put((byte) board).put((byte) flags).put((byte) rssi).putInt((int) uptimeMs)
                .put((byte) n).put(fw, 0, n);
        return b.array();
    }

    /** Decodes a payload; a {@link ProtocolException} if it is too short. A short firmware string is cut. */
    public static Hello decode(byte[] payload) {
        if (payload.length < FIXED + 1) {
            throw new ProtocolException("HELLO too short");
        }
        ByteBuffer b = Wire.le(payload);
        byte[] dev = new byte[6];
        b.get(dev);
        int board = b.get() & 0xff;
        int flags = b.get() & 0xff;
        int rssi = b.get();
        long uptime = Integer.toUnsignedLong(b.getInt());
        int n = b.get() & 0xff;
        int end = Math.min(payload.length, FIXED + 1 + n);
        String fw = new String(Arrays.copyOfRange(payload, FIXED + 1, end), StandardCharsets.UTF_8);
        return new Hello(dev, board, flags, rssi, uptime, fw);
    }

    public boolean simulated() {
        return (flags & ProtocolV1.FLAG_SIMULATED) != 0;
    }

    public boolean hasCamera() {
        return (flags & ProtocolV1.FLAG_CAMERA) != 0;
    }

    public boolean hasAudio() {
        return (flags & ProtocolV1.FLAG_AUDIO) != 0;
    }

    public boolean hasScreen() {
        return Board.hasScreen(board);
    }

    /** {@code marvin-} and the last three bytes of the MAC address in hex, as the robot names itself. */
    public String deviceName() {
        return "marvin-" + HexFormat.of().formatHex(deviceId, 3, 6);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Hello h && Arrays.equals(deviceId, h.deviceId) && board == h.board && flags == h.flags
                && rssi == h.rssi && uptimeMs == h.uptimeMs && firmware.equals(h.firmware);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(deviceId), board, flags, rssi, uptimeMs, firmware);
    }

    @Override
    public String toString() {
        return "Hello[" + deviceName() + ", board " + board + ", flags " + flags + ", rssi " + rssi + ", uptime "
                + uptimeMs + " ms, firmware " + firmware + "]";
    }
}
