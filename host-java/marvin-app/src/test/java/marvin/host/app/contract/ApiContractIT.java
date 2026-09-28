// SPDX-License-Identifier: MIT
package marvin.host.app.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

import marvin.host.adapter.sidecar.PythonRuntime;
import marvin.host.adapter.sidecar.SidecarProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The app's API contract: every request recorded from the Python host in demo mode
 * (marvin-contracts golden/api) is replayed against the Java host in demo mode, with the same access
 * key, and must get the same status, the same headers, and a body of the same shape (the same values
 * where they are not volatile: error messages, settings, the access rules, the app's files). The voice
 * is not in the Java host yet: its endpoints must answer as the Python host does without a voice.
 * Both event streams are checked the same way.
 *
 * <p>Needs Docker (PostgreSQL) and the Python host (the demo's simulated robot and past week).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=0.0.0.0", "marvin.mode=demo", "marvin.robot.port=0",
                "marvin.robot.calibration-file=no-such-calibration.json", "marvin.web.token=contract-key",
                "marvin.import.sqlite=", "marvin.time-zone=UTC"})
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiContractIT {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> NO_VOICE_GETS = Set.of("voice", "voice_after_turns", "voice_options");
    /** Python's json error text; the Java host says "invalid JSON: ..." (same status). */
    static final Set<String> OWN_MESSAGE = Set.of("bad_json");

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
            if (gold.has("settings") && gold.has("about") && !withoutVoice(gold.get("settings")).equals(
                    withoutVoice(body.get("settings")))) {
                problems.add(name + ": settings " + body.get("settings") + " instead of " + gold.get("settings"));
            }
        } else if (snap.has("body_text")) {
            if (!snap.get("body_text").asString().equals(r.text())) {
                problems.add(name + ": page differs");
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
     * The settings without {@code voice}: the Python recording turned the voice on through
     * {@code /api/voice/on}, which the Java host (no voice yet) refuses.
     */
    static JsonNode withoutVoice(JsonNode settings) {
        var copy = ((tools.jackson.databind.node.ObjectNode) settings).deepCopy();
        copy.remove("voice");
        return copy;
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
            if (NO_VOICE_GETS.contains(name)) {
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

    @Test
    @Order(2)
    void withoutAVoiceTheVoiceEndpointsAnswerAsThePythonHostDoesWithoutOne() throws Exception {
        RawHttp.Response v = RawHttp.call("127.0.0.1", port, "GET", "/api/voice", null, null);
        assertThat(v.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(v.body());
        assertThat(body.get("voice").get("state").asString()).isEqualTo("unavailable");
        assertThat(body.get("settings").isNull()).isTrue();
        assertThat(body.get("transcript").isEmpty()).isTrue();
        assertThat(Shapes.diff(Shapes.of(body.get("voice")),
                Shapes.of(JSON.readTree("{\"state\":\"\",\"status\":\"\",\"muted\":false,\"error\":\"\",\"fix\":\"\"}")),
                "voice")).isEmpty();
        RawHttp.Response o = RawHttp.call("127.0.0.1", port, "GET", "/api/voice/options", null, null);
        assertThat(o.status()).isEqualTo(404);
        assertThat(o.text()).isEqualTo("{\"error\":\"voice control is not available\"}");
        for (JsonNode n : index.get("post")) {
            String name = n.asString();
            if (!name.startsWith("voice")) {
                continue;
            }
            JsonNode snap = golden("post/" + name + ".json");
            RawHttp.Response r = replay(snap, "127.0.0.1");
            assertThat(r.status()).as(name).isEqualTo(404);
            assertThat(r.text()).as(name).isEqualTo("{\"error\":\"voice control is not available\"}");
        }
    }

    @Test
    @Order(3)
    void everyPostAnswersLikeThePythonHost() throws Exception {
        List<String> problems = new ArrayList<>();
        List<String> names = new ArrayList<>();
        index.get("post").forEach(n -> names.add(n.asString()));
        names.addAll(List.of("wrong_content_type", "cross_origin", "bad_json", "settings_break_50"));
        for (String name : names) {
            if (name.startsWith("voice")) {
                continue;
            }
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
    @Order(5)
    void bothStreamsCarryTheSameEvents() throws Exception {
        JsonNode sse = golden("sse.json").get("streams");
        checkStream("/api/stream", sse.get("/api/stream"), List.of("hello", "today", "devices", "state"));
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
            JsonNode shape = Shapes.of(JSON.readTree(e[1]));
            boolean ok = false;
            List<String> why = List.of();
            for (JsonNode s : g.get("shapes")) {
                List<String> d = Shapes.diff(shape, s, path + " " + e[0]);
                if (d.isEmpty()) {
                    ok = true;
                    break;
                }
                why = d;
            }
            if (!ok) {
                problems.addAll(why);
            }
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
