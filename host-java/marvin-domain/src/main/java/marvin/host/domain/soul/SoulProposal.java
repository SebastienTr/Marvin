// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A new learned part proposed by the weekly reflection (docs/design.md 8.2), with its rationale, the events behind it,
 * the diff against the learned part it was made from, and the guardrails' verdicts. Nothing changes until the owner
 * approves it.
 *
 * <p>States: {@code pending} (waiting for the owner), {@code approved} (it became a learned version), {@code rejected}
 * (by the owner, or by the guardrails: then it is shown with its reasons until the owner dismisses it), {@code superseded}
 * (a newer proposal replaced it before the owner decided).
 *
 * @param id          the store's id, 0 before it is stored
 * @param created     Unix seconds
 * @param text        the proposed learned part
 * @param baseVersion the learned version it was made from (its diff is against it)
 * @param decided     when the owner decided (approved, rejected or dismissed), {@code null} until then
 */
public record SoulProposal(long id, double created, State state, String text, String rationale, List<SoulDiff.Op> diff,
                           List<Long> events, List<Guardrails.Check> guardrails, int baseVersion, Double decided) {

    public enum State {
        PENDING, APPROVED, REJECTED, SUPERSEDED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static State of(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }
    }

    public SoulProposal {
        Objects.requireNonNull(state, "state");
        text = SoulText.normalise(text);
        rationale = rationale == null ? "" : rationale.strip();
        diff = diff == null ? List.of() : List.copyOf(diff);
        events = events == null ? List.of() : List.copyOf(events);
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
    }

    /**
     * A proposal made now against {@code base} (its text, its version), checked against {@code core}: pending when every
     * guardrail passes, rejected (with its reasons, not yet seen by the owner) otherwise.
     */
    public static SoulProposal propose(double now, String text, String rationale, List<Long> events, String baseText,
                                       int baseVersion, String core) {
        List<Guardrails.Check> checks = Guardrails.check(text, core);
        return new SoulProposal(0, now, Guardrails.passes(checks) ? State.PENDING : State.REJECTED, text, rationale,
                SoulDiff.of(baseText, text), events, checks, baseVersion, null);
    }

    public SoulProposal withId(long newId) {
        return new SoulProposal(newId, created, state, text, rationale, diff, events, guardrails, baseVersion, decided);
    }

    public SoulProposal decide(State to, double now) {
        if (to == State.PENDING) {
            throw new IllegalArgumentException("a decision is not pending");
        }
        if (!awaitsOwner()) {
            throw new IllegalStateException("proposal " + id + " was already decided");
        }
        if (to == State.APPROVED && state != State.PENDING) {
            throw new IllegalStateException("proposal " + id + " failed the guardrails: it cannot be approved");
        }
        return new SoulProposal(id, created, to, text, rationale, diff, events, guardrails, baseVersion, now);
    }

    /** Whether the owner still has to see it: pending, or rejected by the guardrails and not dismissed yet. */
    public boolean awaitsOwner() {
        return decided == null && (state == State.PENDING || state == State.REJECTED);
    }

    /** As the app shows it (golden/api/get/soul.json). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("created", created);
        m.put("state", state.id());
        m.put("rationale", rationale);
        m.put("diff", diff.stream().map(SoulDiff.Op::toMap).toList());
        m.put("events", events);
        m.put("guardrails", guardrails.stream().map(Guardrails.Check::toMap).toList());
        return m;
    }
}
