// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import marvin.host.application.conversation.port.in.ConversationHistory;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.memory.port.in.RecordMemory;
import marvin.host.application.memory.port.out.BackfillSource;
import marvin.host.application.presence.port.in.PresenceHistory;
import marvin.host.application.presence.port.out.PresenceHistoryListener;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.presence.history.StoredEvent;

/**
 * Where memory's event log is fed from (docs/design.md 5.2): the conversation as it is kept, the presence history as
 * it is stored, and both once from the past. Only ports meet here: the conversation and presence contexts know nothing
 * of memory, memory nothing of their types; {@link EventFeeds} turns plain values into log events.
 */
final class MemoryFeeds {

    private MemoryFeeds() {
    }

    /** The conversation store, which also hands every entry it keeps to memory (off the voice's thread). */
    static final class RememberedConversation implements ConversationStore {
        private final ConversationStore store;
        private final RecordMemory memory;

        RememberedConversation(ConversationStore store, RecordMemory memory) {
            this.store = store;
            this.memory = memory;
        }

        @Override
        public void add(ConversationEntry e) {
            store.add(e);
            EventFeeds.conversation(e.id(), e.t(), e.kind(), e.text(), e.data()).ifPresent(memory::record);
        }

        @Override
        public List<ConversationEntry> between(double start, double end, int limit) {
            return store.between(start, end, limit);
        }

        @Override
        public List<ConversationEntry> search(String query, int limit) {
            return store.search(query, limit);
        }

        @Override
        public List<ConversationEntry> after(long afterId, int limit) {
            return store.after(afterId, limit);
        }

        @Override
        public long maxId() {
            return store.maxId();
        }
    }

    /** Every stored brain event goes to memory too ({@code memory} is asked for when the first event comes). */
    static PresenceHistoryListener presence(Supplier<RecordMemory> memory) {
        return e -> EventFeeds.presence(e.id(), e.ts(), e.kind(), e.text(), e.data()).ifPresent(ev -> memory.get().record(ev));
    }

    /** The conversation kept before memory existed. */
    static BackfillSource conversation(ConversationHistory history) {
        return new BackfillSource() {
            @Override
            public String name() {
                return "conversation";
            }

            @Override
            public Page next(long afterId, int limit) {
                List<ConversationEntry> page = history.after(afterId, limit);
                List<MemoryEvent> out = new ArrayList<>();
                page.forEach(e -> EventFeeds.conversation(e.id(), e.t(), e.kind(), e.text(), e.data()).ifPresent(out::add));
                return new Page(out, page.isEmpty() ? afterId : page.getLast().id(), page.size() < limit);
            }
        };
    }

    /** The presence history kept before memory existed. */
    static BackfillSource presence(PresenceHistory history) {
        return new BackfillSource() {
            @Override
            public String name() {
                return "presence";
            }

            @Override
            public Page next(long afterId, int limit) {
                List<StoredEvent> page = history.after(afterId, limit);
                List<MemoryEvent> out = new ArrayList<>();
                page.forEach(e -> EventFeeds.presence(e.id(), e.ts(), e.kind(), e.text(), e.data()).ifPresent(out::add));
                return new Page(out, page.isEmpty() ? afterId : page.getLast().id(), page.size() < limit);
            }
        };
    }
}
