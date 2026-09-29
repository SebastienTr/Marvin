// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.util.List;

/** Text to vectors, one model for everything (docs/design.md 5.4). */
public interface Embedder {

    /** The embedding model is not there or the server does not answer; {@code fix} says what to do. */
    final class Unavailable extends RuntimeException {
        private final String fix;

        public Unavailable(String message, String fix) {
            super(message);
            this.fix = fix == null ? "" : fix;
        }

        public String fix() {
            return fix;
        }
    }

    /** One vector per text, in order. */
    List<float[]> embed(String host, String model, List<String> texts);

    /**
     * One vector of {@code dimensions} numbers per text, in order: models trained for it (Qwen3-Embedding)
     * return their first {@code dimensions} components, so a larger model fits the same tables.
     */
    default List<float[]> embed(String host, String model, List<String> texts, int dimensions) {
        return embed(host, model, texts);
    }
}
