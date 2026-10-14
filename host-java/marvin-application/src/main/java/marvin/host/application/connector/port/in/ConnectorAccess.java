// SPDX-License-Identifier: MIT
package marvin.host.application.connector.port.in;

import java.util.Optional;

/** What a connector's tools ask: an access token for its calls, and to report that a call was refused. */
public interface ConnectorAccess {

    /** Whether the connector is connected (its tools are offered only then). Cheap: no network. */
    boolean connected(String connectorId);

    /** A valid access token, refreshed when it is about to expire; empty when not connected or the refresh failed. */
    Optional<String> accessToken(String connectorId);

    /** The provider refused a call with this connector's token (revoked, expired): it shows as in error. */
    void refused(String connectorId, String why);
}
