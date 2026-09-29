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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.adapter.llm.StubOllama;
import marvin.host.adapter.persistence.ContextMigrations;
import marvin.host.application.conversation.port.in.ConversationHistory;
import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.in.ForgetMemory;
import marvin.host.application.memory.port.in.ManageFacts;
import marvin.host.application.memory.port.in.MemoryHealth;
import marvin.host.application.memory.port.in.RecordMemory;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.shared.JsonText;

/**
 * Memory's write path in the whole host, on PostgreSQL with pgvector and a stand-in Ollama: the conversation and
 * presence history kept before memory existed are backfilled once, new conversation lines reach the log as they are
 * kept, an idle pass extracts facts with their sources, a nightly pass writes the day and the profile, the health
 * report says how memory searches, and a forgotten fact is gone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "marvin.mode=live", "marvin.robot.bind=127.0.0.1", "marvin.robot.port=0",
                "marvin.robot.calibration-file=no-such-calibration.json", "marvin.sidecar.voice=false",
                "marvin.sidecar.simulator=false", "marvin.import.sqlite=", "marvin.time-zone=Europe/Paris",
                "marvin.data-dir=${java.io.tmpdir}/marvin-memory-it"})
@Testcontainers(disabledWithoutDocker = true)
class MemoryEndToEndIT {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));
    static final StubOllama OLLAMA;
    static final Path CONFIG;
    static final double YESTERDAY = System.currentTimeMillis() / 1000.0 - 86_400;

    static {
        try {
            OLLAMA = new StubOllama();
            OLLAMA.chat = MemoryEndToEndIT::answer;
            CONFIG = Files.createTempDirectory("marvin-memory-it");
            Files.writeString(CONFIG.resolve("voice.json"), "{\"ollama_host\": \"" + OLLAMA.url() + "\", \"internet\": false}\n");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Structured answers, by what the request asks for. */
    @SuppressWarnings("unchecked")
    static List<String> answer(Map<String, Object> body) {
        Map<String, Object> props = body.get("format") instanceof Map<?, ?> f ? (Map<String, Object>) f.get("properties") : Map.of();
        String prompt = StubOllama.prompt(body);
        if (props.containsKey("facts")) {
            if (prompt.contains("J'habite à Lyon")) {
                return StubOllama.json(Map.of("facts", List.of(fact("The owner lives in Lyon.", 9))));
            }
            if (prompt.contains("Tofu")) {
                return StubOllama.json(Map.of("facts", List.of(fact("The owner has a cat named Tofu.", 6))));
            }
            return StubOllama.json(Map.of("facts", List.of()));
        }
        if (props.containsKey("operation")) {
            return StubOllama.json(Map.of("operation", "ADD", "target", 0, "statement", "", "valid_to", ""));
        }
        if (props.containsKey("summary")) {
            return StubOllama.json(Map.of("summary", "The owner came in and talked about Lyon."));
        }
        if (props.containsKey("lines")) {
            return StubOllama.json(Map.of("lines", List.of("The owner lives in Lyon.", "The owner has a cat named Tofu.")));
        }
        return StubOllama.text("Bonjour.");
    }

    static Map<String, Object> fact(String statement, int importance) {
        return Map.of("subject", "owner", "statement", statement, "kind", "biographical", "valid_from", "", "valid_to", "",
                "importance", importance, "sensitivity", "normal", "confidence", 0.9);
    }

    /** The history a host kept before memory existed, written before the host starts. */
    @DynamicPropertySource
    static void seed(DynamicPropertyRegistry r) throws Exception {
        postgres.start();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        new ContextMigrations(ds, "live").afterPropertiesSet();
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO conversation.entry (id, ts, kind, text, data) VALUES (?, ?, 'heard', ?, '{\"language\":\"fr\"}'), "
                        + "(?, ?, 'reply', ?, '{\"language\":\"fr\"}'), (?, ?, 'note', 'Voice on', '{}')")
                .params(1000L, YESTERDAY, "J'habite à Lyon depuis deux ans", 1001L, YESTERDAY + 2, "Lyon, belle ville.", 1002L,
                        YESTERDAY + 3)
                .update();
        jdbc.sql("INSERT INTO presence.event (ts, kind, detail, data) VALUES (?, 'arrived', '1.2 m away', '{\"distance_m\":1.2}'), "
                        + "(?, 'host_started', '', '{}'), (?, 'vitals_acquired', '', '{\"breath_rate\":14.0,\"heart_rate\":61.0}')")
                .params(YESTERDAY - 60, YESTERDAY - 120, YESTERDAY + 60).update();
        r.add("marvin.config-dir", CONFIG::toString);
    }

    @LocalServerPort
    int port;
    @Autowired
    RecordMemory memoryLog;
    @Autowired
    ConversationHistory conversation;
    @Autowired
    ConsolidateMemory worker;
    @Autowired
    ManageFacts facts;
    @Autowired
    ForgetMemory forget;
    @Autowired
    MemoryHealth health;
    @Autowired
    JdbcClient jdbc;

    long events() {
        return jdbc.sql("SELECT count(*) FROM memory.event_log").query(Long.class).single();
    }

    FactStore.Query current() {
        return new FactStore.Query("", "", "", "current", null, null, null, EventFeeds.instant(System.currentTimeMillis() / 1000.0 + 5),
                50, 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWritePath() throws Exception {
        // the backfill: two lines said (not the note), the arrival and the vital signs (not the host's own marker)
        for (int i = 0; i < 100 && events() < 4; i++) {
            Thread.sleep(100);
        }
        assertThat(jdbc.sql("SELECT source || ':' || kind || ':' || sensitivity FROM memory.event_log ORDER BY ts").query(String.class)
                .list()).containsExactly("brain:arrived:normal", "conversation:heard:normal", "conversation:reply:normal",
                "brain:vitals_acquired:sensitive");

        // a new line of the conversation reaches the log as it is kept
        double now = System.currentTimeMillis() / 1000.0;
        conversation.add(new ConversationEntry(conversation.maxId() + 1, now, "heard", "Mon chat s'appelle Tofu", Map.of("language", "fr")));
        assertThat(memoryLog.flush(5000)).isTrue();
        assertThat(events()).isEqualTo(5);

        ConsolidateMemory.Report idle = worker.consolidateNow(ConsolidateMemory.Pass.IDLE).get(30, TimeUnit.SECONDS);
        assertThat(idle.outcome()).as(idle.error()).isEqualTo("done");
        assertThat(idle.counts()).containsEntry("added", 2).containsEntry("events", 5);
        List<Fact> known = facts.list(current()).facts();
        assertThat(known).extracting(Fact::statement).containsExactlyInAnyOrder("The owner lives in Lyon.", "The owner has a cat named Tofu.");
        Fact lyon = known.stream().filter(f -> f.statement().contains("Lyon")).findFirst().orElseThrow();
        assertThat(facts.get(lyon.id()).orElseThrow().sources()).extracting(e -> e.body())
                .containsExactly("J'habite à Lyon depuis deux ans", "Lyon, belle ville.");

        ConsolidateMemory.Report night = worker.consolidateNow(ConsolidateMemory.Pass.NIGHTLY).get(30, TimeUnit.SECONDS);
        assertThat(night.outcome()).as(night.error()).isEqualTo("done");
        assertThat(night.counts()).containsEntry("days", 1).containsEntry("profile", 1);
        assertThat(jdbc.sql("SELECT content FROM memory.block_version WHERE status = 'active'").query(String.class).single())
                .isEqualTo("The owner lives in Lyon.\nThe owner has a cat named Tofu.");

        // every memory request kept the voice's context size (no model reload) and asked for a structure
        for (Map<String, Object> body : OLLAMA.requests) {
            assertThat(body).containsKey("format");
            assertThat(((Map<String, Object>) body.get("options")).get("num_ctx")).isEqualTo(8192L);
        }
        assertThat(OLLAMA.embedRequests).isNotEmpty().allSatisfy(b -> assertThat(b).containsEntry("model", "qwen3-embedding:8b"));

        MemoryHealth.Report h = health.health();
        assertThat(h.searchMode()).contains("pgvector HNSW");
        assertThat(h.embedder()).isEqualTo("ready");
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> components = (Map<String, Object>) ((Map<String, Object>) JsonText.parse(r.body())).get("components");
        assertThat((Map<String, Object>) components.get("memory")).containsEntry("state", "up");
        assertThat((String) ((Map<String, Object>) components.get("memory")).get("detail"))
                .contains("2 facts").contains("pgvector HNSW index").contains("embeddings qwen3-embedding:8b ready");

        // "Consolidate now" from the app, and the worker's state
        HttpResponse<String> post = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/memory/consolidate"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"pass\": \"idle\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(202);
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/memory/consolidate"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"pass\": \"weekly\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        Map<String, Object> w = (Map<String, Object>) JsonText.parse(HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/memory/worker")).build(), HttpResponse.BodyHandlers.ofString()).body());
        assertThat(w).containsKeys("state", "pending", "last", "embeddings", "next_night_at");
        assertThat((Map<String, Object>) w.get("embeddings")).containsEntry("state", "ready").containsEntry("dimensions", 1024L);

        Fact tofu = known.stream().filter(f -> f.statement().contains("Tofu")).findFirst().orElseThrow();
        assertThat(forget.forgetFact(tofu.id()).facts()).isEqualTo(1);
        assertThat(facts.list(current()).facts()).extracting(Fact::statement).containsExactly("The owner lives in Lyon.");
        assertThat(jdbc.sql("SELECT content FROM memory.block_version WHERE status = 'active'").query(String.class).single())
                .isEqualTo("The owner lives in Lyon.");
    }
}
