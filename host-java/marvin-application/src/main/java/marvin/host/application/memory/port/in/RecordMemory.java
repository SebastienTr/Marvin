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
     * Reads a source's records after the last one read before (its high-water mark, kept per source name) into the
     * log: at the first start its whole past, then what the live feed may have missed (a full queue, a crash).
     * Idempotent: records already fed live are not added twice, and records the owner forgot are never read again.
     * Returns how many events were added.
     */
    int catchUp(BackfillSource source);

    /** Waits until what is queued is written, at most {@code timeoutMs}; true if it was. */
    boolean flush(long timeoutMs);
}
