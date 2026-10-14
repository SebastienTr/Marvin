// SPDX-License-Identifier: MIT
package marvin.host.application.connector.port.out;

import java.util.Optional;

/**
 * Where OAuth tokens are kept (docs/design.md 7.3): the macOS Keychain natively, a file only its owner can read (0600)
 * as the last resort. Values never reach the database, the logs, the event log or a prompt.
 */
public interface SecretStore {

    Optional<String> get(String key);

    void put(String key, String value);

    void delete(String key);

    /** Where secrets are kept, for the app ("macOS Keychain", "a file only you can read"). */
    String where();

    /** The store failed (the Keychain refused, the file could not be written). */
    final class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }
}
