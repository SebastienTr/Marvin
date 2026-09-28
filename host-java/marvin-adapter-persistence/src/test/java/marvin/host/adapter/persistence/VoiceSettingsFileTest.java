// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** voice.json as the Python host writes and reads it. */
class VoiceSettingsFileTest {

    @Test
    void writtenAsThePythonHostWritesItOtherKeysKept(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("marvin/voice.json");
        VoiceSettingsFile f = new VoiceSettingsFile(path);
        assertThat(f.load()).isEmpty();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("llm_model", "qwen3:8b");
        v.put("follow_up_s", 5.0);
        v.put("wake", false);
        f.save(v);
        f.save(Map.of("home_place", "Saint-Étienne"));
        // json.dumps(data, indent=2, ensure_ascii=False) + "\n"
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo("""
                {
                  "llm_model": "qwen3:8b",
                  "follow_up_s": 5.0,
                  "wake": false,
                  "home_place": "Saint-Étienne"
                }
                """);
        assertThat(f.load()).containsEntry("wake", false).containsEntry("follow_up_s", 5.0)
                .containsEntry("home_place", "Saint-Étienne");
    }

    @Test
    void aBrokenFileIsIgnored(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("voice.json");
        Files.writeString(path, "{not json");
        assertThat(new VoiceSettingsFile(path).load()).isEmpty();
        Files.writeString(path, "[1, 2]");
        assertThat(new VoiceSettingsFile(path).load()).isEmpty();
        Files.writeString(path, "{\"stt\": \"mlx\", \"input_device\": 2}");
        assertThat(new VoiceSettingsFile(path).load()).containsEntry("stt", "mlx").containsEntry("input_device", 2L);
    }

    @Test
    void theConfigDirectoryIsTheOneThePythonHostUses() {
        assertThat(VoiceSettingsFile.configDir("/tmp/x")).isEqualTo(Path.of("/tmp/x"));
        String env = System.getenv("MARVIN_CONFIG_DIR");
        if (env == null || env.isBlank()) {
            assertThat(VoiceSettingsFile.configDir("").toString()).endsWith("marvin");
        }
    }
}
