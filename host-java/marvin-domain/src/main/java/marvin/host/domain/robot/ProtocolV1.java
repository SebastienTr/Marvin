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

    private ProtocolV1() {
    }
}
