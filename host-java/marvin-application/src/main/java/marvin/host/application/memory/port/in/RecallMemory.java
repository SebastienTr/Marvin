// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import marvin.host.domain.memory.BlockVersion;

/**
 * Memory for the conversation (docs/design.md 5.3): the profile of the system prompt, the scored candidates of the
 * question's memory sections, and the deep dive of the {@code recall} tool. What the owner forgot never comes back
 * from here: forgotten facts are deleted, archived ones are never retrieved automatically.
 */
public interface RecallMemory {

    /**
     * Who may hear the answer, and where the prompt goes.
     *
     * @param othersPresent the brain sees more than one person: no {@code sensitive} facts
     * @param cloudModel    the prompt leaves the house: no {@code sensitive} facts at all
     */
    record Audience(boolean othersPresent, boolean cloudModel) {
        public static final Audience OWNER = new Audience(false, false);

        public boolean sensitiveAllowed() {
            return !othersPresent && !cloudModel;
        }
    }

    /**
     * A candidate line of a memory section.
     *
     * @param key    a fact's id, or {@code episode:<day>:<n>} for a sentence of a summary
     * @param detail what the reply inspector shows (similarity, relevance, recency, importance, dates)
     */
    record Line(String key, String text, double score, Map<String, Object> detail) {
    }

    /**
     * The candidates of one question.
     *
     * @param facts        the facts above the relevance floor, best first (at most the 30 nearest were scored)
     * @param gist         today's and yesterday's summaries, sentence by sentence
     * @param embedSeconds computing the question's embedding
     * @param searchSeconds finding and scoring the facts
     * @param problem      why there are no facts ({@code ""} when there was no problem): the embedding model is missing ...
     */
    record Recollection(List<Line> facts, List<Line> gist, double embedSeconds, double searchSeconds, String problem) {
        public static final Recollection EMPTY = new Recollection(List.of(), List.of(), 0, 0, "");
    }

    /** The active profile, from a cache (no database read on the voice's path). */
    Optional<BlockVersion> profile();

    /** The candidates for a question. Never throws: a failure gives no facts and says why in {@code problem}. */
    Recollection recollect(String question, Audience audience);

    /** These facts went into a prompt ({@code last_used_at}, {@code use_count}); written off the caller's thread. */
    void used(Collection<UUID> facts);

    /**
     * The {@code recall} tool: facts (past and archived ones too, with their validity), summaries and what was said,
     * in a short dated list with sources, as JSON-like values for the model.
     *
     * @param period one of {@code any}, {@code today}, {@code yesterday}, {@code this_week}, {@code last_week},
     *               {@code this_month}, {@code last_month}, {@code this_year}
     */
    Map<String, Object> recall(String query, String period, Audience audience);
}
