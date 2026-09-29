// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;
import marvin.host.domain.memory.MemoryEvent;

class ConsolidatorTest {
    static final Instant T = Instant.parse("2026-09-28T12:00:00Z");
    final FakeMemoryModel model = new FakeMemoryModel();
    final MemoryFixture m = new MemoryFixture(T.plusSeconds(3600), model);
    final MemoryModel.Target target = new MemoryModel.Target("http://x", "qwen3:4b-instruct");

    List<MemoryEvent> talk(String... lines) {
        return java.util.stream.IntStream.range(0, lines.length)
                .mapToObj(i -> m.say(T.plusSeconds(i * 10L), i % 2 == 0 ? "heard" : "reply", lines[i])).toList();
    }

    @Test
    void aNewFactIsAddedWithoutAskingTheModelToReconcile() {
        List<MemoryEvent> batch = talk("J'habite à Lyon depuis deux ans", "Lyon, belle ville !");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 9));
        Consolidator.Result r = m.consolidator.process(batch, target, () -> false);
        assertThat(r.added()).isEqualTo(1);
        assertThat(model.count(MemoryModel.ReconcileRequest.class)).isZero();
        Fact f = m.store.facts.rows.values().iterator().next();
        assertThat(f.sources()).containsExactlyElementsOf(batch.stream().map(MemoryEvent::id).toList());
        assertThat(f.extractedBy()).isEqualTo("fake/1 qwen3:4b-instruct");
        assertThat(m.store.log.unconsolidatedCount()).isZero();
        MemoryModel.ExtractRequest req = (MemoryModel.ExtractRequest) model.requests.getFirst();
        assertThat(req.lines()).extracting(MemoryModel.Line::who).containsExactly("Owner", "Marvin");
        assertThat(req.now().toInstant()).isEqualTo(T.plusSeconds(10));
        assertThat(req.now().getZone()).isEqualTo(MemoryFixture.ZONE);
    }

    @Test
    void aChangedWorldInvalidatesTheOldFact() {
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 9));
        m.consolidator.process(talk("J'habite à Lyon"), target, () -> false);
        Fact lyon = m.store.facts.rows.values().iterator().next();
        model.extract = r -> List.of(new FactCandidate.Raw("owner", "The owner lives in Lille.", "biographical", "2026-09-01", "",
                9, "normal", 0.9));
        model.reconcile = r -> {
            assertThat(r.similar()).extracting(Fact::id).containsExactly(lyon.id());
            return new MemoryModel.Decision("INVALIDATE", 1, "", "", MemoryModel.Usage.NONE);
        };
        Consolidator.Result r = m.consolidator.process(talk("J'ai déménagé à Lille"), target, () -> false);
        assertThat(r.invalidated()).isEqualTo(1);
        assertThat(m.store.facts.currentStatements(T.plusSeconds(7200))).containsExactly("The owner lives in Lille.");
        Fact ended = m.store.facts.rows.get(lyon.id());
        assertThat(ended.validTo()).isEqualTo(Instant.parse("2026-08-31T22:00:00Z"));
        assertThat(ended.expiredAt()).isNotNull();
    }

    @Test
    void candidatesOfOneBatchSeeEachOther() {
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner has a cat named Tofu.", 6),
                FakeMemoryModel.raw("owner", "The owner has a cat named Tofu.", 6),
                FakeMemoryModel.raw("owner", "The owner's cat Tofu is three years old.", 5));
        AtomicInteger asked = new AtomicInteger();
        model.reconcile = r -> {
            asked.incrementAndGet();
            assertThat(r.similar()).extracting(Fact::statement).contains("The owner has a cat named Tofu.");
            return new MemoryModel.Decision("UPDATE", 1, "The owner has a three-year-old cat named Tofu.", "", MemoryModel.Usage.NONE);
        };
        Consolidator.Result r = m.consolidator.process(talk("Mon chat Tofu a trois ans"), target, () -> false);
        assertThat(r.candidates()).isEqualTo(2);           // the duplicate is one candidate
        assertThat(asked.get()).isEqualTo(1);
        assertThat(m.store.facts.currentStatements(T.plusSeconds(7200))).containsExactly("The owner has a three-year-old cat named Tofu.");
        assertThat(m.store.facts.rows).hasSize(2);
    }

    @Test
    void secretsAreDroppedAndRedactedInTheLog() {
        List<MemoryEvent> batch = talk("le code de la porte, c'est 4812B", "Noté.");
        model.extract = r -> List.of(new FactCandidate.Raw("owner", "The door code is 4812B.", "state", "", "", 5, "secret", 1.0));
        Consolidator.Result r = m.consolidator.process(batch, target, () -> false);
        assertThat(r.dropped()).isEqualTo(1);
        assertThat(m.store.facts.rows).isEmpty();
        assertThat(m.store.log.rows.get(batch.getFirst().id()).body()).isEqualTo("le code de la porte, c'est [redacted]");
    }

    @Test
    void unreadableOutputIsRetriedOnceThenTheBatchIsSkipped() {
        AtomicInteger calls = new AtomicInteger();
        model.extract = r -> {
            calls.incrementAndGet();
            throw new MemoryModel.BadOutput("not JSON");
        };
        Consolidator.Result r = m.consolidator.process(talk("bla"), target, () -> false);
        assertThat(r.skipped()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(m.store.log.unconsolidatedCount()).isZero();
    }

    @Test
    void aBatchCutShortWritesNothing() {
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 9),
                FakeMemoryModel.raw("owner", "The owner lives in Lyon near the river.", 7));
        AtomicBoolean busy = new AtomicBoolean();
        model.reconcile = r -> {
            busy.set(true);                                  // the voice wakes up during the second candidate
            return FakeMemoryModel.decision("ADD", 0);
        };
        assertThatThrownBy(() -> m.consolidator.process(talk("J'habite à Lyon, près du Rhône"), target, busy::get))
                .isInstanceOf(MemoryModel.Cancelled.class);
        assertThat(m.store.facts.rows).isEmpty();
        assertThat(m.store.log.unconsolidatedCount()).isEqualTo(1);
    }

    @Test
    void aFactWithoutItsEmbeddingIsNeverWrittenHalfway() {
        m.embedder.failure = new marvin.host.application.memory.port.out.Embedder.Unavailable("no qwen3-embedding:0.6b", "Run `ollama pull qwen3-embedding:0.6b`.");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 9));
        assertThatThrownBy(() -> m.consolidator.process(talk("J'habite à Lyon"), target, () -> false))
                .hasMessage("no qwen3-embedding:0.6b");
        assertThat(m.embeddings.state()).isEqualTo("unavailable");
        assertThat(m.embeddings.fix()).contains("ollama pull qwen3-embedding:0.6b");
        assertThat(m.store.log.unconsolidatedCount()).isEqualTo(1);
    }
}
