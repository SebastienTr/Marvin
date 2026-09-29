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
}
