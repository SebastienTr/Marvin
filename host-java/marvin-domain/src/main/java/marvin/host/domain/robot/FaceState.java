// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;

/**
 * {@code FACE_STATE} payload (17 bytes): what the robot's face needs from the presence state.
 * Flags (u8): bit 0 present, 1 seated, 2 head valid, 3 position valid, 4 distance valid, 5 heart
 * rate valid; head x, y, z and position x, y, z (i16, mm, device frame); distance (u16, mm); heart
 * rate (u16, 0.01/min). Fields without their valid bit are sent as 0.
 *
 * @param head      mm, device frame, or {@code null}
 * @param position  mm, device frame, or {@code null}
 * @param distanceM metres, or {@code null}
 * @param heartRate per minute, or {@code null}
 */
public record FaceState(boolean present, boolean seated, double[] head, double[] position, Double distanceM,
                        Double heartRate) {
    public static final int SIZE = 17;

    public static final FaceState NOBODY = new FaceState(false, false, null, null, null, null);

    public byte[] encode() {
        int flags = (present ? 1 : 0) | (seated ? 2 : 0) | (head != null ? 4 : 0) | (position != null ? 8 : 0)
                | (distanceM != null ? 16 : 0) | (heartRate != null ? 32 : 0);
        ByteBuffer b = Wire.le(SIZE);
        b.put((byte) flags);
        putMm(b, head);
        putMm(b, position);
        b.putShort((short) (distanceM == null ? 0 : Wire.clamp(Wire.round(distanceM * 1000), 0, 0xFFFF)));
        b.putShort((short) (heartRate == null ? 0 : Wire.clamp(Wire.round(heartRate * 100), 0, 0xFFFF)));
        return b.array();
    }

    private static void putMm(ByteBuffer b, double[] p) {
        for (int i = 0; i < 3; i++) {
            b.putShort((short) (p == null ? 0 : Wire.clamp(Wire.round(p[i]), -32767, 32767)));
        }
    }

    public static FaceState decode(byte[] payload) {
        if (payload.length < SIZE) {
            throw new ProtocolException("FACE_STATE too short");
        }
        ByteBuffer b = Wire.le(payload);
        int flags = b.get() & 0xff;
        double[] head = {b.getShort(), b.getShort(), b.getShort()};
        double[] pos = {b.getShort(), b.getShort(), b.getShort()};
        int dist = b.getShort() & 0xffff;
        int hr = b.getShort() & 0xffff;
        return new FaceState((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0 ? head : null,
                (flags & 8) != 0 ? pos : null, (flags & 16) != 0 ? dist / 1000.0 : null,
                (flags & 32) != 0 ? hr / 100.0 : null);
    }
}
