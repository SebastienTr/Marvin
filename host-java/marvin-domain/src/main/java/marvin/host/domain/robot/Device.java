// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.Objects;

/**
 * A device that said {@code HELLO}: a robot, the MR60BHA2 bridge, or a simulator. Identified by the
 * address its datagrams come from, as the Python receiver does: the MR60BHA2 kit is a device of its
 * own, beside the robot.
 */
public final class Device {
    private final Endpoint endpoint;
    private volatile Hello hello;
    private long lastSeq = -1;
    private final LinkStats stats = new LinkStats();

    public Device(Hello hello, Endpoint endpoint) {
        this.hello = Objects.requireNonNull(hello, "hello");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    /** Where it sends from, which is also where it listens. */
    public Endpoint endpoint() {
        return endpoint;
    }

    /** Its latest {@code HELLO}. */
    public Hello hello() {
        return hello;
    }

    void hello(Hello h) {
        this.hello = h;
    }

    public LinkStats stats() {
        return stats;
    }

    public String name() {
        return hello.deviceName();
    }

    /** Counts the sequence gap since the previous datagram (below one million, a wrap-safe difference). */
    void sequence(long seq) {
        if (lastSeq >= 0) {
            long gap = (seq - lastSeq - 1) & 0xFFFFFFFFL;
            if (gap < 1_000_000) {
                stats.lost(gap);
            }
        }
        lastSeq = seq;
    }

    @Override
    public String toString() {
        return name() + "@" + endpoint;
    }
}
