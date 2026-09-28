// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import marvin.host.domain.conversation.tool.ToolSpec;
import marvin.host.domain.conversation.tool.Weather;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.shared.JsonText;

/**
 * The conversation domain against the Python host's own output ({@code golden/conversation/vectors.json},
 * made by {@code conversation_vectors.py}): prompts, context blocks, text cleaning, sentence splitting,
 * language guesses, settings checks, memory and proactive speech.
 */
class ConversationVectorsTest {
    static Map<String, Object> v;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        try (InputStream in = ConversationVectorsTest.class
                .getResourceAsStream("/marvin/contracts/golden/conversation/vectors.json")) {
            assertThat(in).isNotNull();
            v = (Map<String, Object>) JsonText.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    static Double dbl(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    @Test
    void personaPromptsAndPhrases() {
        Map<String, Object> p = map(v.get("persona"));
        for (Object o : list(p.get("prompts"))) {
            Map<String, Object> c = map(o);
            assertThat(Persona.personaPrompt((String) c.get("language"), (Boolean) c.get("tools")))
                    .as("%s %s", c.get("language"), c.get("tools")).isEqualTo(c.get("text"));
        }
        for (Object o : list(p.get("phrases"))) {
            Map<String, Object> c = map(o);
            assertThat(Persona.phrase((String) c.get("key"), (String) c.get("language"), Map.of("minutes", 42)))
                    .isEqualTo(c.get("text"));
        }
    }

    @Test
    void contextBlocksAndUserMessages() {
        Map<String, Object> ctx = map(v.get("context"));
        LocalDateTime now = LocalDateTime.parse((String) ctx.get("now"));
        int n = 0;
        for (Object o : list(ctx.get("cases"))) {
            Map<String, Object> c = map(o);
            PresenceState state = null;
            if (c.get("state") != null) {
                Map<String, Object> s = map(c.get("state"));
                state = new PresenceState(((Number) s.get("t_us")).longValue(), (Boolean) s.get("present"),
                        (Boolean) s.get("seated"), null, null, dbl(s.get("distance_m")), 0, 0, dbl(s.get("seated_s")),
                        dbl(s.get("breath_rate")), dbl(s.get("heart_rate")), (Boolean) s.get("vitals_sensor"),
                        (Boolean) s.get("simulated"), 0);
            }
            List<PresenceEvent> events = new ArrayList<>();
            for (Object e : list(c.get("events"))) {
                Map<String, Object> m = map(e);
                events.add(new PresenceEvent(EventKind.fromWireName((String) m.get("kind")).orElseThrow(),
                        ((Number) m.get("t_us")).longValue(), "", Map.of()));
            }
            String block = Persona.contextBlock(state, events, now, (String) c.get("home"));
            assertThat(block).as("case %d", n++).isEqualTo(c.get("block"));
            assertThat(Persona.userMessage("Quelle heure est-il ?", block, "fr")).isEqualTo(c.get("message_fr"));
            assertThat(Persona.userMessage("Hello", block, null)).isEqualTo(c.get("message_none"));
        }
        assertThat(n).isGreaterThan(20);
    }

    @Test
    void textCleaning() {
        Map<String, Object> c = map(v.get("clean"));
        for (Object o : list(c.get("clean_for_speech"))) {
            Map<String, Object> m = map(o);
            assertThat(SpeechText.cleanForSpeech((String) m.get("in"))).as((String) m.get("in")).isEqualTo(m.get("out"));
        }
        for (Object o : list(c.get("strip_payload"))) {
            Map<String, Object> m = map(o);
            assertThat(SpeechText.stripPayload((String) m.get("in"))).as((String) m.get("in")).isEqualTo(m.get("out"));
        }
        for (Object o : list(c.get("strip_thinking"))) {
            Map<String, Object> m = map(o);
            assertThat(SpeechText.stripThinking((String) m.get("in"))).as((String) m.get("in")).isEqualTo(m.get("out"));
        }
        for (Object o : list(c.get("looks_like_payload"))) {
            Map<String, Object> m = map(o);
            assertThat(SpeechText.looksLikePayload((String) m.get("in"))).as((String) m.get("in")).isEqualTo(m.get("out"));
        }
        for (Object o : list(c.get("payload_tool_calls"))) {
            Map<String, Object> m = map(o);
            String in = (String) m.get("in");
            List<Map<String, Object>> found = SpeechText.payloadToolCalls(in);
            assertThat(found).as(in).isEqualTo(m.get("out"));
            List<Object> calls = new ArrayList<>();
            for (Map<String, Object> f : found) {
                ToolCall call = ToolCall.parse(f);
                if (call == null) {
                    calls.add(null);
                } else {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("name", call.name());
                    cm.put("arguments", call.arguments());
                    cm.put("id", call.id());
                    calls.add(cm);
                }
            }
            assertThat(calls).as(in).isEqualTo(m.get("calls"));
        }
    }

    @Test
    void sentenceSplitting() {
        for (Object o : list(v.get("splitter"))) {
            Map<String, Object> c = map(o);
            SentenceSplitter sp = new SentenceSplitter(((Number) c.get("min_chars")).intValue(),
                    ((Number) c.get("first_clause_words")).intValue());
            List<Object> pieces = list(c.get("pieces"));
            List<Object> steps = list(c.get("steps"));
            for (int i = 0; i < pieces.size(); i++) {
                assertThat(sp.feed((String) pieces.get(i))).as("%s %s", pieces, c.get("min_chars")).isEqualTo(steps.get(i));
            }
            assertThat(sp.flush()).as("%s flush", pieces).isEqualTo(c.get("flush"));
        }
    }

    @Test
    void languageGuesses() {
        for (Object o : list(v.get("language"))) {
            Map<String, Object> c = map(o);
            assertThat(SpeechText.guessLanguage((String) c.get("in"), List.of("fr", "en"))).as((String) c.get("in"))
                    .isEqualTo(c.get("out"));
        }
    }

    @Test
    void settingsChecks() {
        Map<String, Object> s = map(v.get("settings"));
        for (Object o : list(s.get("ok"))) {
            Map<String, Object> c = map(o);
            assertThat(VoiceSettings.validate(map(c.get("in")))).as("%s", c.get("in")).isEqualTo(c.get("out"));
        }
        for (Object o : list(s.get("bad"))) {
            Map<String, Object> c = map(o);
            assertThatThrownBy(() -> VoiceSettings.validate(map(c.get("in")))).as("%s", c.get("in"))
                    .isInstanceOf(VoiceSettings.InvalidVoiceSettingException.class).hasMessage((String) c.get("error"));
        }
        assertThat(VoiceSettings.appSettings(Map.of())).isEqualTo(s.get("app_defaults"));
        assertThat(new ArrayList<>(VoiceSettings.appSettings(Map.of()).keySet()))
                .isEqualTo(new ArrayList<>(map(s.get("app_defaults")).keySet()));
    }

    @Test
    void theToolsListIsThePythonOneByteForByte() {
        Map<String, Object> t = map(v.get("tools"));
        ToolSpec weather = Weather.spec();
        assertThat(JsonText.write(List.of(weather.schema()))).isEqualTo(t.get("ollama_tools_json"));
        Map<String, Object> cat = map(list(t.get("catalog")).get(0));
        assertThat(cat.get("name")).isEqualTo(weather.name());
        assertThat(cat.get("description")).isEqualTo(weather.description());
        assertThat(cat.get("online")).isEqualTo(weather.online());
    }

    @Test
    void memoryIsHalvedWhenFull() {
        Map<String, Object> m = map(v.get("memory"));
        ConversationMemory memory = new ConversationMemory(((Number) m.get("turns")).intValue(), 180);
        List<Object> steps = list(m.get("steps"));
        for (int i = 0; i < steps.size(); i++) {
            List<ChatMessage> exchange = i % 3 == 1
                    ? List.of(ChatMessage.assistant("", List.of(new ToolCall("get_weather", Map.of(), null))),
                            ChatMessage.tool("get_weather", "{}"))
                    : List.of();
            memory.remember("q" + i, "a" + i, exchange, i);
            List<List<String>> got = memory.messages(i).stream().map(x -> List.of(x.role(), x.content())).toList();
            assertThat(got).as("turn %d", i).isEqualTo(steps.get(i));
        }
        assertThat(memory.messages(1000)).isEmpty();
    }

    @Test
    void proactiveSpeech() {
        ProactiveSpeech sp = new ProactiveSpeech(true, true);
        for (Object o : list(v.get("proactive"))) {
            Map<String, Object> c = map(o);
            Map<String, Double> data = new LinkedHashMap<>();
            map(c.get("data")).forEach((k, x) -> data.put(k, ((Number) x).doubleValue()));
            PresenceEvent ev = new PresenceEvent(EventKind.fromWireName((String) c.get("kind")).orElseThrow(),
                    ((Number) c.get("t_us")).longValue(), "", data);
            double t = ((Number) c.get("t")).doubleValue();
            String said = sp.onEvent(ev, "fr", t).orElse(null);
            if (said != null) {
                sp.spoken(t);
            }
            assertThat(said).as("%s", c).isEqualTo(c.get("said"));
        }
    }
}
