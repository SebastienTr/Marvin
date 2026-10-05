// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * {@code HEAD} payload, 10 bytes: where the robot's head looks. Mode (u8), flags (u8), yaw, goal and target
 * yaw (i16 each, 0.1 degree: 0 straight ahead, positive towards the device frame's +x), target distance (u16,
 * mm). The target fields are 0 on the wire and {@code null} here without {@link #TARGET}. A longer payload
 * keeps this prefix; the host ignores modes and flags it does not know.
 *
 * @param targetYawDeg       {@code null} without a target
 * @param targetDistanceMm   {@code null} without a target
 */
public record Head(int mode, double yawDeg, double goalDeg, Double targetYawDeg, Integer targetDistanceMm,
                   boolean tracking, boolean glancing, boolean servoOn) {
    /** Follows the nearest LD2450 target, glances around now and then when nobody is there. */
    public static final int AUTO = 1;
    /** Holds the yaw {@code HEAD_CTRL} gave, until another one or the host is lost. */
    public static final int MANUAL = 2;
    /** Straight ahead. */
    public static final int CENTER = 3;
    /** The last LD2450 frame had a target: target yaw and distance valid. */
    public static final int TARGET = 0x01;
    /** Following a target (seen in the last 3 s). */
    public static final int TRACKING = 0x02;
    /** An idle glance around. */
    public static final int GLANCING = 0x04;
    /** The servo is driven (off at boot and while the head rests). */
    public static final int SERVO_ON = 0x08;
    public static final int SIZE = 10;

    private static final Map<Integer, String> MODES = Map.of(AUTO, "auto", MANUAL, "manual", CENTER, "center");

    public byte[] encode() {
        boolean target = targetYawDeg != null;
        int flags = (target ? TARGET : 0) | (tracking ? TRACKING : 0) | (glancing ? GLANCING : 0)
                | (servoOn ? SERVO_ON : 0);
        ByteBuffer b = Wire.le(SIZE);
        b.put((byte) mode).put((byte) flags).putShort(deci(yawDeg)).putShort(deci(goalDeg))
                .putShort(target ? deci(targetYawDeg) : 0)
                .putShort((short) (target && targetDistanceMm != null ? Math.max(0, Math.min(0xffff, targetDistanceMm)) : 0));
        return b.array();
    }

    public static Head decode(byte[] payload) {
        if (payload.length < SIZE) {
            throw new ProtocolException("HEAD too short");
        }
        ByteBuffer b = Wire.le(payload);
        int mode = b.get() & 0xff;
        int flags = b.get() & 0xff;
        double yaw = b.getShort() / 10.0;
        double goal = b.getShort() / 10.0;
        double targetYaw = b.getShort() / 10.0;
        int targetDistance = b.getShort() & 0xffff;
        boolean target = (flags & TARGET) != 0;
        return new Head(mode, yaw, goal, target ? targetYaw : null, target ? targetDistance : null,
                (flags & TRACKING) != 0, (flags & GLANCING) != 0, (flags & SERVO_ON) != 0);
    }

    /** {@code auto}, {@code manual}, {@code center}, or {@code null} for a mode this host does not know. */
    public static String modeName(int mode) {
        return MODES.get(mode);
    }

    /** The head modes this host knows, by name. */
    public static Map<Integer, String> modeNames() {
        return MODES;
    }

    static short deci(double deg) {
        return (short) Math.max(-32767, Math.min(32767, Math.rint(deg * 10)));  // half to even, as Python's round()
    }
}
