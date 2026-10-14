// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.VoiceSidecar;

/** The latency line (C6): one JSON object per answered turn, milliseconds from the end of speech. */
class LatencyLineTest {
    final VoiceServiceTest t = new VoiceServiceTest();

    @AfterEach
    void close() {
        t.close();
    }

    @Test
    void theStagesAddUpFromTheEndOfSpeech() {
        Map<String, Double> lat = new LinkedHashMap<>();
        lat.put("endpoint", 0.55);
        lat.put("stt", 0.12);
        lat.put("llm_first_token", 1.38);
        lat.put("first_chunk", 1.52);
        lat.put("first_audio", 1.9);
        lat.put("audio_start", 1.95);
        lat.put("playback_start", 2.1);
        Map<String, Object> line = LatencyLine.of(1_789_983_000.0, "fr", lat, 0.05, true);
        assertThat(line).containsEntry("t", 1_789_983_000.0).containsEntry("lang", "fr").containsEntry("stt_ms", 670L)
                .containsEntry("llm_first_token_ms", 2100L).containsEntry("tts_first_audio_ms", 2450L)
                .containsEntry("robot_out_ms", 2650L).containsEntry("total_ms", 2650L).containsEntry("endpoint_ms", 550L)
                .containsEntry("prepare_ms", 50L).containsEntry("route", "robot");
        assertThat(line.keySet()).startsWith("t", "lang", "stt_ms", "llm_first_token_ms", "tts_first_audio_ms",
                "robot_out_ms", "total_ms");
    }

    @Test
    void whatDidNotHappenIsNull() {
        Map<String, Object> line = LatencyLine.of(1.0, "en", Map.of("audio_start", 0.8), null, false);
        assertThat(line).containsEntry("stt_ms", null).containsEntry("llm_first_token_ms", null)
                .containsEntry("tts_first_audio_ms", 800L).containsEntry("robot_out_ms", null).containsEntry("total_ms", 800L)
                .containsEntry("route", "computer");
    }

    @Test
    void eachAnsweredTurnWritesOneLine() throws InterruptedException {
        List<Map<String, Object>> lines = new CopyOnWriteArrayList<>();
        VoiceService v = t.service(FakeModel.of("w", "w", "Paris."));
        v.setLatencyLog(lines::add);
        v.start();
        VoiceServiceTest.waitFor(() -> v.snapshot().state().equals("on"));
        Map<String, Double> heard = new LinkedHashMap<>();
        heard.put("endpoint", 0.5);
        heard.put("stt", 0.1);
        t.sidecar.signals.signal(new VoiceSidecar.Heard(3, "Quelle est la capitale ?", "", "fr", "voice", heard,
                1_790_000_000.0));
        VoiceServiceTest.waitFor(() -> lines.size() == 1);
        Map<String, Object> line = lines.getFirst();
        assertThat(line).containsEntry("lang", "fr").containsEntry("stt_ms", 600L).containsEntry("route", "computer")
                .containsEntry("robot_out_ms", null).containsEntry("total_ms", 900L);       // 0.5 + the fake's audio_start
        assertThat((double) line.get("t")).isEqualTo(1_789_999_999.5);
        assertThat((Long) line.get("llm_first_token_ms")).isGreaterThanOrEqualTo(600L);
    }
}
