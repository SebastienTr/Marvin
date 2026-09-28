// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import marvin.host.application.conversation.port.in.ConversationHistory;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.shared.LocalDays;

/** The kept conversation, by local day or by search. */
public final class ConversationService implements ConversationHistory {
    public static final int DAY_LIMIT = 1000;

    private final ConversationStore store;
    private final LocalDays days;

    public ConversationService(ConversationStore store, LocalDays days) {
        this.store = Objects.requireNonNull(store, "store");
        this.days = Objects.requireNonNull(days, "days");
    }

    @Override
    public List<ConversationEntry> day(LocalDate day) {
        double[] b = days.bounds(day);
        return store.between(b[0], b[1], DAY_LIMIT);
    }

    @Override
    public List<ConversationEntry> search(String query, int limit) {
        String q = query.strip();
        return q.isEmpty() ? List.of() : store.search(q, limit);
    }

    @Override
    public void add(ConversationEntry entry) {
        store.add(entry);
    }

    @Override
    public long maxId() {
        return store.maxId();
    }
}
