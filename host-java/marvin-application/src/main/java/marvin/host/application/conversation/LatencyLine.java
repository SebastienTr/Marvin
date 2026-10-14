// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The latency line of an answered turn (v1 contract C6, {@code golden/voice/latency.schema.json}): milliseconds from
 * the end of the person's speech, from the turn's breakdown (seconds, each stage measured where docs/voice.md
 * "Latency" says).
 *
 * <ul>
 * <li>{@code stt_ms}: end of speech ({@code endpoint}), the queue, then what recognition left to wait for
 * ({@code stt});</li>
 * <li>{@code llm_first_token_ms}: that, the core's preparation of the question (memory, context: {@code prepare_ms}),
 * then the model's first token;</li>
 * <li>{@code tts_first_audio_ms}: {@code endpoint} + the first clause's first sound ({@code first_audio}, else
 * {@code audio_start}), which the voice measures from the end of the segment;</li>
 * <li>{@code total_ms}: the first sound heard ({@code endpoint} + {@code playback_start}, else {@code audio_start}),
 * the app's "first word"; {@code robot_out_ms} is the same on the robot's speaker, {@code null} on the
 * computer's.</li>
 * </ul>
 * Extra stages: {@code endpoint_ms}, {@code prepare_ms}, {@code tools_ms}; and {@code route} ({@code robot} or
 * {@code computer}).
 */
final class LatencyLine {
    private LatencyLine() {
    }

    /**
     * @param endOfSpeech wall-clock seconds of the end of speech
     * @param lat         the turn's breakdown (seconds)
     * @param prepareS    the core's time from the question heard to the model's request (seconds; null: unknown)
     * @param robot       the voice speaks on the robot
     */
    static Map<String, Object> of(double endOfSpeech, String lang, Map<String, Double> lat, Double prepareS, boolean robot) {
        double endpoint = lat.getOrDefault("endpoint", 0.0);
        Double heardS = lat.containsKey("stt") ? endpoint + lat.getOrDefault("queue", 0.0) + lat.get("stt") : null;
        Double firstToken = heardS != null && lat.containsKey("llm_first_token")
                ? heardS + (prepareS == null ? 0.0 : prepareS) + lat.get("llm_first_token") : null;
        Double firstAudio = lat.containsKey("first_audio") ? Double.valueOf(endpoint + lat.get("first_audio"))
                : lat.containsKey("audio_start") ? Double.valueOf(endpoint + lat.get("audio_start")) : null;
        Double heard = lat.containsKey("playback_start") ? Double.valueOf(endpoint + lat.get("playback_start"))
                : lat.containsKey("audio_start") ? Double.valueOf(endpoint + lat.get("audio_start")) : null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", Math.round(endOfSpeech * 1000) / 1000.0);
        m.put("lang", lang == null || !lang.matches("[a-z]{2}") ? "en" : lang);
        m.put("stt_ms", ms(heardS));
        m.put("llm_first_token_ms", ms(firstToken));
        m.put("tts_first_audio_ms", ms(firstAudio));
        m.put("robot_out_ms", robot ? ms(heard) : null);
        m.put("total_ms", ms(heard));
        m.put("endpoint_ms", ms(lat.get("endpoint")));
        m.put("prepare_ms", ms(prepareS));
        m.put("tools_ms", ms(lat.get("tools")));
        m.put("route", robot ? "robot" : "computer");
        return m;
    }

    private static Long ms(Double s) {
        return s == null ? null : Math.max(0L, Math.round(s * 1000));
    }
}
