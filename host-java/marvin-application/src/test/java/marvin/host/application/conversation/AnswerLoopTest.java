// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.conversation.tool.ToolSpec;
import marvin.host.domain.conversation.tool.Weather;
import marvin.host.domain.shared.JsonText;

/** The answer loop, case for case as the Python host's test_voice_tools.py checks VoiceAssistant._answer. */
class AnswerLoopTest {
    double now;

    static Map<String, Object> echoSchema() {
        return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")), "required", List.of("text"));
    }

    ToolRegistry registry(boolean enabled) {
        ToolRegistry.Tool weather = new ToolRegistry.Tool(Weather.spec(), (args, ctx) -> Map.of("place", "Nice, France",
                "temperature_c", 21));
        ToolRegistry.Tool echo = new ToolRegistry.Tool(new ToolSpec("echo", "Repeats.", echoSchema(), 1, false, false, null),
                (args, ctx) -> args.get("text"));
        return new ToolRegistry(List.of(weather, echo), enabled, true, () -> now);
    }

    /** What was said and what the loop returned. */
    record Run(List<String> said, AnswerLoop.Outcome out, FakeModel model) {
    }

    Run ask(FakeModel model, ToolRegistry tools, int maxRounds) {
        List<String> said = new ArrayList<>();
        List<ChatMessage> messages = List.of(ChatMessage.system(Persona.personaPrompt("fr", tools != null && tools.ollamaTools() != null)),
                ChatMessage.user("question"));
        AnswerLoop loop = new AnswerLoop(model, () -> now += 0.01);
        AnswerLoop.Outcome out = loop.answer("http://ollama", "m", messages, tools, tools != null, "fr", maxRounds, 5,
                () -> false, new AnswerLoop.Speaker() {
                    @Override
                    public void say(String sentence) {
                        if (!SpeechText.cleanForSpeech(sentence).isEmpty()) {      // what the voice would speak
                            said.add(sentence);
                        }
                    }

                    @Override
                    public void filler(String text) {
                        said.add("filler:" + text);
                    }
                }, () -> { });
        return new Run(said, out, model);
    }

    Run ask(FakeModel model) {
        return ask(model, registry(true), 3);
    }

    @Test
    void oneToolCallThenTheAnswer() {
        Run r = ask(FakeModel.of(new ToolCall("get_weather", Map.of("day", "now"), null),
                "Il fait vingt et un degrés, partiellement nuageux."));
        List<ChatMessage> first = r.model.calls.get(0);
        List<ChatMessage> second = r.model.calls.get(1);
        assertThat(r.model.tools.get(0)).isSameAs(r.model.tools.get(1)).isNotNull();
        assertThat(first.get(0).content()).contains("Tools:");
        assertThat(second.subList(0, first.size())).isEqualTo(first);
        ChatMessage call = second.get(first.size());
        ChatMessage result = second.get(first.size() + 1);
        assertThat(call.role()).isEqualTo("assistant");
        assertThat(call.content()).isEmpty();
        assertThat(call.toolCalls()).extracting(ToolCall::name).containsExactly("get_weather");
        assertThat(result.role()).isEqualTo("tool");
        assertThat(result.toolName()).isEqualTo("get_weather");
        assertThat(((Map<?, ?>) JsonText.parse(result.content())).get("temperature_c")).isEqualTo(21L);
        // an online tool: a filler first; the answer to a tool result is held until complete: one piece
        assertThat(r.said).containsExactly("filler:Je regarde…", "Il fait vingt et un degrés, partiellement nuageux.");
        assertThat(r.out.calls()).hasSize(1);
        assertThat(r.out.calls().get(0)).containsEntry("name", "get_weather").containsEntry("ok", true);
        assertThat(r.out.latency()).containsKeys("llm_first_token", "tools", "llm_first_token_2", "first_chunk");
        assertThat(r.out.exchange()).extracting(ChatMessage::role).containsExactly("assistant", "tool");
        assertThat(r.out.saidAnswer()).isEqualTo("Il fait vingt et un degrés, partiellement nuageux.");
    }

    @Test
    void anOfflineToolHasNoFillerAndNoToolsMeansNoToolsPrompt() {
        Run r = ask(FakeModel.of(new ToolCall("echo", Map.of("text", "bonjour"), null), "Bonjour."));
        assertThat(r.said).containsExactly("Bonjour.");
        Run none = ask(FakeModel.of("Bonjour."), null, 3);
        assertThat(none.model.tools).containsExactly((List<Map<String, Object>>) null);
        assertThat(none.model.calls.get(0).get(0).content()).doesNotContain("Tools:").contains("no internet access");
    }

    @Test
    void anUnknownToolIsReportedToTheModel() {
        Run r = ask(FakeModel.of(new ToolCall("turn_on_lights", Map.of(), null), "Je n'ai pas d'interrupteur, hélas."));
        ChatMessage tool = r.model.calls.get(1).get(r.model.calls.get(1).size() - 1);
        assertThat(tool.role()).isEqualTo("tool");
        assertThat(tool.content()).contains("unknown tool 'turn_on_lights'");
        assertThat(r.out.calls().get(0)).containsEntry("ok", false);
        assertThat(String.join(" ", r.said)).isEqualTo("Je n'ai pas d'interrupteur, hélas.");
    }

    @Test
    void toolRoundsAreCapped() {
        FakeModel model = new FakeModel(m -> new ArrayList<>(List.of(new ToolCall("echo", Map.of("text", "encore"), null))));
        Run r = ask(model, registry(true), 3);
        assertThat(model.calls).hasSize(4);
        assertThat(r.out.calls()).hasSize(3);
        assertThat(r.said).containsExactly(Persona.phrase("no_answer", "fr"));
        assertThat(r.out.exchange().stream().filter(m -> m.role().equals("tool"))).hasSize(3);
    }

    @Test
    void aToolCallWrittenAsTextIsRunAndNeverSpoken() {
        String payload = "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"place\": \"Nice\"}}\n</tool_call>";
        Run r = ask(FakeModel.of(payload, "Vingt et un degrés à Nice."));
        String spoken = String.join(" ", r.said);
        assertThat(spoken).doesNotContain("{", "get_weather", "tool_call").endsWith("Vingt et un degrés à Nice.");
        List<ChatMessage> second = r.model.calls.get(1);
        ToolCall call = second.get(second.size() - 2).toolCalls().get(0);
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).isEqualTo(Map.of("place", "Nice"));
        // JSON that is not a tool call is not spoken either; prose starting with a bracket is
        assertThat(ask(FakeModel.of("{\"temperature\": 21}")).said).containsExactly(Persona.phrase("no_answer", "fr"));
        assertThat(String.join(" ", ask(FakeModel.of("[soupir] Bon, d'accord.")).said)).isEqualTo("[soupir] Bon, d'accord.");
    }

    @Test
    void reasoningLeakedAfterAToolResultIsDropped() {
        String leaked = "À Nice, c'est essentiellement dégagé, entre vingt et vingt-cinq degrés.\n</think>\n\n"
                + "À Nice, il fait beau aujourd'hui, entre vingt et vingt-cinq degrés.";
        Run r = ask(FakeModel.of(new ToolCall("get_weather", Map.of("day", "today"), null), leaked));
        assertThat(r.said).containsExactly("filler:Je regarde…",
                "À Nice, il fait beau aujourd'hui, entre vingt et vingt-cinq degrés.");
        assertThat(r.out.saidAnswer()).isEqualTo("À Nice, il fait beau aujourd'hui, entre vingt et vingt-cinq degrés.");
    }

    @Test
    void reasoningLeakedWhileStreamingIsSaidOnce() {
        String text = "C'est comme si l'air retenait la note la plus claire. </think>\n\n"
                + "C'est comme si l'air retenait la note la plus claire.";
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 5) {
            pieces.add(text.substring(i, Math.min(text.length(), i + 5)));
        }
        Run r = ask(FakeModel.of(List.of(pieces)), null, 3);
        String said = String.join(" ", r.said);
        assertThat(said.split("note la plus claire", -1)).hasSize(2);
        assertThat(said).doesNotContain("think");
        // nothing said yet when the tag comes: only the answer after it
        Run r2 = ask(FakeModel.of(List.of(List.of("Bonj", "our</th", "ink>\n\nBonjour !"))), null, 3);
        assertThat(r2.said).containsExactly("Bonjour !");
    }

    @Test
    void theFirstClauseIsSpokenEarly() {
        Run r = ask(FakeModel.of(List.of(List.of("La capitale de la France, ", "c'est Paris. ", "Elle est grande."))), null, 3);
        assertThat(r.said).containsExactly("La capitale de la France,", "c'est Paris.", "Elle est grande.");
    }

    @Test
    void aModelThatIsDownGivesLlmDownWithItsHint() {
        FakeModel model = FakeModel.of("x");
        model.down = true;
        Run r = ask(model);
        assertThat(r.out.failure()).isEqualTo("llm_down");
        assertThat(r.out.hint()).startsWith("cannot reach Ollama at http://ollama: Connection refused.").endsWith("Start Ollama.");
        assertThat(r.said).isEmpty();
    }

    @Test
    void aModelWithoutToolsIsAskedAgainWithoutThem() {
        FakeModel model = FakeModel.of("Bonjour.");
        model.noTools = true;
        boolean[] told = {false};
        List<String> said = new ArrayList<>();
        AnswerLoop.Outcome out = new AnswerLoop(model, () -> now += 0.01).answer("h", "m",
                List.of(ChatMessage.system("s"), ChatMessage.user("u")), registry(true), true, "fr", 3, 5, () -> false,
                new AnswerLoop.Speaker() {
                    @Override
                    public void say(String sentence) {
                        said.add(sentence);
                    }

                    @Override
                    public void filler(String text) {
                    }
                }, () -> told[0] = true);
        assertThat(told[0]).isTrue();
        assertThat(model.tools).containsExactly((List<Map<String, Object>>) null);
        assertThat(said).containsExactly("Bonjour.");
        assertThat(out.failure()).isNull();
    }

    @Test
    void cancellingStopsTheAnswer() {
        FakeModel model = FakeModel.of("Une phrase assez longue. Et une autre phrase encore. Et la dernière phrase.");
        List<String> said = new ArrayList<>();
        AnswerLoop.Outcome out = new AnswerLoop(model, () -> now += 0.01).answer("h", "m", List.of(ChatMessage.user("u")),
                null, false, "fr", 3, 5, () -> said.size() >= 1, new AnswerLoop.Speaker() {
                    @Override
                    public void say(String sentence) {
                        said.add(sentence);
                    }

                    @Override
                    public void filler(String text) {
                    }
                }, () -> { });
        assertThat(said).containsExactly("Une phrase assez longue.");
        assertThat(out.answer()).hasSize(1);
    }
}
