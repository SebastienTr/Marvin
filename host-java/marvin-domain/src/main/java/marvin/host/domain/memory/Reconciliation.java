// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Turns a candidate and the operation chosen for it into changes to the facts (docs/design.md 5.2, steps 2
 * and 3), keeping both clocks: our time ({@code learnedAt}, {@code expiredAt}) is {@code now}; world time
 * ({@code validFrom}, {@code validTo}) comes from the candidate or the event.
 *
 * <p>The owner's word wins: a pinned fact is never changed by extraction (an update or an invalidation of one
 * becomes a new fact beside it, for the owner to see), and an owner-written fact is never reworded (an update
 * of one only adds sources); the world may still end an owner-written fact that is not pinned.
 */
public final class Reconciliation {

    private Reconciliation() {
    }

    /** An existing fact that ends: {@code expiredAt} (ours), {@code validTo} (the world's), maybe a successor. */
    public record Expiry(UUID id, Instant expiredAt, Instant validTo, UUID supersededBy) {
    }

    /**
     * The changes, to write together.
     *
     * @param added        new facts (with their sources)
     * @param expired      facts that end
     * @param moreSources  existing facts that get these events as more sources
     * @param applied      the operation actually applied (after the owner's rules)
     */
    public record Plan(List<Fact> added, List<Expiry> expired, Map<UUID, List<Long>> moreSources, Operation applied) {
        public Plan {
            added = List.copyOf(added);
            expired = List.copyOf(expired);
            moreSources = Map.copyOf(moreSources);
        }
    }

    /**
     * @param eventTime   the time of the batch's last event: relative dates were resolved against it, and a
     *                    fact without its own {@code validFrom} starts then
     * @param extractedBy the model and prompt version
     */
    public static Plan plan(FactCandidate c, Operation op, List<Fact> similar, List<Long> eventIds, Instant eventTime,
                            Instant now, Supplier<UUID> ids, String extractedBy) {
        Objects.requireNonNull(op, "op");
        Fact target = switch (op) {
            case Operation.Update u -> find(similar, u.target());
            case Operation.Invalidate i -> find(similar, i.target());
            case Operation.Noop n -> find(similar, n.target());
            case Operation.Add a -> null;
        };
        if (!(op instanceof Operation.Add) && target == null) {
            op = new Operation.Add();
        }
        if (target != null && target.pinned() && (op instanceof Operation.Update || op instanceof Operation.Invalidate)) {
            op = new Operation.Add();
            target = null;
        }
        if (target != null && target.origin() == FactOrigin.OWNER && op instanceof Operation.Update) {
            op = new Operation.Noop(target.id());
        }
        return switch (op) {
            case Operation.Add a -> new Plan(List.of(fresh(c, c.statement(), c.validFrom() != null ? c.validFrom() : eventTime,
                    eventIds, now, ids, extractedBy)), List.of(), Map.of(), op);
            case Operation.Noop n -> new Plan(List.of(), List.of(), Map.of(target.id(), List.copyOf(eventIds)), op);
            case Operation.Update u -> {
                List<Long> sources = merge(target.sources(), eventIds);
                Checked reworded = reworded(c, u.statement());
                Fact next = new Fact(ids.get(), target.subject(), reworded.statement(),
                        c.kind(), Math.max(c.importance(), target.importance()), Math.max(c.confidence(), target.confidence()),
                        reworded.sensitivity().atLeast(target.sensitivity()),
                        target.validFrom() != null ? target.validFrom() : c.validFrom(),
                        c.validTo() != null ? c.validTo() : target.validTo(), now, null, null, target.lastUsedAt(),
                        target.useCount(), false, false, FactOrigin.EXTRACTED, extractedBy, sources);
                yield new Plan(List.of(next), List.of(new Expiry(target.id(), now, target.validTo(), next.id())), Map.of(), op);
            }
            case Operation.Invalidate i -> {
                Instant start = c.validFrom() != null ? c.validFrom() : eventTime;
                Instant end = i.validTo() != null ? i.validTo() : start;
                Fact next = fresh(c, c.statement(), start, eventIds, now, ids, extractedBy);
                yield new Plan(List.of(next), List.of(new Expiry(target.id(), now, end, null)), Map.of(), op);
            }
        };
    }

    private record Checked(String statement, Sensitivity sensitivity) {
    }

    /**
     * The model's merged wording of an update goes through the candidate's checks (a secret drops it, health and
     * money make it sensitive, the length is bounded); rejected, the candidate's own statement is used.
     */
    static Checked reworded(FactCandidate c, String statement) {
        if (statement == null || statement.isBlank()) {
            return new Checked(c.statement(), c.sensitivity());
        }
        return switch (FactCandidate.check(new FactCandidate.Raw(c.subject(), statement, c.kind().wire(), null, null,
                c.importance(), c.sensitivity().wire(), c.confidence()), java.time.ZoneOffset.UTC, c.sensitivity())) {
            case FactCandidate.Accepted a -> new Checked(a.candidate().statement(), a.candidate().sensitivity().atLeast(c.sensitivity()));
            case FactCandidate.Dropped d -> new Checked(c.statement(), c.sensitivity());
        };
    }

    private static Fact fresh(FactCandidate c, String statement, Instant validFrom, List<Long> eventIds, Instant now,
                              Supplier<UUID> ids, String extractedBy) {
        return new Fact(ids.get(), c.subject(), statement, c.kind(), c.importance(), c.confidence(), c.sensitivity(),
                validFrom, c.validTo(), now, null, null, null, 0, false, false, FactOrigin.EXTRACTED, extractedBy,
                eventIds);
    }

    private static Fact find(List<Fact> similar, UUID id) {
        for (Fact f : similar) {
            if (f.id().equals(id)) {
                return f;
            }
        }
        return null;
    }

    static List<Long> merge(List<Long> a, List<Long> b) {
        LinkedHashSet<Long> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return new ArrayList<>(s);
    }
}
