// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.util.List;

import marvin.host.domain.memory.MemoryEvent;

/** Records kept by another context before memory existed, read once into the log (conversation, presence). */
public interface BackfillSource {

    /** The source's name, e.g. {@code conversation}: a backfill is done once per name. */
    String name();

    /**
     * The next records after {@code afterId} (the source's own ids), oldest first, as events.
     *
     * @param lastId the source id of the last record read (records memory does not keep are skipped but counted)
     */
    record Page(List<MemoryEvent> events, long lastId, boolean done) {
    }

    Page next(long afterId, int limit);
}
