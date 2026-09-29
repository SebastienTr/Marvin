// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The read path's rules: retrieval scoring, the recall periods, and how facts and summaries are written for the model. */
class ReadPathTest {
    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    static Fact fact(String statement, int importance, Instant learned, Instant lastUsed) {
        return new Fact(UUID.randomUUID(), "owner", statement, FactKind.STATE, importance, 0.8, Sensitivity.NORMAL, null, null,
                learned, null, null, lastUsed, 0, false, false, FactOrigin.EXTRACTED, "", List.of(1L));
    }

    @Test
    void theScoreIsAWeightedSumOfNormalisedTerms() {
        RetrievalScoring s = RetrievalScoring.DEFAULT;
        Fact a = fact("a", 10, NOW, null);                               // most important, just learned
        Fact b = fact("b", 2, NOW.minus(Duration.ofHours(100)), null);   // the most similar, old, minor
        Fact c = fact("c", 5, NOW.minus(Duration.ofHours(10)), NOW);     // used just now
        List<RetrievalScoring.Scored> out = s.score(List.of(a, b, c), List.of(0.60, 0.80, 0.70), NOW);
        RetrievalScoring.Scored sa = out.stream().filter(x -> x.fact() == a).findFirst().orElseThrow();
        RetrievalScoring.Scored sb = out.stream().filter(x -> x.fact() == b).findFirst().orElseThrow();
        RetrievalScoring.Scored sc = out.stream().filter(x -> x.fact() == c).findFirst().orElseThrow();
        assertThat(sa.relevance()).isZero();                              // min-max over the candidates
        assertThat(sb.relevance()).isEqualTo(1.0);
        assertThat(sc.relevance()).isCloseTo(0.5, within(1e-9));
        assertThat(sb.recency()).isCloseTo(Math.pow(0.995, 100), within(1e-9));
        assertThat(sc.recency()).isEqualTo(1.0);                          // last used counts, not learned
        assertThat(sa.score()).isCloseTo(0 + 0.5 * 1 + 0.7 * 1.0, within(1e-9));
        assertThat(sb.score()).isCloseTo(1.0 + 0.5 * Math.pow(0.995, 100) + 0.7 * 0.2, within(1e-9));
        assertThat(sc.score()).isCloseTo(0.5 + 0.5 + 0.7 * 0.5, within(1e-9));
        assertThat(out).extracting(RetrievalScoring.Scored::fact).containsExactly(sb.fact(), sc.fact(), sa.fact());
    }

    @Test
    void aSumNotAProductSoOneWeakTermDoesNotHideAnImportantRelevantFact() {
        Fact vital = fact("vital", 10, NOW.minus(Duration.ofDays(400)), null);   // recency almost 0
        Fact trivia = fact("trivia", 1, NOW, null);
        List<RetrievalScoring.Scored> out = RetrievalScoring.DEFAULT.score(List.of(vital, trivia), List.of(0.9, 0.5), NOW);
        assertThat(out.getFirst().fact()).isSameAs(vital);
    }

    @Test
    void theRelevanceFloorKeepsNoiseOut() {
        Fact near = fact("near", 5, NOW, null);
        Fact far = fact("far", 10, NOW, null);
        List<RetrievalScoring.Scored> out = RetrievalScoring.DEFAULT.score(List.of(near, far), List.of(0.62, 0.40), NOW);
        assertThat(out).extracting(RetrievalScoring.Scored::fact).containsExactly(near);
        assertThat(out.getFirst().relevance()).as("alone above the floor").isEqualTo(1.0);
        assertThat(RetrievalScoring.DEFAULT.score(List.of(far), List.of(0.2), NOW)).isEmpty();
        assertThatThrownBy(() -> RetrievalScoring.DEFAULT.score(List.of(far), List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recallPeriodsAreLocalDays() {
        Instant tuesday = Instant.parse("2026-09-29T22:30:00Z");          // already Wednesday 00:30 in Paris
        assertThat(RecallWindow.of("today", tuesday, ZONE)).isEqualTo(new RecallWindow("today", LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 1)));
        assertThat(RecallWindow.of("yesterday", tuesday, ZONE).from()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(RecallWindow.of("this_week", tuesday, ZONE).from()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(RecallWindow.of("last_week", tuesday, ZONE)).isEqualTo(new RecallWindow("last_week",
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28)));
        assertThat(RecallWindow.of("last_month", tuesday, ZONE)).isEqualTo(new RecallWindow("last_month",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)));
        assertThat(RecallWindow.of("whenever", tuesday, ZONE)).isEqualTo(RecallWindow.ANY);
        RecallWindow y = RecallWindow.of("yesterday", tuesday, ZONE);
        assertThat(y.contains(Instant.parse("2026-09-29T21:00:00Z"), ZONE)).isTrue();
        assertThat(y.contains(Instant.parse("2026-09-29T22:10:00Z"), ZONE)).isFalse();
        assertThat(y.overlaps(Instant.parse("2026-01-01T00:00:00Z"), null, ZONE)).isTrue();
        assertThat(y.overlaps(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), ZONE)).isFalse();
        assertThat(RecallWindow.ANY.contains(Instant.EPOCH, ZONE)).isTrue();
    }

    @Test
    void factsAreWrittenWithTheirValidity() {
        Fact plan = new Fact(UUID.randomUUID(), "owner", "The owner flies to Oslo.", FactKind.PLAN, 6, 0.9, Sensitivity.NORMAL,
                Instant.parse("2026-10-12T08:00:00Z"), Instant.parse("2026-10-19T08:00:00Z"), NOW, null, null, null, 0, false,
                false, FactOrigin.EXTRACTED, "", List.of());
        assertThat(MemoryText.contextLine(plan, NOW, ZONE)).isEqualTo("The owner flies to Oslo. (from 12 October 2026, until 19 October 2026)");
        assertThat(MemoryText.validity(plan, ZONE)).isEqualTo("from 12 October 2026 to 19 October 2026");
        assertThat(MemoryText.status(plan, NOW)).isEqualTo("current");
        assertThat(MemoryText.status(plan, Instant.parse("2026-11-01T00:00:00Z"))).isEqualTo("past");
        Fact open = fact("The owner likes tea.", 3, NOW, null);
        assertThat(MemoryText.contextLine(open, NOW, ZONE)).isEqualTo("The owner likes tea.");
        assertThat(MemoryText.validity(open, ZONE)).isEqualTo("learned 29 September 2026");
        assertThat(MemoryText.status(open.withArchived(true), NOW)).isEqualTo("archived");
        assertThat(MemoryText.status(open.withExpiry(NOW, null, UUID.randomUUID()), NOW)).isEqualTo("replaced");
    }

    @Test
    void summariesAreCutIntoSentencesAndRankedByWords() {
        assertThat(MemoryText.sentences("Sam worked late. He called Claire in Lyon! Then 3 cups of tea.  "))
                .containsExactly("Sam worked late.", "He called Claire in Lyon!", "Then 3 cups of tea.");
        assertThat(MemoryText.words("Où habite ma sœur Claire ?")).contains("habite", "claire").doesNotContain("ou", "ma");
        assertThat(MemoryText.overlap(MemoryText.words("sister Claire"), "He called Claire.")).isEqualTo(0.5);
        assertThat(MemoryText.overlap(MemoryText.words("the"), "anything")).isZero();
        assertThat(MemoryText.searchTerms("Quand ai-je parlé de la réunion ?", 2)).containsExactly("réunion", "parlé");
    }
}
