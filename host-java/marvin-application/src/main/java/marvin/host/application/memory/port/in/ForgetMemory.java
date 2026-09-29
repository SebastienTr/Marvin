// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * Forgetting is real (docs/design.md 2.2 and 5.4): what is forgotten is deleted, with everything derived only
 * from it, and the summaries that used it are marked for rewriting. Forgetting a fact also removes it from every
 * future context.
 */
public interface ForgetMemory {

    /** What went. */
    record Forgotten(int events, int facts, int staleEpisodes) {
    }

    /** A fact and all its versions. */
    Forgotten forgetFact(UUID id);

    /** Log events (and the facts left with no other source). */
    Forgotten forgetEvents(Instant from, Instant to);

    Forgotten forgetEvent(long id);

    /**
     * A request to forget, said in the conversation: its line and the answer to it (they repeat what was forgotten)
     * are withheld like a forgotten fact's sources, and the summaries of their day blanked. {@code externalRef}: the
     * log's reference to the line that asked.
     */
    Forgotten forgetRequest(String externalRef);

    /** Everything memory holds: log, facts, episodes, profile versions. The conversation's own history is not memory's. */
    Forgotten forgetEverything();
}
