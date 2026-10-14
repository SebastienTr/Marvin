// SPDX-License-Identifier: MIT
package marvin.host.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import marvin.host.contracts.voice.v1.CoreToVoice;
import marvin.host.contracts.voice.v1.TextPiece;
import marvin.host.contracts.voice.v1.VoiceGrpc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The golden files are on the classpath, readable, and consistent with each other. */
class GoldenFilesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static InputStream golden(String path) {
        InputStream in = GoldenFilesTest.class.getResourceAsStream("/marvin/contracts/golden/" + path);
        assertThat(in).as(path).isNotNull();
        return in;
    }

    static JsonNode json(String path) throws IOException {
        try (InputStream in = golden(path)) {
            return JSON.readTree(in);
        }
    }

    @Test
    void protocolVectorsAreWholeDatagramsWithAValidHeader() throws IOException {
        JsonNode v = json("protocol/vectors.json");
        assertThat(v.get("robot_to_host").size()).isGreaterThan(10);
        for (String dir : new String[] {"robot_to_host", "host_to_robot"}) {
            for (JsonNode vec : v.get(dir)) {
                if (!vec.has("datagram")) {
                    continue;
                }
                byte[] d = HexFormat.of().parseHex(vec.get("datagram").asString());
                assertThat(d[0]).isEqualTo((byte) 'M');
                assertThat(d[1]).isEqualTo((byte) 'V');
                assertThat(d[2]).isEqualTo((byte) 1);
                assertThat(d[3] & 0xff).as(vec.get("name").asString()).isEqualTo(vec.get("type").asInt());
            }
        }
    }

    @Test
    void everyRecordingHasItsExpectedFiles() throws IOException {
        JsonNode index = json("recordings/index.json");
        assertThat(index.get("scenarios").size()).isEqualTo(4);
        for (JsonNode s : index.get("scenarios")) {
            String name = s.get("name").asString();
            try (InputStream in = new GZIPInputStream(golden("recordings/" + s.get("file").asString()))) {
                assertThat(new String(in.readNBytes(5))).isEqualTo("MVREC");
            }
            for (String suffix : new String[] {".events.jsonl", ".states.jsonl", ".scans.jsonl"}) {
                try (InputStream in = golden("recordings/" + name + suffix)) {
                    assertThat(in.readAllBytes()).isNotEmpty();
                }
            }
        }
    }

    @Test
    void apiSnapshotsCoverTheDocumentedStreamEvents() throws IOException {
        JsonNode sse = json("api/sse.json");
        assertThat(sse.get("documented_not_seen").size()).isZero();
        assertThat(json("api/get/state.json").get("status").asInt()).isEqualTo(200);
    }

    /**
     * The Marvin v1 contracts (2026-10-14): the endpoints and stream events frozen before they exist are specified,
     * kept out of the replayed list until a stream implements them, and the latency line has its schema.
     */
    @Test
    void theV1ContractsAreSpecified() throws IOException {
        JsonNode index = json("api/index.json");
        JsonNode pending = index.get("pending");
        // each frozen endpoint is either still pending or implemented (moved to the replayed list)
        for (String name : java.util.List.of("setup", "vitals", "soul", "approvals", "connectors")) {
            assertThat(pending.get("get").has(name) != index.get("get").toString().contains("\"" + name + "\""))
                    .as(name + ": pending or replayed, not both").isTrue();
        }
        assertThat(pending.get("get").propertyNames()).isSubsetOf("setup", "vitals", "soul", "approvals", "connectors");
        for (String name : pending.get("get").propertyNames()) {
            assertThat(index.get("get").toString()).as(name).doesNotContain("\"" + name + "\"");
            JsonNode snap = json("api/get/" + name + ".json");
            assertThat(snap.get("contract").get("owner").asString()).isEqualTo(pending.get("get").get(name).asString());
            assertThat(snap.get("request").get("method").asString()).isEqualTo("GET");
            assertThat(snap.get("request").get("path").asString()).startsWith("/api/" + name);
            assertThat(snap.get("status").asInt()).isEqualTo(200);
            assertThat(snap.get("body").propertyNames()).isEqualTo(snap.get("shape").propertyNames());
        }
        JsonNode events = json("api/sse.json").get("streams").get("/api/stream").get("events");
        for (String kind : pending.get("sse").propertyNames()) {
            assertThat(events.get(kind).has("pending")).as(kind).isTrue();
            assertThat(events.get(kind).get("shapes").size()).isEqualTo(1);
        }
        assertThat(json("api/get/setup.json").get("body").get("steps").findValuesAsString("id")).containsExactly(
                "host", "model", "voice", "robot", "mr60", "speaker", "voiceprint", "owner", "quiet_hours");
        JsonNode settings = json("api/get/settings.json").get("body").get("defaults");
        assertThat(settings.get("owner").propertyNames()).containsExactly("name", "languages");
        assertThat(json("api/get/voice.json").get("body").get("settings").get("greet_on_sit").asBoolean()).isTrue();
        JsonNode latency = json("voice/latency.schema.json");
        assertThat(latency.get("required").toString()).isEqualTo("[\"t\",\"lang\",\"stt_ms\",\"llm_first_token_ms\","
                + "\"tts_first_audio_ms\",\"robot_out_ms\",\"total_ms\"]");
    }

    @Test
    void voiceContractCompiles() {
        CoreToVoice m = CoreToVoice.newBuilder().setText(TextPiece.newBuilder().setReplyId(7).setText("Hello")).build();
        assertThat(m.getMCase()).isEqualTo(CoreToVoice.MCase.TEXT);
        assertThat(VoiceGrpc.SERVICE_NAME).isEqualTo("marvin.voice.v1.Voice");
    }
}
