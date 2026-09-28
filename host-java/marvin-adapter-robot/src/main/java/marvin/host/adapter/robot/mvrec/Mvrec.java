// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot.mvrec;

/**
 * Format constants, version 1 (little-endian; the whole file may be gzip-compressed, detected on read):
 *
 * <pre>
 * header  "MVREC" (5 bytes), u8 version = 1, u32 n, then n bytes of UTF-8 JSON (free-form meta)
 * record  u8 kind, u64 t_us, u16 n, then n bytes of body, repeated to the end of the file
 *         t_us: host monotonic clock, microseconds since the recording started
 *   kind 1  ADDRESS   u16 id + UTF-8 "ip:port": the sender behind id (before its first datagram)
 *   kind 2  DATAGRAM  u16 id + the datagram exactly as received
 *   kind 3  DEVICE    UTF-8 JSON describing a robot, when its HELLO is first handled:
 *                     {"address", "device_name", "board", "firmware", "simulated"}
 *   other kinds are skipped
 * </pre>
 *
 * A file cut short is read up to its last complete record.
 */
public final class Mvrec {
    public static final byte[] MAGIC = {'M', 'V', 'R', 'E', 'C'};
    public static final int VERSION = 1;
    public static final int HEAD_SIZE = 10;
    public static final int RECORD_HEAD_SIZE = 11;
    public static final int ADDRESS = 1;
    public static final int DATAGRAM = 2;
    public static final int DEVICE = 3;
    public static final int MAX_META = 1 << 20;
    /** The writer flushes at least this often, so a reader sees everything up to about a second ago. */
    public static final long FLUSH_NANOS = 1_000_000_000L;

    private Mvrec() {
    }
}
