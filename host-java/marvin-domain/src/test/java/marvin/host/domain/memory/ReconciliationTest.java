// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class ReconciliationTest {
    static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    static final Instant SAID = Instant.parse("2026-09-28T11:00:00Z");
    final Supplier<UUID> ids = UUID::randomUUID;

    static Fact fact(String statement, FactOrigin origin, boolean pinned) {
        return new Fact(UUID.randomUUID(), "owner", statement, FactKind.BIOGRAPHICAL, 7, 0.8, Sensitivity.NORMAL, T0, null, T0,
                null, null, null, 0, false, pinned, origin, "x", List.of(1L));
    }

    static FactCandidate candidate(String statement, Instant validFrom) {
        return new FactCandidate("owner", statement, FactKind.BIOGRAPHICAL, validFrom, null, 8, Sensitivity.PERSONAL, 0.9);
    }

    @Test
    void addStartsAtTheEventWhenTheWorldTimeIsUnknown() {
        Reconciliation.Plan p = Reconciliation.plan(candidate("Lives in Lyon", null), new Operation.Add(), List.of(), List.of(5L),
                SAID, NOW, ids, "m");
        Fact f = p.added().getFirst();
        assertThat(f.validFrom()).isEqualTo(SAID);
        assertThat(f.learnedAt()).isEqualTo(NOW);
        assertThat(f.sources()).containsExactly(5L);
        assertThat(f.extractedBy()).isEqualTo("m");
        assertThat(f.current(NOW)).isTrue();
    }

    @Test
    void invalidateEndsTheOldFactOnBothClocksAndAddsTheNewOne() {
        Fact lyon = fact("Lives in Lyon", FactOrigin.EXTRACTED, false);
        Instant moved = Instant.parse("2026-09-01T00:00:00Z");
        Reconciliation.Plan p = Reconciliation.plan(candidate("Lives in Lille", moved), new Operation.Invalidate(lyon.id(), null),
                List.of(lyon), List.of(9L), SAID, NOW, ids, "m");
        assertThat(p.expired()).containsExactly(new Reconciliation.Expiry(lyon.id(), NOW, moved, null));
        assertThat(p.added()).extracting(Fact::statement).containsExactly("Lives in Lille");
        Fact ended = lyon.withExpiry(NOW, moved, null);
        assertThat(ended.validAt(Instant.parse("2026-06-01T00:00:00Z"))).isTrue();
        assertThat(ended.validAt(Instant.parse("2026-09-10T00:00:00Z"))).isFalse();
        assertThat(ended.believedAt(Instant.parse("2026-09-10T00:00:00Z"))).isTrue();
        assertThat(ended.believedAt(NOW.plusSeconds(1))).isFalse();
    }

    @Test
    void updateSupersedesWithTheMergedWordingAndKeepsProvenance() {
        Fact jazz = fact("Likes jazz", FactOrigin.EXTRACTED, false);
        Reconciliation.Plan p = Reconciliation.plan(candidate("Likes Coltrane", null),
                new Operation.Update(jazz.id(), "Likes jazz, especially Coltrane"), List.of(jazz), List.of(9L), SAID, NOW, ids, "m");
        Fact next = p.added().getFirst();
        assertThat(next.statement()).isEqualTo("Likes jazz, especially Coltrane");
        assertThat(next.validFrom()).isEqualTo(T0);
        assertThat(next.sources()).containsExactly(1L, 9L);
        assertThat(next.importance()).isEqualTo(8);
        assertThat(p.expired()).containsExactly(new Reconciliation.Expiry(jazz.id(), NOW, null, next.id()));
    }

    @Test
    void noopAddsSources() {
        Fact f = fact("Lives in Lyon", FactOrigin.EXTRACTED, false);
        Reconciliation.Plan p = Reconciliation.plan(candidate("Lives in Lyon", null), new Operation.Noop(f.id()), List.of(f),
                List.of(3L, 4L), SAID, NOW, ids, "m");
        assertThat(p.added()).isEmpty();
        assertThat(p.moreSources()).containsEntry(f.id(), List.of(3L, 4L));
    }

    @Test
    void theOwnersWordWins() {
        Fact pinned = fact("Lives in Lyon", FactOrigin.OWNER, true);
        Reconciliation.Plan p = Reconciliation.plan(candidate("Lives in Lille", null), new Operation.Invalidate(pinned.id(), null),
                List.of(pinned), List.of(3L), SAID, NOW, ids, "m");
        assertThat(p.applied()).isInstanceOf(Operation.Add.class);
        assertThat(p.expired()).isEmpty();
        Fact owners = fact("Call me Sam", FactOrigin.OWNER, false);
        Reconciliation.Plan q = Reconciliation.plan(candidate("Wants to be called Samuel", null),
                new Operation.Update(owners.id(), null), List.of(owners), List.of(3L), SAID, NOW, ids, "m");
        assertThat(q.applied()).isEqualTo(new Operation.Noop(owners.id()));
        Reconciliation.Plan r = Reconciliation.plan(candidate("Moved", null), new Operation.Invalidate(owners.id(), null),
                List.of(owners), List.of(3L), SAID, NOW, ids, "m");
        assertThat(r.expired()).hasSize(1);
    }

    @Test
    void theModelsDecisionIsReadSafely() {
        Fact a = fact("A", FactOrigin.EXTRACTED, false);
        Fact b = fact("B", FactOrigin.EXTRACTED, false);
        List<Fact> similar = List.of(a, b);
        assertThat(Operation.parse("update", 2, " New ", "", similar, ZoneOffset.UTC)).isEqualTo(new Operation.Update(b.id(), "New"));
        assertThat(Operation.parse("INVALIDATE", 1, "", "2026-09-01", similar, ZoneOffset.UTC))
                .isEqualTo(new Operation.Invalidate(a.id(), Instant.parse("2026-09-01T00:00:00Z")));
        assertThat(Operation.parse("NOOP", 3, "", "", similar, ZoneOffset.UTC)).isInstanceOf(Operation.Add.class);
        assertThat(Operation.parse("DELETE", 1, "", "", similar, ZoneOffset.UTC)).isInstanceOf(Operation.Add.class);
        assertThat(Operation.parse("NOOP", null, "", "", similar, ZoneOffset.UTC)).isInstanceOf(Operation.Add.class);
        Reconciliation.Plan p = Reconciliation.plan(candidate("C", null), new Operation.Noop(UUID.randomUUID()), similar,
                List.of(1L), SAID, NOW, ids, "m");
        assertThat(p.applied()).isInstanceOf(Operation.Add.class);
    }
}
