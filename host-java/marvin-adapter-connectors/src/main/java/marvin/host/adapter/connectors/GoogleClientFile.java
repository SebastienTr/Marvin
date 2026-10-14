// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import marvin.host.domain.shared.JsonText;

/**
 * The owner's own OAuth client, as Google Cloud's console downloads it for a desktop app ({@code client_secret_...json},
 * an {@code installed} object with {@code client_id} and {@code client_secret}). Marvin never ships a client of its own.
 * For a desktop client the secret is not confidential (Google says so); it still never reaches a log.
 */
public final class GoogleClientFile {
    /** The client's id and secret. */
    public record Client(String id, String secret) {
        @Override
        public String toString() {
            return "Client[id=" + id + "]";
        }
    }

    private final Path path;

    public GoogleClientFile(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    /** The client, read each time (the owner may install it while the host runs); empty when missing or unreadable. */
    public Optional<Client> read() {
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            Object json = JsonText.parse(Files.readString(path));
            if (!(json instanceof Map<?, ?> top)) {
                return Optional.empty();
            }
            Object inner = top.get("installed") instanceof Map<?, ?> i ? i : top.get("web") instanceof Map<?, ?> w ? w : top;
            if (inner instanceof Map<?, ?> m && m.get("client_id") instanceof String id && !id.isBlank()) {
                return Optional.of(new Client(id.strip(), m.get("client_secret") instanceof String s ? s.strip() : ""));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
