// SPDX-License-Identifier: MIT
package marvin.host.domain.connector;

import java.util.Locale;

/** Where a connector is: not connected, connected (a token is kept), or in error (its last call or refresh failed). */
public enum ConnectorState {
    DISCONNECTED, CONNECTED, ERROR;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
