// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;

/**
 * {@code VITALS} payload, MR60BHA2 readings: valid (u8), breath rate (u16, 0.01/min), heart rate
 * (u16, 0.01/min), breathing wave (i16, ±32767), heartbeat wave (i16, ±32767), distance (u16, mm).
 *
 * @param valid      a still person is measured
 * @param breathRate breaths per minute
 * @param heartRate  beats per minute
 * @param breathWave -1..1
 * @param heartWave  -1..1
 */
public record Vitals(boolean valid, double breathRate, double heartRate, double breathWave, double heartWave,
                     int distanceMm) {
    public static final int SIZE = 11;

    public byte[] encode() {
        ByteBuffer b = Wire.le(SIZE);
        b.put((byte) (valid ? 1 : 0))
                .putShort((short) Wire.round(breathRate * 100))
                .putShort((short) Wire.round(heartRate * 100))
                .putShort((short) Wire.round(Math.max(-1, Math.min(1, breathWave)) * 32767))
                .putShort((short) Wire.round(Math.max(-1, Math.min(1, heartWave)) * 32767))
                .putShort((short) Math.min(distanceMm, 0xFFFF));
        return b.array();
    }

    /** Decodes a payload (extra bytes are ignored); a {@link ProtocolException} if it is too short. */
    public static Vitals decode(byte[] payload) {
        if (payload.length < SIZE) {
            throw new ProtocolException("VITALS too short");
        }
        ByteBuffer b = Wire.le(payload);
        int v = b.get() & 0xff;
        int br = b.getShort() & 0xffff;
        int hr = b.getShort() & 0xffff;
        short bw = b.getShort();
        short hw = b.getShort();
        int d = b.getShort() & 0xffff;
        return new Vitals((v & 1) != 0, br / 100.0, hr / 100.0, bw / 32767.0, hw / 32767.0, d);
    }
}
