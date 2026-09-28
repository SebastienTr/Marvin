// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.Objects;

/**
 * Where a datagram came from (and where the answer goes): an IP address and a UDP port.
 *
 * @param host the IP address, as text
 */
public record Endpoint(String host, int port) {

    public Endpoint {
        Objects.requireNonNull(host, "host");
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port " + port);
        }
    }

    /** Parses {@code ip:port} (the recording format; an IPv6 address keeps its colons). */
    public static Endpoint parse(String s) {
        int i = s.lastIndexOf(':');
        if (i < 0) {
            throw new IllegalArgumentException("not ip:port: " + s);
        }
        return new Endpoint(s.substring(0, i), Integer.parseInt(s.substring(i + 1)));
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
