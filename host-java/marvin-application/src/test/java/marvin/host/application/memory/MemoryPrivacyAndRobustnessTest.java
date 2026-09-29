// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.in.RecallMemory;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.port.out.VoiceActivity;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.RetrievalScoring;
import marvin.host.domain.memory.Sensitivity;

/**
 * Forgetting takes a fact out of every future context; sensitive conversations stay away from guests; one failing
 * part of a nightly pass does not stop the others; the worker never undoes what the owner did meanwhile.
 */
class MemoryPrivacyAndRobustnessTest {
    /** Tuesday 6 October 2026, 03:10 in Paris. */
    static final Instant NOW = ZonedDateTime.of(2026, 10, 6, 3, 10, 0, 0, MemoryFixture.ZONE).toInstant();
    static final RecallMemory.Audience GUEST = new RecallMemory.Audience(true, false);
    final FakeMemoryModel model = new FakeMemoryModel();
    final MemoryFixture m = new MemoryFixture(NOW, model);
    final MemoryModel.Target target = new MemoryModel.Target("h", "m");
    final MemoryRecallService recall = new MemoryRecallService(m.store.facts, m.store.log, m.store.episodes, m.store.profiles,
            m.embeddings, m.days, m.clock, new RetrievalScoring(1.0, 0.5, 0.7, 0.995, 0.0), () -> { });
    final MemoryExportService export = new MemoryExportService(m.store.log, m.store.facts, m.store.episodes, m.store.profiles,
            m.settings, m.clock);
    final AtomicInteger warmUps = new AtomicInteger();
    volatile boolean busy;
    final MemoryWorker worker = new MemoryWorker(m.consolidator, m.nightly, m.store.log, m.settings, new VoiceActivity() {
        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public double lastActivity() {
            return 0;
        }
    }, warmUps::incrementAndGet, m.store.state, m.days, m.clock, m.config, m.guard, m.embeddings);

    MemoryPrivacyAndRobustnessTest() {
        model.summarize = r -> String.join(" ", r.items().stream().map(i -> i.replaceFirst("^\\d\\d:\\d\\d ", "") + ".").toList());
    }

    @AfterEach
    void close() {
        worker.close();
        recall.close();
    }

    static Instant at(int month, int day, int hour) {
        return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, MemoryFixture.ZONE).toInstant();
    }

    /** Everything a future answer or the owner's export could still show, as one text. */
    String everything(String query) {
        StringBuilder b = new StringBuilder();
        RecallMemory.Recollection r = recall.recollect(query, RecallMemory.Audience.OWNER);
        r.gist().forEach(l -> b.append(l.text()).append('\n'));
        r.facts().forEach(l -> b.append(l.text()).append('\n'));
        for (String period : List.of("", "last week")) {
            Map<String, Object> found = new java.util.LinkedHashMap<>(recall.recall(query, period, RecallMemory.Audience.OWNER));
            found.remove("query");
            b.append(found).append('\n');
        }
        m.store.profiles.rows.forEach(v -> b.append(v.content()).append('\n'));
        ExportMemoryText.append(b, export.export());
        return b.toString();
    }

    /** The export's JSON and Markdown, flattened. */
    static final class ExportMemoryText {
        static void append(StringBuilder b, marvin.host.application.memory.port.in.ExportMemory.Export e) {
            b.append(e.json()).append('\n').append(e.markdown()).append('\n');
        }
    }

    // ------------------------------------------------------------------ forgetting

    @Test
    void aForgetAskedByVoiceTakesItsOwnRequestAndAnswerWithIt() {
        MemoryEvent said = m.say(at(10, 5, 11), "heard", "My cat is called Pixel");
        model.extract = r -> List.of(FakeMemoryModel.raw("thing:pixel", "The owner has a cat named Pixel.", 6));
        m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false);
        Fact pixel = m.store.facts.rows.values().iterator().next();
        assertThat(pixel.sources()).contains(said.id());
        // today: the request repeats the fact, and so may the answer
        MemoryEvent asked = m.say(at(10, 6, 2), "heard", "Forget that my cat is called Pixel");
        m.brain(at(10, 6, 2).plusSeconds(1), "person_seen", "Someone is at the desk.", Sensitivity.NORMAL);
        m.say(at(10, 6, 2).plusSeconds(2), "reply", "Shall I forget that your cat is called Pixel?");
        MemoryEvent later = m.say(at(10, 6, 2).plusSeconds(30), "heard", "Thanks");
        ForgetConfirmations forgetting = new ForgetConfirmations(m.store.facts, m.admin, m.embeddings, m.clock,
                java.util.UUID::randomUUID);
        var p = forgetting.proposeMatching("my cat Pixel", "voice", 3, asked.externalRef(), RecallMemory.Audience.OWNER);
        assertThat(p.facts()).extracting(Fact::statement).containsExactly("The owner has a cat named Pixel.");
        // nothing withheld before the owner says yes
        assertThat(m.store.log.withheld).isEmpty();

        assertThat(forgetting.confirm(p.code(), 4, null).done()).isTrue();

        assertThat(m.store.facts.rows).isEmpty();
        assertThat(everything("cat Pixel")).doesNotContain("Pixel");
        // the next pass cannot learn it again from the request: it is not there to read
        assertThat(m.store.log.unconsolidated(50)).extracting(MemoryEvent::body).doesNotContain(
                "Forget that my cat is called Pixel", "Shall I forget that your cat is called Pixel?")
                .contains("Thanks", "Someone is at the desk.");
        assertThat(m.store.log.withheld).doesNotContain(later.id());
    }

    @Test
    void forgettingAWholeDayLeavesNothingOfItInSummariesRecallOrTheExport() {
        m.say(at(10, 5, 11), "heard", "Mon code de porte est dans le tiroir et je quitte mon travail");
        assertThat(m.nightly.dayEpisodes(target, () -> false)).isEqualTo(1);
        assertThat(everything("tiroir travail")).contains("tiroir");

        m.admin.forgetEvents(at(10, 5, 0), at(10, 6, 0));
        // at once, before any pass: nothing of it in a prompt, a recall or the export
        assertThat(everything("tiroir travail")).doesNotContain("tiroir").doesNotContain("travail");
        // the next night has nothing to write: the episode goes
        m.nightly.dayEpisodes(target, () -> false);
        assertThat(m.store.episodes.get(EpisodeLevel.DAY, LocalDate.of(2026, 10, 5))).isEmpty();
        assertThat(everything("tiroir travail")).doesNotContain("tiroir");
    }

    @Test
    void aForgottenFactLeavesThePromptTheSummariesRecallTheProfileItsHistoryAndTheExport() {
        MemoryEvent said = m.say(at(10, 5, 11), "heard", "Ma soeur Julie habite maintenant à Oslo");
        m.say(at(10, 5, 11).plusSeconds(5), "reply", "C'est noté.");
        model.extract = r -> List.of(FakeMemoryModel.raw("person:julie", "Julie lives in Oslo.", 7));
        m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false);
        Fact julie = m.store.facts.rows.values().iterator().next();
        assertThat(julie.sources()).contains(said.id());
        assertThat(m.nightly.dayEpisodes(target, () -> false)).isEqualTo(1);
        // two profile versions: the older one says it in other words, the active one also word for word
        m.store.profiles.add(new BlockVersion(0, Block.PROFILE, "Call me Sam.\nJulie, the owner's sister, moved to Norway.", 10,
                BlockVersion.Status.ACTIVE, "", List.of(), BlockVersion.Author.WORKER, at(10, 5, 20), at(10, 5, 20), List.of()));
        long older = m.store.profiles.rows.getLast().id();
        m.store.profiles.add(new BlockVersion(0, Block.PROFILE,
                "Call me Sam.\nJulie, the owner's sister, moved to Norway.\nJulie lives in Oslo.", 12, BlockVersion.Status.ACTIVE, "",
                List.of(), BlockVersion.Author.WORKER, at(10, 5, 21), at(10, 5, 21), List.of()));
        assertThat(everything("Julie Oslo")).contains("Julie lives in Oslo").contains("Julie habite");

        m.admin.forgetFact(julie.id());

        String left = everything("Julie Oslo soeur");
        assertThat(left).doesNotContain("Julie lives in Oslo").doesNotContain("habite").doesNotContain("Oslo");
        // the reworded line needs the model: it waits for the next rewrite, run by the next pass (idle or nightly)
        assertThat(m.nightly.profilePending()).isTrue();
        model.profile = r -> {
            assertThat(r.remove()).contains("Julie lives in Oslo.");
            return String.join("\n", List.of(r.previous().split("\n")).stream().filter(l -> !l.contains("Julie")).toList());
        };
        assertThat(m.nightly.profile(target, () -> false)).isTrue();
        assertThat(m.nightly.profilePending()).isFalse();
        assertThat(everything("Julie Oslo soeur")).doesNotContain("Julie").doesNotContain("Norway");
        // restoring the older version does not bring it back
        m.admin.restoreProfile(older);
        assertThat(m.store.profiles.active(Block.PROFILE).orElseThrow().content()).isEqualTo("Call me Sam.");
        // the day is summarised again without the lines it was learned from
        assertThat(m.nightly.dayEpisodes(target, () -> false)).isZero();
        assertThat(m.store.episodes.get(EpisodeLevel.DAY, LocalDate.of(2026, 10, 5))).isEmpty();
    }

    // ------------------------------------------------------------------ sensitivity

    @Test
    void aSensitiveConversationNeverReachesAGuest() {
        m.say(at(10, 5, 11), "heard", "Mon médecin dit que mon diabète s'aggrave");
        m.say(at(10, 5, 15), "heard", "Je suis allé courir au parc");
        model.extract = r -> r.lines().getFirst().text().contains("diabète")
                ? List.of(FakeMemoryModel.raw("owner", "The owner's doctor says his diabetes is getting worse.", 8))
                : List.of();
        m.consolidator.process(m.store.log.unconsolidated(1), target, () -> false);
        m.consolidator.process(m.store.log.unconsolidated(1), target, () -> false);
        assertThat(m.store.facts.rows.values()).extracting(Fact::sensitivity).containsExactly(Sensitivity.SENSITIVE);
        assertThat(m.store.log.rows.values().iterator().next().sensitivity()).isEqualTo(Sensitivity.SENSITIVE);
        m.nightly.dayEpisodes(target, () -> false);
        String summary = m.store.episodes.get(EpisodeLevel.DAY, LocalDate.of(2026, 10, 5)).orElseThrow().summary();
        assertThat(summary).contains("courir").doesNotContain("diab");

        RecallMemory.Recollection r = recall.recollect("médecin diabète", GUEST);
        assertThat(r.gist()).isEmpty();
        assertThat(r.facts()).isEmpty();
        Map<String, Object> guest = new java.util.LinkedHashMap<>(recall.recall("médecin diabète", "", GUEST));
        guest.remove("query");
        assertThat(guest.toString()).doesNotContain("diab");
        // the owner alone still has it
        assertThat(recall.recall("médecin diabète", "", RecallMemory.Audience.OWNER).get("said").toString()).contains("diabète");
    }

    @Test
    void aDaySummarisedBeforeItsSensitiveFactIsWrittenAgainWithoutIt() {
        m.say(at(10, 5, 11), "heard", "J'ai rendez-vous à l'hôpital pour mon allergie");
        m.nightly.dayEpisodes(target, () -> false);
        assertThat(m.store.episodes.get(EpisodeLevel.DAY, LocalDate.of(2026, 10, 5)).orElseThrow().summary()).contains("hôpital");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner has an allergy.", 6));
        m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false);
        assertThat(m.store.episodes.get(EpisodeLevel.DAY, LocalDate.of(2026, 10, 5)).orElseThrow().summary()).isEmpty();
        assertThat(recall.recollect("hôpital", RecallMemory.Audience.OWNER).gist()).isEmpty();
    }

    @Test
    void anEditThatMakesAFactSensitiveTakesItOutOfTheProfile() {
        MemoryEvent e = m.say(at(10, 5, 11), "heard", "Je travaille chez Acme");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner works at Acme.", 7));
        m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false);
        Fact f = m.store.facts.rows.values().iterator().next();
        m.store.profiles.add(new BlockVersion(0, Block.PROFILE, "Call me Sam.\nThe owner works at Acme.", 10,
                BlockVersion.Status.ACTIVE, "", List.of(), BlockVersion.Author.WORKER, NOW, NOW, List.of()));
        m.admin.edit(f.id(), new marvin.host.application.memory.port.in.ManageFacts.Edit(null, null, null, null, Sensitivity.SENSITIVE));
        assertThat(m.store.profiles.active(Block.PROFILE).orElseThrow().content()).isEqualTo("Call me Sam.");
        assertThat(m.store.log.rows.get(e.id()).sensitivity()).isEqualTo(Sensitivity.SENSITIVE);
        m.admin.archive(m.admin.remember("The owner plays chess.", "owner", Sensitivity.NORMAL).id(), true);
    }

    // ------------------------------------------------------------------ robustness

    @Test
    void withoutTheEmbeddingModelTheNightStillWritesItsDaysAndProfile() {
        m.say(at(10, 5, 11), "heard", "Je suis allé à la plage");
        m.store.profiles.add(new BlockVersion(0, Block.PROFILE, "Call me Sam.", 3, BlockVersion.Status.ACTIVE, "", List.of(),
                BlockVersion.Author.WORKER, at(10, 1, 3), at(10, 1, 3), List.of()));
        m.admin.remember("The owner likes the sea.", "owner", Sensitivity.NORMAL);
        m.embedder.failure = new Embedder.Unavailable("no model bge-m3", "Run `ollama pull bge-m3`.");
        model.profile = r -> r.previous() + "\n" + String.join("\n", r.learned());

        ConsolidateMemory.Report r = worker.run(ConsolidateMemory.Pass.NIGHTLY);
        assertThat(r.outcome()).isEqualTo("partial");
        assertThat(r.error()).contains("extract").contains("bge-m3");
        assertThat(r.fix()).contains("ollama pull");
        assertThat(r.counts()).containsEntry("days", 1).containsEntry("profile", 1).containsKey("archived");
        assertThat(model.count(MemoryModel.ExtractRequest.class)).isZero();       // no model call wasted
        assertThat(m.store.log.rows.values().stream().filter(e -> e.body().contains("plage")).findFirst().orElseThrow()
                .consolidatedAt()).isNull();                                   // waits for the embedding model
    }

    @Test
    void anUnreadableSummaryIsSkippedForSomeNightsAndTheRestGoesOn() {
        for (int d = 3; d <= 5; d++) {
            m.say(at(10, d, 11), "heard", "Jour " + d);
        }
        var normal = model.summarize;
        model.summarize = r -> {
            if (r.first().equals(LocalDate.of(2026, 10, 4))) {
                throw new MemoryModel.BadOutput("no \"summary\"");
            }
            return normal.apply(r);
        };
        ConsolidateMemory.Report r = worker.run(ConsolidateMemory.Pass.NIGHTLY);
        assertThat(r.outcome()).isEqualTo("done");
        assertThat(r.counts()).containsEntry("days", 2).containsKey("archived").containsKey("retention_deleted");
        assertThat(m.store.episodes.rows.keySet()).contains("DAY 2026-10-03", "DAY 2026-10-05");
        // tried again the next night (and it works now)
        model.summarize = normal;
        assertThat(m.nightly.dayEpisodes(target, () -> false)).isZero();       // not due yet tonight
        m.clock.advance(24 * 3600);
        m.say(at(10, 6, 11), "heard", "Jour 6");
        assertThat(m.nightly.dayEpisodes(target, () -> false)).isEqualTo(2);    // 4 October again, and 6 October
        assertThat(m.store.episodes.rows.keySet()).contains("DAY 2026-10-04", "DAY 2026-10-06");
    }

    @Test
    void daysAfterALongGapAreStillSummarised() {
        m.say(at(6, 1, 11), "heard", "Premier jour");
        for (int d = 1; d <= 5; d++) {
            m.say(at(10, d, 11), "heard", "Jour " + d);
        }
        for (int night = 0; night < 3; night++) {
            m.nightly.dayEpisodes(target, () -> false);
        }
        assertThat(m.store.episodes.list(EpisodeLevel.DAY, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)))
                .extracting(e -> e.day().toString())
                .containsExactly("2026-06-01", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05");
    }

    @Test
    void daysWithOnlySensitiveEventsMoveTheCursorOn() {
        for (int d = 1; d <= 60; d++) {
            m.brain(at(8, 1, 9).plusSeconds(d * 86400L), "vitals_acquired", "Breathing 14/min", Sensitivity.SENSITIVE);
        }
        m.say(at(10, 5, 11), "heard", "Enfin quelque chose");
        m.nightly.dayEpisodes(target, () -> false);
        m.nightly.dayEpisodes(target, () -> false);
        assertThat(m.store.episodes.get(EpisodeLevel.DAY, LocalDate.of(2026, 10, 5))).isPresent();
    }

    // ------------------------------------------------------------------ the owner meanwhile

    @Test
    void aFactForgottenDuringItsReconciliationDoesNotComeBack() {
        m.say(at(10, 5, 11), "heard", "J'habite à Lyon");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 7));
        m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false);
        Fact lyon = m.store.facts.rows.values().iterator().next();

        m.say(at(10, 5, 18), "heard", "J'habite à Lyon, près du parc");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon near the park.", 7));
        model.reconcile = r -> {
            m.admin.forgetFact(lyon.id());       // the owner, in the app, while the model thinks
            return new MemoryModel.Decision("UPDATE", 1, "The owner lives in Lyon near the park.", "", MemoryModel.Usage.NONE);
        };
        Consolidator.Result res = m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false);
        assertThat(res.redo()).isTrue();
        assertThat(m.store.facts.rows).isEmpty();
        MemoryEvent second = m.store.log.rows.values().stream().filter(e -> e.body().contains("parc")).findFirst().orElseThrow();
        assertThat(second.consolidatedAt()).isNull();                          // decided again by the next pass
    }

    @Test
    void anOwnersCorrectionDuringAPassIsNotOverwritten() {
        m.say(at(10, 5, 11), "heard", "J'habite à Lyon");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 7));
        m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false);
        Fact lyon = m.store.facts.rows.values().iterator().next();

        m.say(at(10, 5, 18), "heard", "En fait j'habite à Villeurbanne");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Villeurbanne.", 7));
        List<Fact> ownerVersion = new ArrayList<>();
        model.reconcile = r -> {
            ownerVersion.add(m.admin.edit(lyon.id(), new marvin.host.application.memory.port.in.ManageFacts.Edit(
                    "The owner lives in Lyon 3e.", null, null, null, null)));
            return new MemoryModel.Decision("UPDATE", 1, "", "", MemoryModel.Usage.NONE);
        };
        assertThat(m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false).redo()).isTrue();
        assertThat(m.store.facts.currentStatements(NOW)).containsExactly("The owner lives in Lyon 3e.");

        // the next pass decides from the owner's version: an owner-written fact is never reworded
        model.reconcile = r -> new MemoryModel.Decision("UPDATE", 1, "", "", MemoryModel.Usage.NONE);
        assertThat(m.consolidator.process(m.store.log.unconsolidated(10), target, () -> false).redo()).isFalse();
        assertThat(m.store.facts.currentStatements(NOW)).containsExactly("The owner lives in Lyon 3e.");
        assertThat(m.store.facts.rows.get(ownerVersion.getFirst().id()).current(NOW)).isTrue();
    }

    @Test
    void forgettingEverythingStopsThePassAndNothingComesBack() {
        m.say(at(10, 5, 11), "heard", "J'habite à Lyon");
        model.extract = r -> {
            m.admin.forgetEverything();
            return List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 7));
        };
        ConsolidateMemory.Report r = worker.run(ConsolidateMemory.Pass.NIGHTLY);
        assertThat(r.outcome()).isEqualTo("yielded");
        assertThat(m.store.facts.rows).isEmpty();
        assertThat(m.store.profiles.rows).isEmpty();
        assertThat(m.store.episodes.rows).isEmpty();
        assertThat(warmUps.get()).isZero();
    }

    @Test
    void aPassThatYieldsToTheVoiceDoesNotWarmItUp() {
        m.say(at(10, 5, 11), "heard", "J'habite à Lyon");
        model.extract = r -> {
            busy = true;                    // the owner speaks
            return List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 7));
        };
        m.voiceModel = "m";
        ConsolidateMemory.Report r = worker.run(ConsolidateMemory.Pass.IDLE);
        assertThat(r.outcome()).isEqualTo("yielded");
        assertThat(warmUps.get()).isZero();
    }

    @Test
    void theReportCountsTheModelsWork() {
        m.say(at(10, 5, 11), "heard", "J'habite à Lyon");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 7));
        ConsolidateMemory.Report r = worker.run(ConsolidateMemory.Pass.IDLE);
        assertThat(r.counts()).containsKeys("prompt_tokens", "model_ms");
        assertThat(Map.copyOf(r.counts())).containsEntry("added", 1);
    }
}
