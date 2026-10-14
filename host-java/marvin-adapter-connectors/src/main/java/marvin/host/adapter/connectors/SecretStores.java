// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import marvin.host.application.connector.port.out.SecretStore;

/** Which secret store: the macOS Keychain on a Mac, else a 0600 file; {@code memory} for the demo. */
public final class SecretStores {

    private SecretStores() {
    }

    /**
     * @param kind {@code auto} (the Keychain on macOS, else the file), {@code keychain}, {@code file} or {@code memory}
     * @param file the file of the file store
     */
    public static SecretStore choose(String kind, Path file, String osName) {
        String k = kind == null || kind.isBlank() ? "auto" : kind.strip().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "keychain" -> new KeychainSecretStore();
            case "file" -> new FileSecretStore(file);
            case "memory" -> new MemorySecretStore();
            case "auto" -> osName != null && osName.toLowerCase(Locale.ROOT).contains("mac")
                    && Files.isExecutable(Path.of(KeychainSecretStore.TOOL)) ? new KeychainSecretStore() : new FileSecretStore(file);
            default -> throw new IllegalArgumentException("marvin.connectors.secrets must be auto, keychain, file or memory");
        };
    }
}
