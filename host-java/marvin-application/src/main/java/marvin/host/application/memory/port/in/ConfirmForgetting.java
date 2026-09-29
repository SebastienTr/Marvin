// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import marvin.host.domain.memory.Fact;

/**
 * Forgetting only after a confirmation (docs/design.md 5.3, {@code forget}): a proposal names what would go and
 * carries a short code; the owner confirms by voice (a later turn of the conversation, never the one that asked) or
 * in the app. Forgetting everything needs the code and the owner typing a phrase.
 */
public interface ConfirmForgetting {

    /** What the owner types to forget everything. */
    String EVERYTHING_PHRASE = "forget everything";

    /**
     * A pending proposal.
     *
     * @param code      what confirms it (short: it may be read back by the model)
     * @param origin    {@code voice} or {@code app}
     * @param turn      the conversation turn that asked (voice), -1 from the app
     * @param everything forget everything (then {@code facts} is empty)
     */
    record Proposal(String code, String query, List<Fact> facts, boolean everything, String origin, long turn,
                    Instant createdAt, Instant expiresAt) {
    }

    /** The result of a confirmation. */
    record Outcome(boolean done, String reason, ForgetMemory.Forgotten forgotten) {
    }

    /** Facts matching a query (current ones, best first), proposed for forgetting; empty facts: nothing matched. */
    Proposal proposeMatching(String query, String origin, long turn, RecallMemory.Audience audience);

    /** One fact and all its versions, proposed from the app. */
    Proposal proposeFact(UUID id);

    /** Everything memory holds. */
    Proposal proposeEverything();

    /**
     * Confirms a proposal. {@code turn}: the conversation turn confirming it (a voice proposal is only confirmed in a
     * later turn), -1 from the app. {@code phrase}: required for everything.
     */
    Outcome confirm(String code, long turn, String phrase);

    /** Drops a proposal without forgetting anything. */
    boolean cancel(String code);

    /** The proposals still waiting, newest first. */
    List<Proposal> pending();
}
