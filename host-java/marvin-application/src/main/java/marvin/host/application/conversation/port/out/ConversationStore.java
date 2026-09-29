// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;

import marvin.host.domain.conversation.ConversationEntry;

/** Where the conversation is kept. */
public interface ConversationStore {

    /** Keeps an entry; the same id again replaces it. */
    void add(ConversationEntry entry);

    /** Entries with {@code start <= t < end}, oldest first (the last {@code limit} of them). */
    List<ConversationEntry> between(double start, double end, int limit);

    /**
     * What was said (heard and replies) containing {@code query}, case-insensitive for ASCII letters,
     * newest first.
     */
    List<ConversationEntry> search(String query, int limit);

    /** Entries with {@code id > afterId}, by id, at most {@code limit} (reading the whole history in pages). */
    List<ConversationEntry> after(long afterId, int limit);

    /** The largest id so far, 0 when empty. */
    long maxId();
}
