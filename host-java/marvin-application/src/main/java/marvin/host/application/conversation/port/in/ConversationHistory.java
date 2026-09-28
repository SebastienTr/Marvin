// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.in;

import java.time.LocalDate;
import java.util.List;

import marvin.host.domain.conversation.ConversationEntry;

/** The conversation as kept: one day of it, or a search (History panel). */
public interface ConversationHistory {

    List<ConversationEntry> day(LocalDate day);

    /** Up to {@code limit} entries said or heard containing {@code query}, newest first; none for a blank query. */
    List<ConversationEntry> search(String query, int limit);

    /** Keeps an entry (the voice calls it for each line of the Talk panel). */
    void add(ConversationEntry entry);

    /** The largest id kept so far, 0 when there is none. */
    long maxId();
}
