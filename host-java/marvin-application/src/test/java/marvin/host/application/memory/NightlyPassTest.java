// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.FactOrigin;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Sensitivity;

class NightlyPassTest {
    static final Instant NOW = ZonedDateTime.of(2026, 10, 6, 3, 10, 0, 0, MemoryFixture.ZONE).toInstant();
    final FakeMemoryModel model = new FakeMemoryModel();
    final MemoryFixture m = new MemoryFixture(NOW, model);
    final MemoryModel.Target target = new MemoryModel.Target("h", "m");

    Instant at(int day, int hour) {
        return ZonedDateTime.of(2026, 9, day, hour, 0, 0, 0, MemoryFixture.ZONE).toInstant();
    }

    @Test
    void daysAreSummarisedWithoutTheirSensitiveEventsThenRolledUp() {
        for (int d = 28; d <= 30; d++) {
            m.brain(at(d, 9), "arrived", "You came in", Sensitivity.NORMAL);
            m.brain(at(d, 10), "vitals_acquired", "Breathing 14/min, heart 61/min", Sensitivity.SENSITIVE);
            m.say(at(d, 11), "heard", "Aujourd'hui je travaille sur Marvin");
        }
        assertThat(m.nightly.dayEpisodes(target, () -> false)).isEqualTo(3);
        MemoryModel.SummaryRequest day = (MemoryModel.SummaryRequest) model.requests.getFirst();
        assertThat(day.items()).containsExactly("09:00 Presence: You came in", "11:00 Owner: Aujourd'hui je travaille sur Marvin");
        assertThat(day.first()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(m.store.episodes.list(EpisodeLevel.DAY, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 7))).hasSize(3);
        assertThat(m.nightly.dayEpisodes(target, () -> false)).isZero();       // idempotent

        // the week of 28 September, then September from the weeks that start in it; October is not over
        assertThat(m.nightly.rollUps(target, () -> false)).isEqualTo(2);
        MemoryModel.SummaryRequest week = (MemoryModel.SummaryRequest) model.requests.get(model.requests.size() - 2);
        assertThat(week.level()).isEqualTo(EpisodeLevel.WEEK);
        assertThat(week.items()).hasSize(3).allMatch(s -> s.startsWith("Mon 2026-09-28: ") || s.startsWith("Tue") || s.startsWith("Wed"));

        // forgetting a day's event marks its episodes for rewriting, and the next night rewrites them
        MemoryEvent any = m.store.log.between(at(29, 0), at(29, 23), 10).getFirst();
        m.admin.forgetEvent(any.id());
        assertThat(m.store.episodes.stale()).extracting(e -> e.level() + " " + e.day())
                .containsExactlyInAnyOrder("DAY 2026-09-29", "WEEK 2026-09-28", "MONTH 2026-09-01");
        assertThat(m.nightly.dayEpisodes(target, () -> false)).isEqualTo(1);
        assertThat(m.nightly.rollUps(target, () -> false)).isEqualTo(2);
        assertThat(m.store.episodes.stale()).isEmpty();
    }

    Fact fact(String statement, Sensitivity s, Instant learned, long source) {
        Fact f = new Fact(UUID.randomUUID(), "owner", statement, FactKind.STATE, 7, 0.9, s, null, null, learned, null, null,
                null, 0, false, false, FactOrigin.EXTRACTED, "", List.of(source));
        m.store.facts.apply(new Reconciliation.Plan(List.of(f), List.of(), Map.of(), new Operation.Add()), Map.of(f.id(), new float[1024]));
        return m.store.facts.rows.get(f.id());
    }

    @Test
    void theProfileIsRewrittenKeepingTheOwnersLinesAndNeverSensitiveFacts() {
        long e = m.say(at(28, 11), "heard", "x").id();
        m.store.profiles.add(new BlockVersion(0, Block.PROFILE, "Call me Sam.\nThe owner lives in Lyon.", 10, BlockVersion.Status.ACTIVE,
                "", List.of(), BlockVersion.Author.OWNER, at(28, 1), at(28, 1), List.of("Call me Sam.")));
        Fact lyon = fact("The owner lives in Lyon.", Sensitivity.NORMAL, at(27, 1), e);
        m.store.facts.apply(new Reconciliation.Plan(List.of(), List.of(new Reconciliation.Expiry(lyon.id(), at(28, 12), at(28, 12), null)),
                Map.of(), new Operation.Invalidate(lyon.id(), null)), Map.of());
        fact("The owner lives in Lille.", Sensitivity.NORMAL, at(28, 12), e);
        fact("The owner takes medication for asthma.", Sensitivity.SENSITIVE, at(28, 12), e);
        model.profile = r -> {
            assertThat(r.kept()).containsExactly("Call me Sam.");
            assertThat(r.learned()).containsExactly("The owner lives in Lille.");
            assertThat(r.ended()).containsExactly("The owner lives in Lyon.");
            return "The owner lives in Lille.\n" + "The owner enjoys long sentences that go on. ".repeat(80);
        };
        assertThat(m.nightly.profile(target, () -> false)).isTrue();
        BlockVersion v = m.store.profiles.active(Block.PROFILE).orElseThrow();
        assertThat(v.content()).isEqualTo("Call me Sam.\nThe owner lives in Lille.");
        assertThat(v.rationale()).contains("1 learned, 1 no longer true, 1 lines cut to fit");
        assertThat(v.author()).isEqualTo(BlockVersion.Author.WORKER);
        assertThat(v.keptLines()).containsExactly("Call me Sam.");
        assertThat(v.evidence()).containsExactly(e);
        assertThat(m.nightly.profile(target, () -> false)).isFalse();       // nothing new since
    }

    @Test
    void decayArchivesAndRetentionForgetsOnlyWhatNothingNeeds() {
        long e = m.say(at(1, 11), "heard", "x").id();
        Fact old = fact("The owner tried a new café.", Sensitivity.NORMAL, NOW.minus(Duration.ofDays(400)), e);
        m.store.facts.rows.put(old.id(), new Fact(old.id(), old.subject(), old.statement(), old.kind(), 2, 0.9, old.sensitivity(), null,
                null, old.learnedAt(), null, null, null, 0, false, false, FactOrigin.EXTRACTED, "", old.sources()));
        fact("The owner is called Sam.", Sensitivity.NORMAL, NOW.minus(Duration.ofDays(200)), e);
        assertThat(m.nightly.decay()).isEqualTo(1);
        assertThat(m.store.facts.rows.get(old.id()).archived()).isTrue();

        m.settings.update(Map.of("retention_days", 30));
        long kept = m.brain(NOW.minus(Duration.ofDays(60)), "arrived", "You came in", Sensitivity.NORMAL).id();
        long gone = m.brain(NOW.minus(Duration.ofDays(59)), "sat_down", "You sat down", Sensitivity.NORMAL).id();
        long recent = m.brain(NOW.minus(Duration.ofDays(2)), "sat_down", "You sat down", Sensitivity.NORMAL).id();
        fact("The owner comes in at nine.", Sensitivity.NORMAL, NOW, kept);
        assertThat(m.nightly.retention()).isZero();                          // no day is summarised yet
        m.nightly.dayEpisodes(target, () -> false);
        assertThat(m.nightly.retention()).isEqualTo(1);
        assertThat(m.store.log.rows).containsKeys(kept, recent).doesNotContainKey(gone);
    }
}
