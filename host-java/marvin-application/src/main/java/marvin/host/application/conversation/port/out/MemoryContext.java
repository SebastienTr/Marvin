// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import marvin.host.domain.conversation.ContextAssembler;

/**
 * What the conversation asks of memory (docs/design.md 5.3): the profile for the system prompt, the scored
 * candidates of a question's memory sections, and the memory tools. The conversation never sees memory's own types:
 * this port speaks in prompt lines and JSON-like values.
 */
public interface MemoryContext {

    /**
     * The profile block of the system prompt.
     *
     * @param version changes only when the text does (nightly rewrite, the owner's edit); 0: no profile
     */
    record Profile(long version, String text) {
        public static final Profile NONE = new Profile(0, "");
    }

    /**
     * Who may hear the answer.
     *
     * @param othersPresent the brain sees more than one person (no sensitive facts)
     * @param cloudModel    the prompt leaves the house (no sensitive facts at all)
     */
    record Audience(boolean othersPresent, boolean cloudModel) {
    }

    /**
     * The candidates of a question's memory sections, unscored by the conversation (the assembler cuts them to their
     * budgets by score).
     *
     * @param timings seconds: {@code embed}, {@code search}
     * @param problem why there are no facts, or {@code ""}
     */
    record Recollection(List<ContextAssembler.Item> gist, List<ContextAssembler.Item> facts, Map<String, Double> timings,
                        String problem) {
        public static final Recollection EMPTY = new Recollection(List.of(), List.of(), Map.of(), "");
    }

    /** A tool's answer to the model: JSON-like values, or an error the model can talk about. */
    record ToolAnswer(Object value, String error) {
        public static ToolAnswer ok(Object value) {
            return new ToolAnswer(value, null);
        }

        public static ToolAnswer failed(String error) {
            return new ToolAnswer(null, error);
        }
    }

    /** Whether memory is there at all (the tools and sections are offered only then). */
    boolean available();

    Profile profile();

    /** Never throws; a failure gives {@link Recollection#EMPTY} with a problem. */
    Recollection recollect(String question, Audience audience);

    /** These items' facts were sent in a prompt (keys as given in {@link Recollection#facts()}). */
    void used(Collection<String> keys);

    /** {@code remember(statement)}: the owner's fact, written at once. */
    ToolAnswer remember(String statement);

    /** {@code recall(query, period)}. */
    ToolAnswer recall(String query, String period, Audience audience);

    /**
     * {@code forget(query, confirm)}: without {@code confirm}, the matches and a code; with it, forgets them if the
     * code was given in an earlier {@code turn}.
     */
    ToolAnswer forget(String query, String confirm, long turn, Audience audience);

    /** No memory: nothing in the prompt, no tools. */
    MemoryContext NONE = new MemoryContext() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Profile profile() {
            return Profile.NONE;
        }

        @Override
        public Recollection recollect(String question, Audience audience) {
            return Recollection.EMPTY;
        }

        @Override
        public void used(Collection<String> keys) {
        }

        @Override
        public ToolAnswer remember(String statement) {
            return ToolAnswer.failed("memory is not available");
        }

        @Override
        public ToolAnswer recall(String query, String period, Audience audience) {
            return ToolAnswer.failed("memory is not available");
        }

        @Override
        public ToolAnswer forget(String query, String confirm, long turn, Audience audience) {
            return ToolAnswer.failed("memory is not available");
        }
    };
}
