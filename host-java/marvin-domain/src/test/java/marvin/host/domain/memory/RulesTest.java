// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import marvin.host.domain.shared.TokenEstimator;

/** Redaction, event feeds, batches, decay, periods, settings and token estimates. */
class RulesTest {
    static final Instant T0 = Instant.parse("2026-09-28T08:00:00Z");

    @Test
    void secretsAreRedacted() {
        assertThat(Redaction.redact("ma carte 4111 1111 1111 1111 expire")).isEqualTo("ma carte [redacted] expire");
        assertThat(Redaction.redact("order 1234 5678 9012 3456")).isEqualTo("order 1234 5678 9012 3456");     // not Luhn
        assertThat(Redaction.redact("le mot de passe c'est Tournesol42 ok")).isEqualTo("le mot de passe c'est [redacted] ok");
        assertThat(Redaction.redact("My PIN is 4821.")).isEqualTo("My PIN is [redacted].");
        assertThat(Redaction.redact(Redaction.redact("Mon code PIN est 4821"))).isEqualTo("Mon code PIN est [redacted]");
        assertThat(Redaction.redact("IBAN FR76 3000 6000 0112 3456 7890 189")).isEqualTo("IBAN [redacted]");
        assertThat(Redaction.redact("I code in Java")).isEqualTo("I code in Java");
        assertThat(Redaction.redactLikelyCodes("la porte, c'est 4812B, au 3e")).isEqualTo("la porte, c'est [redacted], au 3e");
    }

    @Test
    void feedsLabelAtTheSource() {
        MemoryEvent heard = EventFeeds.conversation(42, 1_790_000_000.25, "heard", "J'habite à Lyon",
                Map.of("language", "fr", "context", "big", "source", "voice")).orElseThrow();
        assertThat(heard.externalRef()).isEqualTo("conversation:42");
        assertThat(heard.data()).containsOnlyKeys("language", "source");
        assertThat(heard.ts()).isEqualTo(Instant.ofEpochSecond(1_790_000_000L, 250_000_000));
        assertThat(EventFeeds.conversation(1, 0, "note", "Voice on", Map.of())).isEmpty();
        // a question asked with an image: a note in the log, never the image or what is known of it
        MemoryEvent shown = EventFeeds.conversation(43, 1_790_000_001, "heard", "What is this plant?",
                Map.of("language", "en", "source", "typed", "image", Map.of("width", 1280, "height", 960, "bytes", 212_000,
                        "sha256", "ab12"))).orElseThrow();
        assertThat(shown.body()).isEqualTo("What is this plant? [the owner showed an image with this question]");
        assertThat(shown.data()).containsOnlyKeys("language", "source");
        assertThat(EventFeeds.presence(7, 0, "host_started", "Marvin started", Map.of())).isEmpty();
        MemoryEvent vitals = EventFeeds.presence(8, 0, "vitals_acquired", "Breathing 14/min",
                Map.of("breath_rate", 14.0)).orElseThrow();
        assertThat(vitals.sensitivity()).isEqualTo(Sensitivity.SENSITIVE);
        assertThat(EventFeeds.presence(9, 0, "sat_down", "You sat down", Map.of()).orElseThrow().sensitivity())
                .isEqualTo(Sensitivity.NORMAL);
    }

    static MemoryEvent said(long id, Instant at, String kind) {
        return new MemoryEvent(id, at, at, "conversation", kind, Sensitivity.NORMAL, "c:" + id, "text " + id, Map.of(), null);
    }

    @Test
    void conversationsAreCutAtSilences() {
        List<MemoryEvent> evs = List.of(said(1, T0, "heard"), said(2, T0.plusSeconds(5), "reply"),
                new MemoryEvent(3, T0.plusSeconds(6), T0, "brain", "sat_down", Sensitivity.NORMAL, "p:1", "x", Map.of(), null),
                said(4, T0.plusSeconds(3600), "heard"), said(5, T0.plusSeconds(3601), "reply"), said(6, T0.plusSeconds(3602), "heard"));
        List<List<MemoryEvent>> b = Batches.conversations(evs, Duration.ofMinutes(10), 2);
        assertThat(b).extracting(l -> l.stream().map(MemoryEvent::id).toList())
                .containsExactly(List.of(1L, 2L), List.of(4L, 5L), List.of(6L));
    }

    static Fact fact(int importance, Instant learned, boolean pinned, FactOrigin origin) {
        return new Fact(UUID.randomUUID(), "owner", "x", FactKind.STATE, importance, 0.8, Sensitivity.NORMAL, null, null, learned,
                null, null, null, 0, false, pinned, origin, "", List.of(1L));
    }

    @Test
    void factsFadeWithTimeUnlessTheOwnerKeepsThem() {
        DecayRules d = DecayRules.DEFAULT;
        Fact trivia = fact(1, T0, false, FactOrigin.EXTRACTED);
        Fact core = fact(10, T0, false, FactOrigin.EXTRACTED);
        assertThat(d.archives(trivia, T0.plus(Duration.ofDays(10)))).isFalse();
        assertThat(d.archives(trivia, T0.plus(Duration.ofDays(15)))).isTrue();
        assertThat(d.archives(core, T0.plus(Duration.ofDays(365)))).isFalse();
        assertThat(d.archives(core, T0.plus(Duration.ofDays(700)))).isTrue();
        assertThat(d.archives(fact(1, T0, true, FactOrigin.EXTRACTED), T0.plus(Duration.ofDays(900)))).isFalse();
        assertThat(d.archives(fact(1, T0, false, FactOrigin.OWNER), T0.plus(Duration.ofDays(900)))).isFalse();
        Fact used = trivia.withUse(T0.plus(Duration.ofDays(14)), 1);
        assertThat(d.archives(used, T0.plus(Duration.ofDays(15)))).isFalse();
    }

    @Test
    void periods() {
        LocalDate wed = LocalDate.of(2026, 9, 30);
        assertThat(EpisodeLevel.WEEK.start(wed)).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(EpisodeLevel.WEEK.end(LocalDate.of(2026, 9, 28))).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(EpisodeLevel.MONTH.start(wed)).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(EpisodeLevel.MONTH.below()).isEqualTo(EpisodeLevel.WEEK);
    }

    @Test
    void settingsValidate() {
        MemorySettings s = MemorySettings.DEFAULTS.with(Map.of("night_model", "qwen3:27b", "night_hour", 4));
        assertThat(s.nightModel()).isEqualTo("qwen3:27b");
        assertThat(s.nightHour()).isEqualTo(4);
        assertThatThrownBy(() -> s.with(Map.of("night_hour", 24))).hasMessage("night_hour must be a whole number from 0 to 23");
        assertThatThrownBy(() -> s.with(Map.of("embed_model", ""))).hasMessage("embed_model must be a model name");
        assertThatThrownBy(() -> s.with(Map.of("wat", 1))).hasMessage("unknown memory setting: wat");
        assertThat(MemorySettings.fromStored(Map.of("idle_minutes", "ten", "collect_brain", false)))
                .isEqualTo(MemorySettings.DEFAULTS.with(Map.of("collect_brain", false)));
        assertThat(s.collects("owner")).isTrue();
        assertThat(MemorySettings.fromStored(s.toMap())).isEqualTo(s);
    }

    @Test
    void tokenEstimatesCalibrate() {
        TokenEstimator e = TokenEstimator.DEFAULT;
        assertThat(e.estimate("")).isZero();
        assertThat(e.estimate("x".repeat(350))).isEqualTo(110);
        TokenEstimator c = e.calibrated(4000, 1000);
        assertThat(c.charsPerToken()).isGreaterThan(e.charsPerToken());
    }
}
