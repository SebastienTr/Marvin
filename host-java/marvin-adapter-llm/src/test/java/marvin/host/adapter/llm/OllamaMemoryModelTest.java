// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.FactOrigin;
import marvin.host.domain.memory.Sensitivity;

/** Memory's model jobs and embeddings against a stand-in Ollama. */
class OllamaMemoryModelTest {
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    final StubOllama stub;
    final OllamaMemoryModel model = new OllamaMemoryModel();
    final MemoryModel.Target target;

    OllamaMemoryModelTest() throws Exception {
        stub = new StubOllama();
        target = new MemoryModel.Target(stub.url(), "qwen3:4b-instruct");
    }

    @AfterEach
    void close() {
        stub.close();
    }

    static MemoryModel.ExtractRequest request() {
        ZonedDateTime t = ZonedDateTime.of(2026, 9, 28, 14, 3, 0, 0, PARIS);
        return new MemoryModel.ExtractRequest(List.of(new MemoryModel.Line(t, "Owner", "Je pars à Oslo mardi prochain"),
                new MemoryModel.Line(t.plusMinutes(1), "Marvin", "Bon voyage !")), "The owner lives in Lyon.", t.plusMinutes(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractionAsksForTheSchemaWithTheVoicesContextSizeAndReadsTheFacts() {
        stub.chat = body -> StubOllama.json(Map.of("facts", List.of(Map.of("subject", "owner", "statement", "The owner flies to Oslo.",
                "kind", "plan", "valid_from", "2026-10-06", "valid_to", "", "importance", 6, "sensitivity", "normal", "confidence", 0.9))));
        MemoryModel.Extraction e = model.extract(target, request(), () -> false);
        assertThat(e.facts()).singleElement().satisfies(f -> {
            assertThat(f.statement()).isEqualTo("The owner flies to Oslo.");
            assertThat(f.kind()).isEqualTo("plan");
            assertThat(f.validFrom()).isEqualTo("2026-10-06");
            assertThat(f.importance().intValue()).isEqualTo(6);
            assertThat(f.confidence().doubleValue()).isEqualTo(0.9);
        });
        assertThat(e.usage().promptTokens()).isPositive();              // the stub counts what a cache would not cover
        Map<String, Object> body = stub.requests.getFirst();
        assertThat(body).containsEntry("model", "qwen3:4b-instruct").containsEntry("stream", true).containsEntry("think", false)
                .containsEntry("keep_alive", "30m");
        assertThat((Map<String, Object>) body.get("options")).containsEntry("num_ctx", 16384L).containsEntry("temperature", 0.0);
        Map<String, Object> format = (Map<String, Object>) body.get("format");
        assertThat(format).containsEntry("required", List.of("facts"));
        String prompt = StubOllama.prompt(body);
        assertThat(prompt).contains("What Marvin already knows about the owner").contains("The owner lives in Lyon.")
                .contains("2026-09-28 Mon 14:03 Owner: Je pars à Oslo mardi prochain")
                .contains("Now: 2026-09-28 Mon 14:04.").contains("Europe/Paris").doesNotContain("{{");
    }

    @Test
    void reconciliationNumbersTheSimilarFacts() {
        stub.chat = body -> StubOllama.json(Map.of("operation", "INVALIDATE", "target", 1, "statement", "", "valid_to", "2026-09-01"));
        Fact lyon = new Fact(UUID.randomUUID(), "owner", "The owner lives in Lyon.", FactKind.BIOGRAPHICAL, 9, 0.9,
                Sensitivity.NORMAL, Instant.parse("2024-03-01T00:00:00Z"), null, Instant.EPOCH, null, null, null, 0, false, false,
                FactOrigin.EXTRACTED, "", List.of());
        FactCandidate c = new FactCandidate("owner", "The owner lives in Lille.", FactKind.BIOGRAPHICAL, null, null, 9,
                Sensitivity.NORMAL, 0.9);
        MemoryModel.Decision d = model.reconcile(target, new MemoryModel.ReconcileRequest(c, List.of(lyon),
                ZonedDateTime.of(2026, 9, 28, 14, 0, 0, 0, PARIS)), () -> false);
        assertThat(d.operation()).isEqualTo("INVALIDATE");
        assertThat(d.target()).isEqualTo(1);
        assertThat(d.validTo()).isEqualTo("2026-09-01");
        assertThat(StubOllama.prompt(stub.requests.getFirst())).contains("Candidate, said on 2026-09-28:\n[owner] The owner lives in Lille.")
                .contains("1. [owner] The owner lives in Lyon. (since 2024-03-01)");
        stub.chat = body -> StubOllama.json(Map.of("operation", "ADD", "target", 0, "statement", "", "valid_to", ""));
        assertThat(model.reconcile(target, new MemoryModel.ReconcileRequest(c, List.of(lyon),
                ZonedDateTime.of(2026, 9, 28, 14, 0, 0, 0, PARIS)), () -> false).target()).isNull();
    }

    @Test
    void summariesAndTheProfile() {
        stub.chat = body -> StubOllama.json(Map.of("summary", " A quiet Monday. "));
        MemoryModel.Text t = model.summarize(target, new MemoryModel.SummaryRequest(EpisodeLevel.DAY, LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 9, 28), List.of("09:00 Presence: You came in"), 200), () -> false);
        assertThat(t.text()).isEqualTo("A quiet Monday.");
        assertThat(StubOllama.prompt(stub.requests.getLast())).contains("Summarise the day of Monday 28 September 2026")
                .contains("at most 200 words").contains("09:00 Presence: You came in");
        stub.chat = body -> StubOllama.json(Map.of("lines", List.of("KEEP Call me Sam.", "The owner lives in Lille.", " ")));
        MemoryModel.Text p = model.rewriteProfile(target, new MemoryModel.ProfileRequest("Call me Sam.\nThe owner lives in Lyon.",
                List.of("Call me Sam."), List.of("The owner lives in Lille."), List.of("The owner lives in Lyon."), 500), () -> false);
        assertThat(p.text()).isEqualTo("Call me Sam.\nThe owner lives in Lille.");
        assertThat(StubOllama.prompt(stub.requests.getLast())).contains("KEEP Call me Sam.\nThe owner lives in Lyon.")
                .contains("Learned since:\n- The owner lives in Lille.").contains("at most 500 tokens");
    }

    @Test
    void failuresSayWhatToDo() {
        stub.chat = body -> StubOllama.text("I think the owner lives in Lyon.");
        assertThatThrownBy(() -> model.extract(target, request(), () -> false)).isInstanceOf(MemoryModel.BadOutput.class);
        stub.chat = body -> List.of("!404 {\"error\":\"model \\\"qwen3:27b\\\" not found, try pulling it first\"}");
        assertThatThrownBy(() -> model.extract(new MemoryModel.Target(stub.url(), "qwen3:27b"), request(), () -> false))
                .isInstanceOf(MemoryModel.Unavailable.class).hasMessageContaining("no model 'qwen3:27b'")
                .satisfies(e -> assertThat(((MemoryModel.Unavailable) e).fix()).isEqualTo("Run `ollama pull qwen3:27b`."));
        assertThatThrownBy(() -> model.extract(new MemoryModel.Target("http://127.0.0.1:9", "m"), request(), () -> false))
                .isInstanceOf(MemoryModel.Unavailable.class).hasMessageContaining("cannot reach Ollama");
    }

    @Test
    void aCallIsAbandonedAsSoonAsTheVoiceNeedsTheModel() {
        List<String> slow = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            slow.add("{\"message\":{\"role\":\"assistant\",\"content\":\" \"},\"done\":false}");
        }
        stub.delayMs = 20;
        stub.chat = body -> slow;
        AtomicInteger checks = new AtomicInteger();
        long start = System.nanoTime();
        assertThatThrownBy(() -> model.extract(target, request(), () -> checks.incrementAndGet() > 3))
                .isInstanceOf(MemoryModel.Cancelled.class);
        assertThat((System.nanoTime() - start) / 1e9).isLessThan(2.0);
    }

    @Test
    void aCallIsAbandonedEvenBeforeTheFirstChunk() throws InterruptedException {
        stub.chat = body -> StubOllama.json(Map.of("facts", List.of()));
        model.extract(target, request(), () -> false);  // the client is ready (not timed)
        stub.firstChunkDelayMs = 2000;                  // loading a large night model, reading a long prompt
        long start = System.nanoTime();
        assertThatThrownBy(() -> model.extract(target, request(), () -> (System.nanoTime() - start) / 1e6 > 100))
                .isInstanceOf(MemoryModel.Cancelled.class);
        assertThat((System.nanoTime() - start) / 1e6).isLessThan(300);
        for (int i = 0; i < 60 && stub.abandoned.get() == 0; i++) {
            Thread.sleep(50);
        }
        assertThat(stub.abandoned.get()).as("the connection was closed").isEqualTo(1);
    }

    @Test
    void embeddingsComeInBatchesAndAMissingModelSaysHowToGetIt() {
        OllamaEmbedder embedder = new OllamaEmbedder();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            texts.add("fact number " + i);
        }
        List<float[]> v = embedder.embed(stub.url(), "qwen3-embedding:8b", texts);
        assertThat(v).hasSize(40).allSatisfy(x -> assertThat(x).hasSize(1024));
        assertThat(stub.embedRequests).hasSize(2);
        assertThat(stub.embedRequests.getFirst()).containsEntry("model", "qwen3-embedding:8b").containsEntry("keep_alive", "30m");
        assertThat(v.get(3)).isEqualTo(StubOllama.embedding("fact number 3", 1024));
        assertThat(stub.embedRequests.getFirst()).doesNotContainKey("dimensions");
        embedder.embed(stub.url(), "qwen3-embedding:8b", List.of("x"), 1024);
        assertThat(stub.embedRequests.getLast()).containsEntry("dimensions", 1024L);
        assertThatThrownBy(() -> embedder.embed(stub.url(), "qwen3-embedding", List.of("x")))
                .isInstanceOf(Embedder.Unavailable.class).hasMessage("Ollama has no embedding model 'qwen3-embedding'")
                .satisfies(e -> assertThat(((Embedder.Unavailable) e).fix()).isEqualTo("Run `ollama pull qwen3-embedding`."));
        assertThatThrownBy(() -> embedder.embed("http://127.0.0.1:9", "qwen3-embedding:8b", List.of("x")))
                .isInstanceOf(Embedder.Unavailable.class).hasMessageContaining("cannot reach Ollama");
    }
}
