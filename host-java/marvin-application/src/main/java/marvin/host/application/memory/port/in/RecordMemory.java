// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import marvin.host.application.memory.port.out.BackfillSource;
import marvin.host.domain.memory.MemoryEvent;

/** Feeding the event log (docs/design.md 5.2): the other contexts' records, as they happen and once from the past. */
public interface RecordMemory {

    /**
     * Queues an event for the log (redacted, and dropped if its source is switched off). Returns at once: the
     * voice's thread never waits for the database.
     */
    void record(MemoryEvent draft);

    /**
     * Reads a source's past records into the log, once per source name (idempotent: a second call does nothing, and
     * records already fed live are not added twice). Returns how many events were added.
     */
    int backfill(BackfillSource source);

    /** Waits until what is queued is written, at most {@code timeoutMs}; true if it was. */
    boolean flush(long timeoutMs);
}
