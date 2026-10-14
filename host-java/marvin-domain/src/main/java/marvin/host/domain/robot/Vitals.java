// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;

/**
 * {@code VITALS} payload, MR60BHA2 readings: flags (u8), breath rate (u16, 0.01/min), heart rate
 * (u16, 0.01/min), breathing wave (i16, ±32767), heartbeat wave (i16, ±32767), distance (u16, mm).
 *
 * <p>Flags: bit 0 is protocol v1's {@code valid}. The MR60BHA2 bridge (firmware/mr60_bridge, 0.2.0 on) also sets
 * bit 1, "this sender reports a signal quality", and bit 2, its quality: {@code steady}. Senders without bit 1 (the
 * simulators, older bridges) say nothing about quality; decoders that only read bit 0 are unaffected.
 *
 * @param valid           a still person is measured
 * @param breathRate      breaths per minute
 * @param heartRate       beats per minute
 * @param breathWave      -1..1
 * @param heartWave       -1..1
 * @param distanceMm      the radar's distance to the person, 0 when unknown
 * @param qualityReported the sender reports a signal quality ({@code steady} means something)
 * @param steady          the sender's signal-quality flag: a steady signal in the radar's vital-signs zone
 */
public record Vitals(boolean valid, double breathRate, double heartRate, double breathWave, double heartWave,
                     int distanceMm, boolean qualityReported, boolean steady) {
    public static final int SIZE = 11;
    static final int VALID = 0x01;
    static final int QUALITY_REPORTED = 0x02;
    static final int STEADY = 0x04;

    /** A reading without a signal quality (protocol v1 as the simulators send it). */
    public Vitals(boolean valid, double breathRate, double heartRate, double breathWave, double heartWave, int distanceMm) {
        this(valid, breathRate, heartRate, breathWave, heartWave, distanceMm, false, false);
    }

    /** The sender's quality, when it reports one: {@code null} when it does not. */
    public Boolean steadyIfReported() {
        return qualityReported ? steady : null;
    }

    public byte[] encode() {
        int flags = (valid ? VALID : 0) | (qualityReported ? QUALITY_REPORTED : 0) | (qualityReported && steady ? STEADY : 0);
        ByteBuffer b = Wire.le(SIZE);
        b.put((byte) flags)
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
        boolean reported = (v & QUALITY_REPORTED) != 0;
        return new Vitals((v & VALID) != 0, br / 100.0, hr / 100.0, bw / 32767.0, hw / 32767.0, d, reported,
                reported && (v & STEADY) != 0);
    }
}
