// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts the new events into batches for extraction (docs/design.md 5.2, step 1): a conversation is a run of
 * extractable events with no gap longer than {@code gap}, at most {@code maxEvents} long.
 */
public final class Batches {

    private Batches() {
    }

    /** The conversations in {@code events} (oldest first); events that are not extractable are left out. */
    public static List<List<MemoryEvent>> conversations(List<MemoryEvent> events, Duration gap, int maxEvents) {
        List<List<MemoryEvent>> out = new ArrayList<>();
        List<MemoryEvent> current = new ArrayList<>();
        MemoryEvent last = null;
        for (MemoryEvent e : events) {
            if (!MemorySources.extractable(e)) {
                continue;
            }
            boolean split = last != null && (Duration.between(last.ts(), e.ts()).compareTo(gap) > 0
                    || current.size() >= maxEvents);
            if (split) {
                out.add(current);
                current = new ArrayList<>();
            }
            current.add(e);
            last = e;
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }
}
