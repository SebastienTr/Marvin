// SPDX-License-Identifier: MIT
package marvin.host.app.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import marvin.host.adapter.sidecar.PythonRuntime;
import marvin.host.adapter.sidecar.SidecarProperties;
import marvin.host.adapter.llm.StubOllama;
import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.out.JsonFetcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The app's API contract: every request recorded from the Python host in demo mode
 * (marvin-contracts golden/api) is replayed against the Java host in demo mode, with the same access
 * key, and must get the same status, the same headers, and a body of the same shape (the same values
 * where they are not volatile: error messages, settings, the access rules; the app's files only by status and
 * headers, the Java host's app being its own). The voice
 * is the real one: the Python voice sidecar in its test mode (a scripted microphone, a fake Whisper and
 * voice) and a stand-in for Ollama; the weather tool gets canned Open-Meteo answers. Both event streams
 * are checked the same way, including the voice's live messages.
 *
 * <p>Needs Docker (PostgreSQL) and the Python host (the demo's simulated robot and past week, the voice
 * sidecar with the {@code sidecar} extra).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=0.0.0.0", "marvin.mode=demo", "marvin.robot.port=0",
                "marvin.robot.calibration-file=no-such-calibration.json", "marvin.web.token=contract-key",
                "marvin.import.sqlite=", "marvin.time-zone=UTC", "marvin.sidecar.voice-args=--fake"})
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiContractIT {
    static final ObjectMapper JSON = new ObjectMapper();
    /** Recorded after the live turns: checked then. */
    static final Set<String> AFTER_TURNS = Set.of("voice_after_turns", "conversation_search_after_turns",
            "conversation_today_after_turns");
    /** The stand-in for Ollama: a tool call for the weather, else a short answer. */
    static final StubOllama OLLAMA;
    static final Path CONFIG;
    static final String OWNER_VOICE_JSON;
    static final Path DATA;

    static {
        try {
            OLLAMA = new StubOllama();
            OLLAMA.chat = body -> {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
                Map<String, Object> last = messages.get(messages.size() - 1);
                if ("user".equals(last.get("role")) && String.valueOf(last.get("content")).contains("weather")
                        && body.get("tools") instanceof List<?> t && !t.isEmpty()) {
                    return StubOllama.toolCall("get_weather", Map.<String, Object>of("place", "Paris"));
                }
                return StubOllama.text("It is ", "sunny in Paris, ", "twenty degrees.");
            };
            CONFIG = Files.createTempDirectory("marvin-contract-config");
            OWNER_VOICE_JSON = "{\"ollama_host\": \"" + OLLAMA.url() + "\"}\n";
            Files.writeString(CONFIG.resolve("voice.json"), OWNER_VOICE_JSON);
            DATA = Files.createTempDirectory("marvin-contract-data");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void voice(DynamicPropertyRegistry r) {
        r.add("marvin.config-dir", CONFIG::toString);
        r.add("marvin.data-dir", DATA::toString);
    }

    /** Open-Meteo, canned. */
    @TestConfiguration
    static class CannedWeather {
        @Bean
        @Primary
        JsonFetcher cannedWeather() {
            return (url, params, timeout) -> url.contains("geocoding")
                    ? Map.of("results", List.of(Map.of("name", "Paris", "country", "France", "latitude", 48.85, "longitude", 2.35)))
                    : Map.of("current", Map.of("time", "2026-09-21T09:30", "temperature_2m", 20.4, "weather_code", 0),
                            "daily", Map.of("time", List.of("2026-09-21", "2026-09-22")));
        }
    }
    /** Python's json error text; the Java host says "invalid JSON: ..." (same status). */
    static final Set<String> OWN_MESSAGE = Set.of("bad_json");
    /** The app's own files: the Java host's app differs from the Python host's on purpose (docs/ui.md). */
    static final Set<String> JAVA_APP = Set.of("app_index", "app_script", "app_style", "app_manifest", "no_key_static_is_public");

    /**
     * Not a JUnit-managed {@code @Container}: it must outlive the Spring context, which writes the host's
     * stop to the history when it closes (at the end of the JVM). Testcontainers removes it afterwards.
     */
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    @LocalServerPort
    int port;

    @Autowired
    SidecarProperties sidecars;

    @Autowired
    VoiceControl voice;

    static JsonNode index;

    @BeforeAll
    static void load() {
        index = golden("index.json");
    }

    static JsonNode golden(String path) {
        try (InputStream in = ApiContractIT.class.getResourceAsStream("/marvin/contracts/golden/api/" + path)) {
            assertThat(in).as(path).isNotNull();
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    RawHttp.Response replay(JsonNode snap, String address) throws IOException {
        JsonNode rq = snap.get("request");
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = null;
        if (rq.has("body")) {
            body = JSON.writeValueAsBytes(rq.get("body"));
            headers.put("Content-Type", "application/json");
        } else if (rq.has("raw_body")) {
            body = rq.get("raw_body").asString().getBytes(StandardCharsets.UTF_8);
        }
        if (rq.has("headers")) {
            for (Map.Entry<String, JsonNode> h : rq.get("headers").properties()) {
                headers.put(h.getKey(), h.getValue().asString());
            }
        }
        return RawHttp.call(address, port, rq.get("method").asString(), rq.get("path").asString(), headers, body);
    }

    void waitForTheRobot() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PythonRuntime.find(sidecars).isPresent(),
                "the Python host is needed for the demo's robot and past week");
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            JsonNode r = JSON.readTree(RawHttp.call("127.0.0.1", port, "GET", "/api/robot", null, null).body());
            JsonNode scene = r.get("scene");
            if (!r.get("devices").isEmpty() && !scene.get("lidar").isNull() && !scene.get("vitals").isNull()
                    && !scene.get("targets").isEmpty()) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("the simulated robot did not show up");
    }

    /** Status, headers, and body: shape, and values where they are not volatile. */
    List<String> compare(String name, JsonNode snap, RawHttp.Response r) throws IOException {
        List<String> problems = new ArrayList<>();
        if (r.status() != snap.get("status").asInt()) {
            problems.add(name + ": status " + r.status() + " instead of " + snap.get("status").asInt() + " (" + r.text() + ")");
            return problems;
        }
        for (Map.Entry<String, JsonNode> h : snap.get("headers").properties()) {
            String expected = h.getValue().asString();
            if (h.getKey().equals("content-type")) {
                // Tomcat writes "text/html;charset=utf-8", Python "text/html; charset=utf-8": the same type
                expected = expected.replace("; charset=", ";charset=");
            }
            if (h.getKey().equals("cache-control") && expected.equals("no-store, max-age=86400")) {
                // the Python host sends two Cache-Control headers for the icon; the Java host only the second
                expected = "max-age=86400";
            }
            if (!expected.equals(r.header(h.getKey()))) {
                problems.add(name + ": header " + h.getKey() + " is " + r.header(h.getKey()) + ", expected "
                        + h.getValue().asString());
            }
        }
        JsonNode gold = snap.get("body");
        if (gold != null && !gold.isNull()) {
            JsonNode body = JSON.readTree(r.body());
            for (String d : Shapes.diff(Shapes.of(body), snap.get("shape"), name)) {
                problems.add(d);
            }
            if (gold.size() == 1 && gold.has("error") && !OWN_MESSAGE.contains(name)
                    && !gold.get("error").equals(body.get("error"))) {
                problems.add(name + ": error " + body.get("error") + " instead of " + gold.get("error"));
            }
            if (gold.has("settings") && gold.has("about") && !gold.get("settings").equals(body.get("settings"))) {
                problems.add(name + ": settings " + body.get("settings") + " instead of " + gold.get("settings"));
            }
        } else if (snap.has("body_text")) {
            if (!snap.get("body_text").asString().equals(r.text())) {
                problems.add(name + ": page differs");
            }
        } else if (JAVA_APP.contains(name)) {
            // the Java host has its own app since the new visual direction: same status and headers, its own
            // bytes (AppFilesTest checks the files, host-java/e2e/e2e.py the app itself)
            if (r.body().length == 0) {
                problems.add(name + ": empty");
            }
        } else if (snap.has("body_bytes") && snap.get("headers").get("content-type").asString().startsWith("text/")
                || name.equals("app_manifest")) {
            if (snap.get("body_bytes").asInt() != r.body().length) {
                problems.add(name + ": " + r.body().length + " bytes instead of " + snap.get("body_bytes").asInt());
            }
        }
        return problems;
    }

    /**
     * Conversation entries carry different keys by kind (a reply to a question has the context the model
     * was given, a proactive one has none): which ones a recording saw depends on its random days. Every
     * entry shape seen in any recording is accepted in any conversation response.
     */
    static JsonNode withEveryEntryShape(JsonNode snap) {
        var all = JSON.createArrayNode();
        for (String f : List.of("conversation_today", "conversation_past_day", "conversation_search",
                "conversation_search_limit", "conversation_today_after_turns", "conversation_search_after_turns")) {
            JsonNode shape = golden("get/" + f + ".json").get("shape");
            for (String list : List.of("entries", "results")) {
                if (shape.has(list)) {
                    shape.get(list).forEach(e -> {
                        if (!all.toString().contains(e.toString())) {
                            all.add(e);
                        }
                    });
                }
            }
        }
        var copy = ((tools.jackson.databind.node.ObjectNode) snap).deepCopy();
        var shape = (tools.jackson.databind.node.ObjectNode) copy.get("shape");
        for (String list : List.of("entries", "results")) {
            if (shape.has(list)) {
                shape.set(list, all);
            }
        }
        return copy;
    }

    @Test
    @Order(1)
    void everyGetAnswersLikeThePythonHost() throws Exception {
        waitForTheRobot();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (JsonNode n : index.get("get")) {
            String name = n.asString();
            if (AFTER_TURNS.contains(name)) {
                continue;
            }
            JsonNode snap = golden("get/" + name + ".json");
            if (name.startsWith("conversation")) {
                snap = withEveryEntryShape(snap);
            }
            problems.addAll(compare(name, snap, replay(snap, "127.0.0.1")));
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(25);
        assertThat(problems).isEmpty();
    }

    void waitForTheVoice() throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end && !voice.snapshot().state().equals("on")) {
            if (voice.snapshot().state().equals("error")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "the voice sidecar cannot run here: "
                        + voice.snapshot().error());
            }
            Thread.sleep(100);
        }
        assertThat(voice.snapshot().state()).as("the voice").isEqualTo("on");
    }

    /**
     * Every POST, in the order the Python host's were recorded, with the voice on as it was then (and the
     * app setting off); then the live turns: Talk now, a typed question answered with the weather tool, a
     * settings change; then what the voice and the conversation show afterwards. The event stream is read
     * meanwhile: every message has a recorded shape, and the voice's live messages are there.
     */
    @Test
    @Order(2)
    void everyPostAndTheLiveTurnsAnswerLikeThePythonHost() throws Exception {
        waitForTheRobot();
        voice.start();
        waitForTheVoice();
        List<String[]> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                events.addAll(Sse.read("127.0.0.1", port, "/api/stream", 15_000));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Thread.sleep(500);
        List<String> problems = new ArrayList<>();
        List<String> names = new ArrayList<>();
        index.get("post").forEach(n -> names.add(n.asString()));
        for (String name : names) {
            JsonNode snap = golden("post/" + name + ".json");
            problems.addAll(compare(name, snap, replay(snap, "127.0.0.1")));
        }
        waitForTheVoice();
        for (String name : List.of("voice_listen_turn", "voice_ask")) {
            JsonNode snap = golden("post/" + name + ".json");
            problems.addAll(compare(name, snap, replay(snap, "127.0.0.1")));
            Thread.sleep(name.equals("voice_ask") ? 3000 : 1000);
        }
        JsonNode last = golden("post/settings_break_50.json");
        problems.addAll(compare("settings_break_50", last, replay(last, "127.0.0.1")));
        for (String name : AFTER_TURNS) {
            JsonNode snap = golden("get/" + name + ".json");
            if (name.startsWith("conversation")) {
                snap = withEveryEntryShape(snap);
            }
            problems.addAll(compare(name, snap, withoutToolCalls(replay(snap, "127.0.0.1"))));
        }
        assertThat(problems).isEmpty();

        // the typed question: heard, answered with a tool call, what the model was given
        JsonNode v = JSON.readTree(RawHttp.call("127.0.0.1", port, "GET", "/api/voice", null, null).body());
        JsonNode reply = null;
        for (JsonNode e : v.get("transcript")) {
            if (e.get("kind").asString().equals("reply") && e.has("tools")) {
                reply = e;
            }
        }
        assertThat(reply).as("the weather reply").isNotNull();
        assertThat(reply.get("text").asString()).endsWith("It is sunny in Paris, twenty degrees.");
        assertThat(reply.get("tools").get(0).get("name").asString()).isEqualTo("get_weather");
        assertThat(reply.get("tools").get(0).get("ok").asBoolean()).isTrue();
        assertThat(reply.get("prompt").asString()).contains("The person says: What's the weather in Paris?");
        assertThat(reply.get("latency").has("tools")).isTrue();

        reader.join();
        JsonNode gold = golden("sse.json").get("streams").get("/api/stream");
        Set<String> seen = new java.util.TreeSet<>();
        for (String[] e : events) {
            seen.add(e[0]);
            if (e[0].equals("memory")) {
                continue;                               // memory's changes: the Java host's own (docs/memory.md)
            }
            JsonNode g = gold.get("events").get(e[0]);
            if (g == null) {
                problems.add("/api/stream: event " + e[0] + " is not in the Python stream");
                continue;
            }
            JsonNode payload = JSON.readTree(e[1]);
            if (e[0].equals("transcript")) {
                ((tools.jackson.databind.node.ObjectNode) payload).remove("tools");     // see withoutToolCalls
                ((tools.jackson.databind.node.ObjectNode) payload).remove("memory");
            }
            problems.addAll(matchesOne(Shapes.of(payload), g.get("shapes"), "/api/stream " + e[0]));
        }
        assertThat(problems).isEmpty();
        assertThat(seen).contains("voice", "transcript", "level", "say");
    }

    /**
     * The entries without {@code tools}: the Python recording's typed question got a scripted answer (its demo
     * voice) with no tool call, the Java host's calls the weather tool, and its reply carries the calls, as
     * the Python host's real voice's replies do. And without {@code memory}: the report of what memory put in the
     * prompt, which only the Java host has (docs/memory.md, the reply inspector).
     */
    static RawHttp.Response withoutToolCalls(RawHttp.Response r) {
        JsonNode body = JSON.readTree(r.body());
        for (String list : List.of("transcript", "entries", "results")) {
            if (body.has(list)) {
                body.get(list).forEach(e -> ((tools.jackson.databind.node.ObjectNode) e).remove(List.of("tools", "memory")));
            }
        }
        return new RawHttp.Response(r.status(), r.headers(), JSON.writeValueAsBytes(body));
    }

    static List<String> matchesOne(JsonNode shape, JsonNode shapes, String path) {
        List<String> why = List.of();
        for (JsonNode s : shapes) {
            List<String> d = Shapes.diff(shape, s, path);
            if (d.isEmpty()) {
                return List.of();
            }
            why = d;
        }
        return why;
    }

    @Test
    @Order(3)
    void thePostRulesAreThePythonHostsOnes() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String name : List.of("wrong_content_type", "cross_origin", "bad_json")) {
            JsonNode snap = golden("post/" + name + ".json");
            problems.addAll(compare(name, snap, replay(snap, "127.0.0.1")));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @Order(4)
    void theAccessRulesAreThePythonHostsOnes() throws Exception {
        List<String> problems = new ArrayList<>();
        JsonNode bad = golden("access/bad_host.json");
        problems.addAll(compare("bad_host", bad, replay(bad, "127.0.0.1")));
        String lan = lanAddress();
        org.junit.jupiter.api.Assumptions.assumeTrue(lan != null, "no LAN address to test as another device");
        for (JsonNode n : index.get("access")) {
            String name = n.asString();
            if (name.equals("bad_host")) {
                continue;
            }
            JsonNode snap = golden("access/" + name + ".json");
            problems.addAll(compare(name, snap, replay(snap, lan)));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @Order(4)
    void aRebindingDomainNamedLikeThisMachineIsRefused() throws Exception {
        // deliberate deviation shared with the Python host: whole names only (see host-java/NOTES.md)
        String shortName = marvin.host.adapter.web.AccessFilter.localHostName().toLowerCase(java.util.Locale.ROOT).split("\\.")[0];
        for (String method : List.of("GET", "POST")) {
            String host = shortName + ".evil.example:" + port;
            Map<String, String> h = new java.util.LinkedHashMap<>(Map.of("Host", host));
            byte[] body = null;
            if (method.equals("POST")) {
                h.put("Content-Type", "application/json");
                h.put("Origin", "http://" + host);
                body = "{}".getBytes(StandardCharsets.UTF_8);
            }
            assertThat(RawHttp.call("127.0.0.1", port, method, "/api/settings", h, body).status())
                    .as(method + " with Host " + host).isEqualTo(403);
        }
        for (String name : List.of(shortName, shortName + ".local")) {
            assertThat(RawHttp.call("127.0.0.1", port, "GET", "/api/settings", Map.of("Host", name + ":" + port), null).status())
                    .as("Host " + name).isEqualTo(200);
        }
    }

    /** The demo changes its own copy of voice.json (as the Python demo), never the owner's. */
    @Test
    @Order(6)
    void theDemoLeavesTheOwnersVoiceSettingsAlone() throws Exception {
        Map<String, String> h = Map.of("Content-Type", "application/json");
        RawHttp.Response r = RawHttp.call("127.0.0.1", port, "POST", "/api/voice/settings", h,
                "{\"follow_up_s\": 3}".getBytes(StandardCharsets.UTF_8));
        assertThat(r.status()).isEqualTo(200);
        assertThat(Files.readString(CONFIG.resolve("voice.json"))).isEqualTo(OWNER_VOICE_JSON);
        assertThat(Files.readString(DATA.resolve("demo").resolve("voice.json"))).contains("\"follow_up_s\": 3");
    }

    @Test
    @Order(5)
    void bothStreamsCarryTheSameEvents() throws Exception {
        JsonNode sse = golden("sse.json").get("streams");
        checkStream("/api/stream", sse.get("/api/stream"), List.of("hello", "today", "voice", "devices", "state"));
        checkStream("/api/robot/stream", sse.get("/api/robot/stream"), List.of("hello", "scene", "devices"));
    }

    private void checkStream(String path, JsonNode gold, List<String> first) throws Exception {
        List<String[]> events = Sse.read("127.0.0.1", port, path, 3500);
        assertThat(events).isNotEmpty();
        assertThat(events.getFirst()[2]).as("retry on the first message").isEqualTo("3000");
        List<String> names = events.stream().map(e -> e[0]).toList();
        assertThat(names.subList(0, first.size())).isEqualTo(first);
        List<String> problems = new ArrayList<>();
        for (String[] e : events) {
            JsonNode g = gold.get("events").get(e[0]);
            if (g == null) {
                problems.add(path + ": event " + e[0] + " is not in the Python stream");
                continue;
            }
            problems.addAll(matchesOne(Shapes.of(JSON.readTree(e[1])), g.get("shapes"), path + " " + e[0]));
        }
        assertThat(problems).isEmpty();
    }

    /** This computer's LAN address (no packet sent), as the Python host finds it. */
    static String lanAddress() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 9));
            String ip = s.getLocalAddress().getHostAddress();
            return ip.startsWith("127.") || ip.equals("0.0.0.0") ? null : ip;
        } catch (IOException e) {
            return null;
        }
    }
}
