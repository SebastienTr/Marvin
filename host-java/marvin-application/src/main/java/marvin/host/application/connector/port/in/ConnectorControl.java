// SPDX-License-Identifier: MIT
package marvin.host.application.connector.port.in;

import java.util.Map;

/**
 * The connectors for the app (docs/design.md 7.3; golden/api/get/connectors.json): where each one is, connecting one in
 * the browser, and disconnecting (the token is revoked).
 */
public interface ConnectorControl {

    /** {@code connectors} (id, name, state, account, scopes, tools, last_error) and {@code oauth_client}. */
    Map<String, Object> connectors();

    /**
     * Starts connecting: the consent page to open in the browser of this computer.
     *
     * @param redirectUri the loopback address of the callback on this host
     * @throws IllegalArgumentException no such connector
     * @throws NotReady                 the owner's OAuth client is not installed
     */
    String connect(String id, String redirectUri);

    /**
     * The provider's redirect: exchanges the code and keeps the tokens.
     *
     * @return the connector connected
     * @throws Failed an unknown or expired state, the owner refused, or the exchange failed
     */
    String callback(String state, String code, String error);

    /**
     * Revokes the connector's token and forgets it.
     *
     * @throws IllegalArgumentException no such connector
     */
    void disconnect(String id);

    /** Connecting needs something first (the OAuth client file). */
    class NotReady extends RuntimeException {
        public NotReady(String message) {
            super(message);
        }
    }

    /** Connecting failed, in words for the owner. */
    class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }
}
