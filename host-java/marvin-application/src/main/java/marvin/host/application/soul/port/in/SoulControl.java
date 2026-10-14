// SPDX-License-Identifier: MIT
package marvin.host.application.soul.port.in;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import marvin.host.domain.soul.Guardrails;
import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulProposal;
import marvin.host.domain.soul.SoulVersion;

/**
 * The soul for the app (docs/design.md 8; golden/api/get/soul.json): read it, edit it, decide on the reflection's
 * proposal, go back to a version.
 */
public interface SoulControl {

    /** The soul as the app shows it: core, learned, limits, the proposal awaiting the owner (or null), versions. */
    Map<String, Object> view();

    /**
     * The owner's core.
     *
     * @throws IllegalArgumentException empty or too long
     */
    SoulVersion setCore(String text);

    /**
     * The owner's edit of the learned part, checked by the guardrails.
     *
     * @throws Refused a guardrail failed
     */
    SoulVersion setLearned(String text);

    /**
     * Approves the pending proposal: it becomes the learned part.
     *
     * @throws NotFound     no such proposal
     * @throws Conflict     it is not pending, failed the guardrails, or the learned part changed since it was made
     * @throws Refused      it fails the guardrails against the core as it is now
     */
    SoulVersion approve(long proposal);

    /**
     * Rejects a proposal (or dismisses one the guardrails rejected): nothing changes.
     *
     * @throws NotFound no such proposal
     * @throws Conflict it was decided already
     */
    SoulProposal reject(long proposal);

    /**
     * A new version of {@code part} with the text of an earlier one.
     *
     * @throws NotFound no such version
     * @throws Refused  a learned version that fails the guardrails against the core as it is now
     */
    SoulVersion revert(SoulPart part, int version);

    /** Runs the reflection now, whatever the schedule; the proposal it made, if any. */
    Optional<SoulProposal> reflect();

    /** No such proposal or version. */
    class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** The request does not fit the soul's state. */
    class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    /** A guardrail failed: the text is refused, with every rule's verdict. */
    class Refused extends RuntimeException {
        private final transient List<Guardrails.Check> checks;

        public Refused(List<Guardrails.Check> checks) {
            super("refused by the guardrails: " + Guardrails.reasons(checks));
            this.checks = List.copyOf(checks);
        }

        public List<Guardrails.Check> checks() {
            return checks;
        }
    }
}
