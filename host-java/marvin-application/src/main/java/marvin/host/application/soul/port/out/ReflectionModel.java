// SPDX-License-Identifier: MIT
package marvin.host.application.soul.port.out;

import java.util.function.BooleanSupplier;

/** The local model, for the weekly reflection (never a cloud model: it reads the week). */
public interface ReflectionModel {

    /**
     * The model's answer to a system prompt and a message. Gives way to the voice: gives up, throwing {@link Unavailable},
     * when {@code cancelled} becomes true.
     *
     * @throws Unavailable the model server cannot answer now (try again later)
     */
    String answer(String system, String user, BooleanSupplier cancelled);

    /** The model server is not there, or the voice needed the model: the reflection waits for its next chance. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
