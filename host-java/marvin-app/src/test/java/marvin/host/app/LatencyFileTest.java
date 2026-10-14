// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The latency lines in latency.jsonl (C6): one JSON object per line, the last ones kept. */
class LatencyFileTest {

    private static Map<String, Object> line(int i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", 1_789_983_000.0 + i);
        m.put("lang", "fr");
        m.put("stt_ms", 120L);
        m.put("llm_first_token_ms", 1500L);
        m.put("tts_first_audio_ms", 1830L);
        m.put("robot_out_ms", null);
        m.put("total_ms", 1990L);
        return m;
    }

    @Test
    void oneObjectPerLineWithTheContractsFields(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("data").resolve(LatencyFile.NAME);
        LatencyFile log = new LatencyFile(f);
        log.turn(line(0));
        log.turn(line(1));
        List<String> lines = Files.readAllLines(f);
        assertThat(lines).hasSize(2);
        JsonNode first = new ObjectMapper().readTree(lines.getFirst());
        JsonNode schema = new ObjectMapper().readTree(Files.readString(
                Path.of("../marvin-contracts/golden/voice/latency.schema.json")));
        schema.get("required").forEach(k -> assertThat(first.has(k.asString())).as(k.asString()).isTrue());
        assertThat(first.get("robot_out_ms").isNull()).isTrue();
        assertThat(first.get("total_ms").asLong()).isEqualTo(1990L);
    }

    @Test
    void keepsTheLastLines(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(LatencyFile.NAME);
        LatencyFile log = new LatencyFile(f);
        for (int i = 0; i <= LatencyFile.MAX; i++) {
            log.turn(line(i));
        }
        List<String> lines = Files.readAllLines(f);
        assertThat(lines).hasSize(LatencyFile.KEEP);
        assertThat(new ObjectMapper().readTree(lines.getLast()).get("t").asDouble()).isEqualTo(1_789_983_000.0 + LatencyFile.MAX);
    }
}
