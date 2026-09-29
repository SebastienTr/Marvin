// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import marvin.host.application.memory.port.in.RecordMemory;
import marvin.host.application.memory.port.out.BackfillSource;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.MemoryStateStore;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Redaction;
import marvin.host.domain.shared.Clocks;

/**
 * Feeds the event log (docs/design.md 5.2): events from the other contexts, redacted and filtered by the owner's
 * switches, written off the caller's thread; and the one-time backfill of what the other contexts kept before.
 */
public final class MemoryLogService implements RecordMemory, AutoCloseable {
    private static final Logger log = Logger.getLogger("marvin.memory");
    static final int BACKFILL_PAGE = 500;

    private final EventLog events;
    private final MemoryStateStore state;
    private final MemorySettingsService settings;
    private final Clocks clocks;
    private final LogWriter writer;

    public MemoryLogService(EventLog events, MemoryStateStore state, MemorySettingsService settings, Clocks clocks,
                            boolean inline) {
        this.events = events;
        this.state = state;
        this.settings = settings;
        this.clocks = clocks;
        this.writer = inline ? LogWriter.inline(events::append) : LogWriter.start(events::append);
    }

    @Override
    public void record(MemoryEvent draft) {
        if (!settings.settings().collects(draft.source())) {
            return;
        }
        writer.submit(draft.withBody(Redaction.redact(draft.body())));
    }

    @Override
    public int backfill(BackfillSource source) {
        String key = "backfill:" + source.name();
        if (!state.get(key).isEmpty()) {
            return 0;
        }
        long after = 0;
        int added = 0;
        int read = 0;
        while (true) {
            BackfillSource.Page page = source.next(after, BACKFILL_PAGE);
            List<MemoryEvent> kept = page.events().stream()
                    .filter(e -> settings.settings().collects(e.source()))
                    .map(e -> e.withBody(Redaction.redact(e.body())))
                    .toList();
            added += events.append(kept);
            read += page.events().size();
            if (page.done() || page.lastId() <= after) {
                break;
            }
            after = page.lastId();
        }
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("at", clocks.wallSeconds());
        done.put("read", read);
        done.put("added", added);
        state.put(key, done);
        log.info("memory: " + source.name() + " backfilled, " + added + " events added (" + read + " read)");
        return added;
    }

    @Override
    public boolean flush(long timeoutMs) {
        return writer.flush(timeoutMs);
    }

    /** Events waiting to be written. */
    public int pending() {
        return writer.pending();
    }

    @Override
    public void close() {
        writer.close();
    }
}
