// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.adapter.llm.StubOllama;
import marvin.host.adapter.persistence.JdbcEpisodeStore;
import marvin.host.adapter.persistence.JdbcFactStore;
import marvin.host.application.memory.port.in.RecallMemory;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.FactOrigin;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.JsonText;

/**
 * The memory API in the whole host, on PostgreSQL with pgvector and a stand-in Ollama (docs/memory.md, "The memory
 * API"): every route's status and shape, the access rules, forgetting with a confirmation (a fact, then everything
 * with the typed phrase), the per-source switches, the events on the app's stream, and how long retrieval takes with
 * thousands of facts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "marvin.mode=live", "marvin.robot.bind=127.0.0.1", "marvin.robot.port=0",
                "marvin.robot.calibration-file=no-such-calibration.json", "marvin.sidecar.voice=false",
                "marvin.sidecar.simulator=false", "marvin.import.sqlite=", "marvin.time-zone=Europe/Paris",
                "marvin.memory.worker=false", "marvin.data-dir=${java.io.tmpdir}/marvin-memory-api-it"})
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryApiIT {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));
    static final StubOllama OLLAMA;
    static final Path CONFIG;

    static {
        try {
            OLLAMA = new StubOllama();
            OLLAMA.chat = body -> StubOllama.text("Bonjour.");
            CONFIG = Files.createTempDirectory("marvin-memory-api-it");
            Files.writeString(CONFIG.resolve("voice.json"), "{\"ollama_host\": \"" + OLLAMA.url() + "\", \"internet\": false}\n");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("marvin.config-dir", CONFIG::toString);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcFactStore facts;
    @Autowired
    JdbcEpisodeStore episodes;
    @Autowired
    RecallMemory recall;
    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    record Res(int status, Object body, java.net.http.HttpHeaders headers) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return (Map<String, Object>) body;
        }
    }

    Res get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.headers().firstValue("Content-Type").orElse("").startsWith("application/json")
                ? JsonText.parse(r.body()) : r.body(), r.headers());
    }

    Res post(String path, Object body) throws Exception {
        return post(path, JsonText.write(body), "application/json", null);
    }

    Res post(String path, String body, String type, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            b.header("Origin", origin);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isEmpty() ? null : JsonText.parse(r.body()), r.headers());
    }

    static Instant now() {
        return Instant.ofEpochMilli(System.currentTimeMillis());
    }

    Fact extracted(String statement, Sensitivity s) {
        Fact f = new Fact(UUID.randomUUID(), "owner", statement, FactKind.STATE, 5, 0.7, s, null, null, now(), null, null, null, 0,
                false, false, FactOrigin.EXTRACTED, "test", List.of());
        facts.apply(new Reconciliation.Plan(List.of(f), List.of(), Map.of(), new Operation.Add()),
                Map.of(f.id(), StubOllama.embedding(f.embeddingText(), 1024)));
        return f;
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void theMemoryApi() throws Exception {
        // the app's stream, read in the background: memory's changes arrive as "memory" messages
        List<String> stream = new CopyOnWriteArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                HttpResponse<Stream<String>> s = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/stream"))
                        .build(), HttpResponse.BodyHandlers.ofLines());
                s.body().forEach(stream::add);
            } catch (Exception e) {
                // closed at the end
            }
        });
        Thread.sleep(300);

        // remember, from the app
        Res made = post("/api/memory/facts/remember", Map.of("statement", "The owner's bike is blue."));
        assertThat(made.status()).isEqualTo(201);
        Map<String, Object> bike = (Map<String, Object>) made.map().get("fact");
        assertThat(bike).containsEntry("origin", "owner").containsEntry("confidence", 1.0).containsEntry("reviewed", true)
                .containsEntry("status", "current").containsKeys("learned_at", "when", "sources", "use_count", "importance");
        String bikeId = (String) bike.get("id");
        Fact suggestion = extracted("The owner goes swimming on Sundays.", Sensitivity.NORMAL);
        extracted("The owner's blood pressure is high.", Sensitivity.SENSITIVE);

        // lists and filters
        Res all = get("/api/memory/facts");
        assertThat(all.status()).isEqualTo(200);
        assertThat((List<Object>) all.map().get("facts")).hasSize(3);
        assertThat((Map<String, Object>) all.map().get("counts")).containsEntry("all", 3L).containsEntry("suggested", 2L)
                .containsEntry("pinned", 0L).containsEntry("archived", 0L).containsEntry("past", 0L);
        assertThat((List<Map<String, Object>>) get("/api/memory/facts?filter=suggested").map().get("facts"))
                .extracting(f -> f.get("statement")).contains("The owner goes swimming on Sundays.");
        assertThat(get("/api/memory/facts?q=bike").map()).containsEntry("total", 1L);
        assertThat(get("/api/memory/facts?filter=sensitive").status()).isEqualTo(400);
        assertThat((List<Object>) get("/api/memory/facts?sensitivity=sensitive").map().get("facts")).hasSize(1);

        // detail with its sources, quoted
        Res detail = get("/api/memory/facts/" + bikeId);
        List<Map<String, Object>> sources = (List<Map<String, Object>>) detail.map().get("sources");
        assertThat(sources).singleElement().satisfies(s -> assertThat(s).containsEntry("source", "owner")
                .containsEntry("kind", "remember").containsEntry("text", "The owner's bike is blue.").containsKey("ts"));
        assertThat(get("/api/memory/facts/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(get("/api/memory/facts/not-a-uuid").status()).isEqualTo(404);

        // edit (a new version), pin, archive, review
        Res edited = post("/api/memory/facts/edit", Map.of("id", bikeId, "statement", "The owner's bike is dark blue.",
                "importance", 7));
        String newId = (String) ((Map<String, Object>) edited.map().get("fact")).get("id");
        assertThat(newId).isNotEqualTo(bikeId);
        assertThat((List<Object>) get("/api/memory/facts/" + newId).map().get("versions")).hasSize(2);
        assertThat(post("/api/memory/facts/edit", Map.of("id", bikeId, "importance", 11)).status()).isEqualTo(400);
        Res pinned = post("/api/memory/facts/pin", Map.of("id", newId, "pinned", true));
        assertThat((Map<String, Object>) pinned.map().get("fact")).containsEntry("pinned", true);
        assertThat(post("/api/memory/facts/archive", Map.of("id", suggestion.id().toString())).status()).isEqualTo(200);
        assertThat((Map<String, Object>) get("/api/memory/facts?filter=archived").map().get("counts")).containsEntry("archived", 1L)
                .containsEntry("pinned", 1L).containsEntry("past", 0L);     // an older wording is not "past"
        assertThat(post("/api/memory/facts/review", Map.of("ids", List.of(suggestion.id().toString()))).map())
                .containsEntry("reviewed", 1L);
        assertThat(post("/api/memory/facts/pin", Map.of("id", UUID.randomUUID().toString())).status()).isEqualTo(404);

        // forgetting a fact: a code first, then the confirmation
        Res proposal = post("/api/memory/facts/forget", Map.of("id", newId));
        assertThat(proposal.status()).isEqualTo(200);
        String code = (String) proposal.map().get("confirm");
        assertThat(code).hasSize(6);
        assertThat((List<Object>) get("/api/memory/forget").map().get("pending")).hasSize(1);
        assertThat(get("/api/memory/facts/" + newId).status()).as("nothing gone before the confirmation").isEqualTo(200);
        assertThat(post("/api/memory/forget/confirm", Map.of("confirm", "ZZZZZZ")).status()).isEqualTo(409);
        Res gone = post("/api/memory/facts/forget", Map.of("confirm", code));
        assertThat((Map<String, Object>) gone.map().get("forgotten")).containsEntry("facts", 2L);
        assertThat(get("/api/memory/facts/" + newId).status()).isEqualTo(404);
        assertThat(get("/api/memory/facts/" + bikeId).status()).isEqualTo(404);

        // the profile: edit, versions with diffs, restore
        assertThat(get("/api/memory/profile").map()).containsEntry("current", null);
        Res p1 = post("/api/memory/profile", Map.of("content", "The owner is called Sam."));
        long v1 = ((Number) ((Map<String, Object>) p1.map().get("current")).get("id")).longValue();
        post("/api/memory/profile", Map.of("content", "The owner is called Sam.\nSam swims on Sundays."));
        Res profile = get("/api/memory/profile");
        assertThat((Map<String, Object>) profile.map().get("current")).containsEntry("author", "owner")
                .containsEntry("content", "The owner is called Sam.\nSam swims on Sundays.");
        List<Map<String, Object>> versions = (List<Map<String, Object>>) profile.map().get("versions");
        assertThat(versions).hasSize(2);
        assertThat((List<Map<String, Object>>) versions.getFirst().get("diff")).contains(Map.of("op", "+", "text", "Sam swims on Sundays."));
        assertThat(recall.profile().orElseThrow().content()).endsWith("Sam swims on Sundays.");
        Res restored = post("/api/memory/profile/restore", Map.of("version", v1));
        assertThat((Map<String, Object>) restored.map().get("current")).containsEntry("content", "The owner is called Sam.");
        assertThat(recall.profile().orElseThrow().content()).as("the voice's cache follows").isEqualTo("The owner is called Sam.");
        assertThat(post("/api/memory/profile", Map.of("content", "x ".repeat(2000))).status()).isEqualTo(400);

        // episodes and the raw log
        java.time.LocalDate yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Paris")).minusDays(1);
        episodes.put(new Episode(0, EpisodeLevel.DAY, yesterday, yesterday.atStartOfDay(java.time.ZoneId.of("Europe/Paris")).toInstant(),
                yesterday.plusDays(1).atStartOfDay(java.time.ZoneId.of("Europe/Paris")).toInstant(), "The owner went swimming.",
                false, now(), 4), null);
        Res eps = get("/api/memory/episodes?level=day");
        assertThat((List<Map<String, Object>>) eps.map().get("episodes")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("summary", "The owner went swimming.").containsEntry("day", yesterday.toString()));
        assertThat(get("/api/memory/episodes?level=year").status()).isEqualTo(400);
        assertThat(get("/api/memory/episodes?level=week&from=2026-13-01").status()).isEqualTo(400);
        Res log = get("/api/memory/log?limit=2");
        assertThat((List<Object>) log.map().get("events")).hasSize(2);
        assertThat(log.map().get("next_before")).isNotNull();

        // settings: the per-source switches
        Res s = get("/api/memory/settings");
        assertThat((Map<String, Object>) s.map().get("settings")).containsEntry("collect_conversation", true);
        assertThat((List<Object>) s.map().get("sources")).contains("conversation", "brain");
        Res off = post("/api/memory/settings", Map.of("collect_brain", false));
        assertThat((Map<String, Object>) off.map().get("settings")).containsEntry("collect_brain", false);
        assertThat(post("/api/memory/settings", Map.of("night_hour", 25)).status()).isEqualTo(400);
        post("/api/memory/settings", Map.of("collect_brain", true));

        // worker and overview
        Res w = get("/api/memory/worker");
        assertThat(w.map()).containsKeys("state", "pending", "last_idle_at", "last_night_at", "next_night_at", "last", "models",
                "embeddings");
        assertThat((Map<String, Object>) w.map().get("models")).containsEntry("uses_voice_model", true);
        Res overview = get("/api/memory");
        assertThat(overview.map()).containsKeys("counts", "profile", "worker", "settings", "pending_forget");

        // export: a file, JSON or Markdown
        Res json = get("/api/memory/export");
        assertThat(json.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment; filename=\"marvin-memory-");
        assertThat(json.map()).containsKeys("event_log", "fact", "episode", "block_version", "settings");
        Res md = get("/api/memory/export?format=markdown");
        assertThat((String) md.body()).startsWith("# Marvin's memory").contains("The owner is called Sam.");
        assertThat(get("/api/memory/export?format=pdf").status()).isEqualTo(400);

        // the access rules of every other POST
        assertThat(post("/api/memory/facts/remember", "{\"statement\": \"x\"}", "text/plain", null).status()).isEqualTo(415);
        assertThat(post("/api/memory/facts/remember", "{\"statement\": \"x\"}", "application/json", "http://evil.example").status())
                .isEqualTo(403);
        assertThat(post("/api/memory/facts/delete", Map.of()).status()).isEqualTo(404);
        assertThat(post("/api/memory/facts/remember", "[1]", "application/json", null).status()).isEqualTo(400);
        assertThat(post("/api/memory/facts/remember", Map.of("statement", "My PIN is 4921")).status()).isEqualTo(400);

        // forgetting everything: a code, then the code and the typed phrase
        Res first = post("/api/memory/forget-everything", Map.of());
        assertThat(first.map()).containsEntry("phrase", "forget everything").containsEntry("everything", true);
        String everything = (String) first.map().get("confirm");
        assertThat(post("/api/memory/forget-everything", Map.of("confirm", everything, "phrase", "yes")).status()).isEqualTo(409);
        Res wiped = post("/api/memory/forget-everything", Map.of("confirm", everything, "phrase", "forget everything"));
        assertThat(wiped.status()).isEqualTo(200);
        assertThat((Map<String, Object>) get("/api/memory/facts").map().get("counts")).containsEntry("all", 0L);
        assertThat(get("/api/memory/profile").map()).containsEntry("current", null);
        assertThat(recall.profile()).isEmpty();

        Thread.sleep(300);
        reader.interrupt();
        List<String> memoryMessages = new ArrayList<>();
        for (int i = 0; i < stream.size(); i++) {
            if (stream.get(i).equals("event: memory") && i + 1 < stream.size()) {
                memoryMessages.add(stream.get(i + 1));
            }
        }
        assertThat(memoryMessages).anySatisfy(d -> assertThat(d).contains("\"kind\":\"changed\"").contains("\"what\":\"facts\""));
        assertThat(memoryMessages).anySatisfy(d -> assertThat(d).contains("\"kind\":\"forget\""));
        assertThat(memoryMessages).anySatisfy(d -> assertThat(d).contains("\"what\":\"all\""));
    }

    /**
     * Retrieval on the voice's path with thousands of facts: the question's embedding (from the stand-in Ollama, over
     * HTTP), the 30 nearest current facts from the HNSW index, and the scoring. Printed for the notes.
     */
    @Test
    @Order(2)
    void retrievalWithThousandsOfFactsTakesMilliseconds() {
        List<Fact> many = new ArrayList<>();
        Map<UUID, float[]> vectors = new LinkedHashMap<>();
        String[] topics = {"garden", "bike", "sister", "work", "music", "travel", "cooking", "sailing", "books", "health"};
        for (int i = 0; i < 3000; i++) {
            String statement = "The owner mentioned " + topics[i % topics.length] + " detail number " + i + " in passing.";
            Fact f = new Fact(UUID.randomUUID(), "owner", statement, FactKind.STATE, 1 + i % 10, 0.8, Sensitivity.NORMAL, null, null,
                    now().minusSeconds(i * 600L), null, null, null, 0, i % 7 == 0, false, FactOrigin.EXTRACTED, "test", List.of());
            many.add(f);
            vectors.put(f.id(), StubOllama.embedding(f.embeddingText(), 1024));
        }
        facts.apply(new Reconciliation.Plan(many, List.of(), Map.of(), new Operation.Add()), vectors);
        jdbc.sql("ANALYZE memory.fact").update();          // what autovacuum does after a large change
        List<Double> whole = new ArrayList<>();
        List<Double> search = new ArrayList<>();
        List<Double> embed = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long t0 = System.nanoTime();
            RecallMemory.Recollection r = recall.recollect("Tell me about the sailing detail number passing " + i,
                    RecallMemory.Audience.OWNER);
            double s = (System.nanoTime() - t0) / 1e9;
            if (i >= 10) {
                whole.add(s);
                search.add(r.searchSeconds());
                embed.add(r.embedSeconds());
            }
            assertThat(r.problem()).isEmpty();
            assertThat(r.facts()).isNotEmpty().allSatisfy(l -> assertThat(l.text()).contains("sailing"));
        }
        whole.sort(Double::compare);
        search.sort(Double::compare);
        embed.sort(Double::compare);
        System.out.printf("retrieval with 3000 facts (median of 30): %.1f ms in all, embedding %.1f ms, search and scoring %.1f ms%n",
                whole.get(15) * 1000, embed.get(15) * 1000, search.get(15) * 1000);
        assertThat(whole.get(15)).isLessThan(0.3);
        FactStore.Filter f = new FactStore.Filter(now(), true, false, Sensitivity.SENSITIVE);

        assertThat(facts.nearest(StubOllama.embedding("sailing", 1024), 30, f)).as("30 current, not archived").hasSize(30)
                .allSatisfy(x -> assertThat(x.fact().archived()).isFalse());
    }
}
