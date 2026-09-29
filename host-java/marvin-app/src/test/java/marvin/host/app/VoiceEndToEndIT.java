// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.adapter.llm.StubOllama;
import marvin.host.app.contract.RawHttp;
import marvin.host.app.contract.Sse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A spoken question, end to end: the Java host, the real Python voice sidecar in its test mode (its
 * scripted microphone says "Marvin, quelle heure est-il ?" a few seconds after the voice starts; the real
 * VAD, wake word and filters hear it; a fake Whisper recognises it; a fake voice speaks) and a stand-in for
 * Ollama. The question becomes a transcript entry, the answer is streamed from the model to the voice, said,
 * and kept with its latency breakdown and what the model was given; the app's stream carries the live
 * signals.
 *
 * <p>Needs Docker (PostgreSQL) and the Python host with the {@code sidecar} extra.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "marvin.mode=live", "marvin.robot.bind=127.0.0.1",
                "marvin.robot.port=0", "marvin.robot.calibration-file=no-such-calibration.json",
                "marvin.import.sqlite=", "marvin.web.token=off",
                "marvin.sidecar.voice-args=--fake,--say,4:Marvin quelle heure est-il ?"})
@Testcontainers(disabledWithoutDocker = true)
class VoiceEndToEndIT {
    static final ObjectMapper JSON = new ObjectMapper();
    static final StubOllama OLLAMA;
    static final Path CONFIG;

    static {
        try {
            OLLAMA = new StubOllama();
            OLLAMA.chat = body -> StubOllama.text("Il est ", "neuf heures ", "et quart. ", "Bonne journée.");
            CONFIG = Files.createTempDirectory("marvin-voice-config");
            Files.writeString(CONFIG.resolve("voice.json"), "{\"ollama_host\": \"" + OLLAMA.url() + "\", \"internet\": false}\n");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void voice(DynamicPropertyRegistry r) {
        r.add("marvin.config-dir", CONFIG::toString);
    }

    @LocalServerPort
    int port;

    JsonNode get(String path) throws IOException {
        return JSON.readTree(RawHttp.call("127.0.0.1", port, "GET", path, null, null).body());
    }

    @Test
    void aSpokenQuestionIsHeardAnsweredSaidAndKept() throws Exception {
        List<String[]> events = new CopyOnWriteArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                events.addAll(Sse.read("127.0.0.1", port, "/api/stream", 25_000));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Thread.sleep(300);
        RawHttp.Response on = RawHttp.call("127.0.0.1", port, "POST", "/api/voice/on",
                Map.of("Content-Type", "application/json"), "{}".getBytes());
        assertThat(on.status()).isEqualTo(200);

        JsonNode reply = null;
        JsonNode heard = null;
        long end = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < end && reply == null) {
            JsonNode v = get("/api/voice");
            if (v.get("voice").get("state").asString().equals("error")) {
                assumeTrue(false, "the voice sidecar cannot run here: " + v.get("voice").get("error").asString());
            }
            for (JsonNode e : v.get("transcript")) {
                if (e.get("kind").asString().equals("heard")) {
                    heard = e;
                }
                if (e.get("kind").asString().equals("reply") && !e.get("proactive").asBoolean()) {
                    reply = e;
                }
            }
            Thread.sleep(200);
        }
        assertThat(heard).as("the question").isNotNull();
        assertThat(heard.get("text").asString()).isEqualTo("quelle heure est-il ?");
        assertThat(heard.get("raw").asString()).isEqualTo("Marvin quelle heure est-il ?");
        assertThat(heard.get("source").asString()).isEqualTo("voice");
        assertThat(reply).as("the answer").isNotNull();
        assertThat(reply.get("text").asString()).isEqualTo("Il est neuf heures et quart. Bonne journée.");
        assertThat(reply.get("language").asString()).isEqualTo("fr");
        Set<String> stages = new TreeSet<>();
        reply.get("latency").properties().forEach(p -> stages.add(p.getKey()));
        assertThat(stages).contains("endpoint", "llm_first_token", "first_chunk", "audio_start", "total");
        assertThat(reply.get("first_word_s").isNumber()).isTrue();
        assertThat(reply.get("interrupted").asBoolean()).isFalse();
        assertThat(reply.get("model").asString()).isEqualTo("qwen3:4b-instruct");
        assertThat(reply.get("context").asString()).startsWith("Context:\n- It is ")
                .contains("Your radar sees nobody right now");
        assertThat(reply.get("prompt").asString()).endsWith("The person says: quelle heure est-il ?\n\n(Answer in French.)");
        // what Ollama received: the rehearsal twice, then the question with the same system prompt and options
        List<Map<String, Object>> requests = new ArrayList<>(OLLAMA.requests);
        assertThat(requests.size()).isGreaterThanOrEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> question = (List<Map<String, Object>>) requests.get(requests.size() - 1).get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rehearsal = (List<Map<String, Object>>) requests.get(0).get("messages");
        assertThat(question.get(0)).isEqualTo(rehearsal.get(0));
        assertThat(requests.get(0).get("options")).isEqualTo(requests.get(requests.size() - 1).get("options"));
        assertThat(requests.get(0).get("keep_alive")).isEqualTo("30m");
        // kept in the conversation
        JsonNode today = get("/api/conversation");
        List<String> kinds = new ArrayList<>();
        today.get("entries").forEach(e -> kinds.add(e.get("kind").asString()));
        assertThat(kinds).contains("note", "heard", "reply");

        reader.join();
        Set<String> seen = new TreeSet<>();
        events.forEach(e -> seen.add(e[0]));
        assertThat(seen).contains("voice", "transcript", "level", "utterance", "partial", "say");
        JsonNode say = null;
        for (String[] e : events) {
            if (e[0].equals("say")) {
                say = JSON.readTree(e[1]);
                break;
            }
        }
        List<String> keys = new ArrayList<>();
        say.properties().forEach(p -> keys.add(p.getKey()));
        assertThat(keys).containsExactly("text", "seconds", "envelope", "t");
        assertThat(get("/api/health").get("components").get("voice").get("state").asString()).isEqualTo("up");
    }
}
