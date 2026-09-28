// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

/**
 * Constants of the robot protocol, version 1 (docs/protocol.md). All integers are little-endian.
 */
public final class ProtocolV1 {
    /** The two magic bytes at the start of every datagram, "MV". */
    public static final byte[] MAGIC = {'M', 'V'};
    public static final int VERSION = 1;
    /** Magic (2), version (1), type (1), sequence number (4), sender clock in microseconds (8). */
    public static final int HEADER_SIZE = 16;
    /** The host listens on this UDP port. */
    public static final int HOST_PORT = 47100;
    /** The robot listens on this UDP port. */
    public static final int DEVICE_PORT = 47101;

    /** {@code HELLO} flag: the sensor data is simulated. */
    public static final int FLAG_SIMULATED = 0x01;
    /** {@code HELLO} flag: MJPEG camera on TCP port {@link #CAMERA_PORT}. */
    public static final int FLAG_CAMERA = 0x02;
    /** {@code HELLO} flag: speaker and microphone. */
    public static final int FLAG_AUDIO = 0x04;
    public static final int CAMERA_PORT = 81;

    /** Audio in both directions: 16 kHz mono signed 16-bit little-endian PCM. */
    public static final int AUDIO_RATE = 16_000;
    /** Samples in one {@code AUDIO_IN} datagram (20 ms). */
    public static final int AUDIO_IN_SAMPLES = 320;
    /** At most this many samples in one {@code AUDIO_OUT} datagram (30 ms). */
    public static final int AUDIO_OUT_MAX_SAMPLES = 480;

    private ProtocolV1() {
    }
}
