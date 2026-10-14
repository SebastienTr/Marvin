// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import marvin.host.application.connector.port.out.SecretStore;

/** Secrets kept in memory only, gone at the next start: the demo's (it never touches the owner's Keychain). */
public final class MemorySecretStore implements SecretStore {
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    @Override
    public void put(String key, String value) {
        secrets.put(key, value);
    }

    @Override
    public void delete(String key) {
        secrets.remove(key);
    }

    @Override
    public String where() {
        return "memory only (the demo)";
    }
}
