// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

/** A datagram or payload that protocol v1 does not accept. */
public class ProtocolException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
