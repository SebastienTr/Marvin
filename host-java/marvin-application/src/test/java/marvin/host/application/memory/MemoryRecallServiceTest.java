// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.in.ConfirmForgetting;
import marvin.host.application.memory.port.in.RecallMemory;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.RetrievalScoring;
import marvin.host.domain.memory.Sensitivity;

/**
 * The read path on in-memory stores with word embeddings: what the assembler is offered for a question, the profile
 * and its warm-up, the {@code recall} tool, and forgetting after a confirmation.
 */
class MemoryRecallServiceTest {
    static final Instant T = Instant.parse("2026-09-29T10:00:00Z");
    /** Word embeddings are coarser than bge-m3: a lower floor. */
    static final RetrievalScoring SCORING = new RetrievalScoring(1.0, 0.5, 0.7, 0.995, 0.3);
    final MemoryFixture m = new MemoryFixture(T, new FakeMemoryModel());
    final CachedProfiles profiles = new CachedProfiles(m.store.profiles);
    final MemoryAdminService admin = new MemoryAdminService(m.store.log, m.store.facts, m.store.episodes, profiles,
            m.embeddings, m.config, m.days, m.clock, UUID::randomUUID);
    final AtomicInteger warmUps = new AtomicInteger();
    final MemoryRecallService recall = new MemoryRecallService(m.store.facts, m.store.log, m.store.episodes, profiles,
            m.embeddings, m.days, m.clock, SCORING, warmUps::incrementAndGet);
    final ForgetConfirmations forgetting = new ForgetConfirmations(m.store.facts, admin, m.embeddings, m.clock, UUID::randomUUID);

    {
        admin.addListener(recall);
    }

    Fact ended(Fact f, Instant validTo) {
        m.store.facts.apply(new Reconciliation.Plan(List.of(), List.of(new Reconciliation.Expiry(f.id(), T, validTo, null)),
                Map.of(), new Operation.Noop(f.id())), Map.of());
        return m.store.facts.get(f.id()).orElseThrow();
    }

    static List<String> texts(List<RecallMemory.Line> lines) {
        return lines.stream().map(RecallMemory.Line::text).toList();
    }

    // ------------------------------------------------------------------ automatic retrieval

    @Test
    void aQuestionGetsItsCurrentRelevantFactsBestFirst() {
        Fact sister = admin.remember("The owner's sister Claire lives in Lyon.", "owner", null);
        admin.remember("The owner's sister Claire works as a nurse in Lyon hospital.", "owner", null);
        Fact old = admin.remember("The owner's sister Claire lived in Paris.", "owner", null);
        ended(old, T.minusSeconds(3600));
        Fact archived = admin.remember("The owner's sister Claire loves Lyon markets.", "owner", null);
        admin.archive(archived.id(), true);
        admin.remember("The owner drinks green tea every morning.", "owner", null);

        RecallMemory.Recollection r = recall.recollect("Where does my sister Claire live, Lyon?", RecallMemory.Audience.OWNER);
        assertThat(r.problem()).isEmpty();
        assertThat(texts(r.facts())).contains("The owner's sister Claire lives in Lyon.")
                .doesNotContain("The owner's sister Claire lived in Paris.", "The owner's sister Claire loves Lyon markets.",
                        "The owner drinks green tea every morning.");
        assertThat(r.facts().getFirst().key()).isEqualTo(sister.id().toString());
        assertThat(r.facts()).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
        assertThat(r.facts().getFirst().detail()).containsKeys("similarity", "relevance", "recency", "importance", "learned");
        assertThat(r.embedSeconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void sensitiveFactsStayOutWhenSomeoneElseIsThere() {
        admin.remember("The owner takes medication for migraines.", "owner", Sensitivity.SENSITIVE);
        RecallMemory.Recollection alone = recall.recollect("migraines medication", RecallMemory.Audience.OWNER);
        RecallMemory.Recollection guest = recall.recollect("migraines medication", new RecallMemory.Audience(true, false));
        RecallMemory.Recollection cloud = recall.recollect("migraines medication", new RecallMemory.Audience(false, true));
        assertThat(alone.facts()).hasSize(1);
        assertThat(guest.facts()).isEmpty();
        assertThat(cloud.facts()).isEmpty();
    }

    @Test
    void factsSentArMarkedUsed() {
        Fact f = admin.remember("The owner plays the cello.", "owner", null);
        recall.used(List.of(f.id()));
        recall.drain();
        assertThat(m.store.facts.get(f.id()).orElseThrow().useCount()).isEqualTo(1);
        assertThat(m.store.facts.get(f.id()).orElseThrow().lastUsedAt()).isEqualTo(T);
    }

    @Test
    void withoutTheEmbeddingModelTheQuestionGoesOnWithoutFacts() {
        admin.remember("The owner plays the cello.", "owner", null);
        m.embedder.failure = new Embedder.Unavailable("model \"bge-m3\" not found", "ollama pull bge-m3");
        RecallMemory.Recollection r = recall.recollect("cello", RecallMemory.Audience.OWNER);
        assertThat(r.facts()).isEmpty();
        assertThat(r.problem()).contains("bge-m3");
    }

    @Test
    void todaysAndYesterdaysSummariesAreOfferedSentenceBySentence() {
        LocalDate today = LocalDate.of(2026, 9, 29);
        put(today.minusDays(1), "The owner worked from home. He talked about a trip to Oslo. He went to bed late.");
        put(today.minusDays(3), "An older day that is not offered.");
        List<RecallMemory.Line> gist = recall.recollect("When is the trip to Oslo?", RecallMemory.Audience.OWNER).gist();
        assertThat(texts(gist)).containsExactly("Yesterday: The owner worked from home.", "Yesterday: He talked about a trip to Oslo.",
                "Yesterday: He went to bed late.");
        RecallMemory.Line oslo = gist.get(1);
        assertThat(oslo.score()).isGreaterThan(gist.get(0).score()).isGreaterThan(gist.get(2).score());
    }

    void put(LocalDate day, String summary) {
        Instant start = day.atStartOfDay(MemoryFixture.ZONE).toInstant();
        m.store.episodes.put(new Episode(0, EpisodeLevel.DAY, day, start, day.plusDays(1).atStartOfDay(MemoryFixture.ZONE).toInstant(),
                summary, false, T, 10), null);
    }

    // ------------------------------------------------------------------ profile

    @Test
    void theProfileIsReadOnceAndAnOwnerEditWarmsTheVoiceUp() {
        assertThat(recall.profile()).isEmpty();
        admin.editProfile("The owner is called Sam.", List.of());
        assertThat(recall.profile().orElseThrow().content()).isEqualTo("The owner is called Sam.");
        assertThat(warmUps.get()).isEqualTo(1);
        int reads = m.store.profiles.rows.size();
        for (int i = 0; i < 100; i++) {
            recall.profile();
        }
        assertThat(m.store.profiles.rows).hasSize(reads);
        admin.remember("The owner plays the cello.", "owner", null);          // facts change, the profile does not
        assertThat(warmUps.get()).isEqualTo(1);
        admin.editProfile("The owner is called Sam.\nSam plays the cello.", List.of());
        assertThat(warmUps.get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ recall

    @Test
    @SuppressWarnings("unchecked")
    void recallFindsPastFactsWithTheirValiditySummariesAndWhatWasSaid() {
        Fact old = admin.remember("The owner lived in Lyon.", "owner", null);
        m.clock.advance(3 * 86_400);
        ended(old, T.plusSeconds(2 * 86_400));
        admin.remember("The owner lives in Lille.", "owner", null);
        put(LocalDate.of(2026, 9, 28), "The owner unpacked boxes in Lille. He called the movers.");
        m.say(T.minusSeconds(86_400), "heard", "Les déménageurs arrivent à Lille demain");
        m.say(T.minusSeconds(86_400 * 40), "heard", "Lille, c'est loin de Lyon ?");
        Map<String, Object> out = recall.recall("Lyon Lille", "any", RecallMemory.Audience.OWNER);
        List<Map<String, Object>> facts = (List<Map<String, Object>>) out.get("facts");
        assertThat(facts).extracting(f -> f.get("statement")).contains("The owner lived in Lyon.", "The owner lives in Lille.");
        Map<String, Object> past = facts.stream().filter(f -> f.get("statement").equals("The owner lived in Lyon.")).findFirst()
                .orElseThrow();
        assertThat(past).containsEntry("status", "past");
        assertThat((String) past.get("when")).isEqualTo("from 29 September 2026 to 1 October 2026");
        assertThat((String) past.get("source")).isEqualTo("the owner told you on 29 September 2026");
        assertThat((List<Map<String, Object>>) out.get("days")).singleElement()
                .satisfies(d -> assertThat(d).containsEntry("day", "28 September 2026"));
        assertThat((List<Map<String, Object>>) out.get("said")).hasSize(2).first()
                .satisfies(s -> assertThat(s).containsEntry("who", "the person").containsEntry("date", "20 August 2026"));

        Map<String, Object> none = recall.recall("submarine", "last_week", RecallMemory.Audience.OWNER);
        assertThat(none).containsEntry("note", "nothing in memory matches: say you do not remember");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recallKeepsToThePeriodAsked() {
        put(LocalDate.of(2026, 9, 28), "The owner went sailing.");
        put(LocalDate.of(2026, 9, 10), "The owner went sailing again.");
        Map<String, Object> out = recall.recall("sailing", "yesterday", RecallMemory.Audience.OWNER);
        assertThat((List<Map<String, Object>>) out.get("days")).extracting(d -> d.get("summary"))
                .containsExactly("The owner went sailing.");
    }

    // ------------------------------------------------------------------ forgetting

    @Test
    void forgettingByVoiceNeedsALaterTurnAndGoesForReal() {
        Fact f = admin.remember("The owner's neighbour is called Paul.", "owner", null);
        admin.remember("The owner likes jazz.", "owner", null);
        ConfirmForgetting.Proposal p = forgetting.proposeMatching("neighbour Paul", "voice", 4, RecallMemory.Audience.OWNER);
        assertThat(p.facts()).extracting(Fact::id).containsExactly(f.id());
        assertThat(p.code()).hasSize(6);
        assertThat(forgetting.pending()).hasSize(1);
        ConfirmForgetting.Outcome same = forgetting.confirm(p.code(), 4, null);
        assertThat(same.done()).isFalse();
        assertThat(same.reason()).contains("has not confirmed yet");
        assertThat(m.store.facts.get(f.id())).isPresent();
        ConfirmForgetting.Outcome later = forgetting.confirm(p.code().toLowerCase(), 5, null);
        assertThat(later.done()).isTrue();
        assertThat(later.forgotten().facts()).isEqualTo(1);
        assertThat(m.store.facts.get(f.id())).isEmpty();
        assertThat(recall.recollect("neighbour Paul", RecallMemory.Audience.OWNER).facts()).isEmpty();
        assertThat(forgetting.confirm(p.code(), 6, null).done()).as("a code is used once").isFalse();
    }

    @Test
    void theAppConfirmsAVoiceProposalOrItsOwnAndCodesExpire() {
        Fact f = admin.remember("The owner's neighbour is called Paul.", "owner", null);
        ConfirmForgetting.Proposal voice = forgetting.proposeMatching("neighbour Paul", "voice", 4, RecallMemory.Audience.OWNER);
        assertThat(forgetting.confirm(voice.code(), -1, null).done()).isTrue();

        Fact g = admin.remember("The owner likes jazz.", "owner", null);
        ConfirmForgetting.Proposal app = forgetting.proposeFact(g.id());
        m.clock.advance(ForgetConfirmations.TTL.toSeconds() + 1);
        assertThat(forgetting.pending()).isEmpty();
        assertThat(forgetting.confirm(app.code(), -1, null).reason()).contains("expired");
        ConfirmForgetting.Proposal again = forgetting.proposeFact(g.id());
        assertThat(forgetting.cancel(again.code())).isTrue();
        assertThat(m.store.facts.get(g.id())).isPresent();
        assertThat(m.store.facts.get(f.id())).isEmpty();
        assertThat(forgetting.proposeMatching("submarine", "voice", 1, RecallMemory.Audience.OWNER).facts()).isEmpty();
        assertThat(forgetting.pending()).as("nothing matched: nothing to confirm").isEmpty();
    }

    @Test
    void forgettingEverythingNeedsTheCodeAndThePhrase() {
        admin.remember("The owner likes jazz.", "owner", null);
        ConfirmForgetting.Proposal p = forgetting.proposeEverything();
        assertThat(forgetting.confirm(p.code(), -1, "yes").done()).isFalse();
        assertThat(m.store.facts.rows).isNotEmpty();
        ConfirmForgetting.Outcome o = forgetting.confirm(p.code(), -1, "Forget everything");
        assertThat(o.done()).isTrue();
        assertThat(m.store.facts.rows).isEmpty();
        assertThat(m.store.log.rows).isEmpty();
    }

    @Test
    void theOwnerReviewsSuggestedFacts() {
        Fact owner = admin.remember("The owner likes jazz.", "owner", null);
        assertThat(owner.reviewed()).as("the owner's own facts are reviewed").isTrue();
        Fact extracted = new Fact(UUID.randomUUID(), "owner", "The owner has a bike.", marvin.host.domain.memory.FactKind.STATE, 4,
                0.7, Sensitivity.NORMAL, null, null, T, null, null, null, 0, false, false,
                marvin.host.domain.memory.FactOrigin.EXTRACTED, "m", List.of());
        m.store.facts.apply(new Reconciliation.Plan(List.of(extracted), List.of(), Map.of(), new Operation.Add()), Map.of());
        var suggested = new marvin.host.application.memory.port.out.FactStore.Query("", "", "", "current", null, false, null,
                false, T, 10, 0);
        assertThat(admin.list(suggested).facts()).extracting(Fact::id).containsExactly(extracted.id());
        admin.review(List.of(extracted.id()), true);
        assertThat(admin.list(suggested).facts()).isEmpty();
        assertThat(m.store.facts.get(extracted.id()).orElseThrow().reviewedAt()).isEqualTo(T);
    }

    @Test
    void everyOpenAppHearsOfAProposalMadeInTheApp() {
        List<Object> heard = new java.util.ArrayList<>();
        forgetting.addListener((kind, payload) -> heard.add(kind + " " + payload.get("pending")));
        Fact g = admin.remember("The owner likes jazz.", "owner", null);
        ConfirmForgetting.Proposal p = forgetting.proposeFact(g.id());
        assertThat(heard).containsExactly("forget 1");
        forgetting.cancel(p.code());
        forgetting.proposeEverything();
        assertThat(heard).containsExactly("forget 1", "forget 0", "forget 1");
    }
}
