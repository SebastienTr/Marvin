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
 * switches, written off the caller's thread; and the catch-up of what the other contexts kept (their past at the
 * first start, then what the live feed missed).
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

    /**
     * The in-process equivalent of an outbox: each source's high-water mark ({@code feed:<name>} in the state) moves
     * on page by page, so a catch-up can run at every start and every few minutes at no cost when nothing was missed.
     */
    @Override
    public synchronized int catchUp(BackfillSource source) {
        String key = "feed:" + source.name();
        Map<String, Object> feed = state.get(key);
        long after;
        if (feed.get("after") instanceof Number n) {
            after = n.longValue();
        } else if (!state.get("backfill:" + source.name()).isEmpty()) {
            // backfilled by an earlier version, which kept no mark: start from the source's end (reading its past
            // again could bring back what the owner forgot since)
            after = end(source);
        } else {
            after = 0;
        }
        ForgottenFeed forgotten = ForgottenFeed.read(state);
        int added = 0;
        int read = 0;
        while (true) {
            BackfillSource.Page page = source.next(after, BACKFILL_PAGE);
            List<MemoryEvent> kept = page.events().stream()
                    .filter(e -> settings.settings().collects(e.source()))
                    .filter(e -> !forgotten.covers(e))
                    .map(e -> e.withBody(Redaction.redact(e.body())))
                    .toList();
            added += events.append(kept);
            read += page.events().size();
            boolean last = page.done() || page.lastId() <= after;
            if (page.lastId() > after) {
                after = page.lastId();
            }
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("after", after);
            mark.put("at", clocks.wallSeconds());
            state.put(key, mark);
            if (last) {
                break;
            }
        }
        if (added > 0) {
            log.info("memory: " + source.name() + " caught up, " + added + " events added (" + read + " read)");
        }
        return added;
    }

    /** The last id of a source (read through, nothing appended). */
    private static long end(BackfillSource source) {
        long after = 0;
        while (true) {
            BackfillSource.Page page = source.next(after, BACKFILL_PAGE);
            if (page.done() || page.lastId() <= after) {
                return Math.max(after, page.lastId());
            }
            after = page.lastId();
        }
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
