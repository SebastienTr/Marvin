// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.adapter.llm.OllamaLanguageModel;
import marvin.host.adapter.llm.StubOllama;
import marvin.host.application.conversation.AnswerLoop;
import marvin.host.application.conversation.tools.MemoryTools;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.application.conversation.tools.WeatherTool;
import marvin.host.application.memory.ForgetConfirmations;
import marvin.host.application.memory.MemoryRecallService;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactOrigin;
import marvin.host.domain.memory.RetrievalScoring;

/**
 * The memory tools through the real answer loop and Ollama adapter, against the stub Ollama: the model calls
 * {@code remember}, {@code recall} and {@code forget} as it would, the tools reach memory through the conversation's
 * port, and forgetting waits for the owner's yes in a later turn.
 */
class MemoryToolsLoopTest {
    static final Instant T = Instant.parse("2026-09-29T10:00:00Z");
    static final Pattern CODE = Pattern.compile("\"confirm\": \"([A-Z0-9]{6})\"");
    final StubOllama stub;
    final MemoryFixture m = new MemoryFixture(T, new FakeMemoryModel());
    final MemoryRecallService recall = new MemoryRecallService(m.store.facts, m.store.log, m.store.episodes, m.store.profiles,
            m.embeddings, m.days, m.clock, new RetrievalScoring(1.0, 0.5, 0.7, 0.995, 0.3), () -> { });
    final ForgetConfirmations forgetting = new ForgetConfirmations(m.store.facts, m.admin, m.embeddings, m.clock, UUID::randomUUID);
    final MemoryForConversation memory = new MemoryForConversation(recall, m.admin, forgetting);
    final double[] clock = {0};
    /** Internet off: the weather is not offered, memory's tools are (they are local). */
    final ToolRegistry tools;
    final AnswerLoop loop = new AnswerLoop(new OllamaLanguageModel(), () -> clock[0] += 0.001);
    final List<ChatMessage> history = new ArrayList<>();

    MemoryToolsLoopTest() throws Exception {
        stub = new StubOllama();
        List<ToolRegistry.Tool> all = new ArrayList<>(MemoryTools.tools(memory));
        all.add(WeatherTool.tool("", (u, p, t) -> Map.of(), () -> clock[0]));
        tools = new ToolRegistry(all, true, false, () -> clock[0]);
    }

    @AfterEach
    void close() {
        stub.close();
        recall.close();
    }

    AnswerLoop.Outcome turn(long n, String question) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Marvin."));
        messages.addAll(history);
        messages.add(ChatMessage.user(question));
        AnswerLoop.Outcome out = loop.answer(stub.url(), "qwen3:4b-instruct", messages, tools, true, "en", 3, 5, () -> false,
                new AnswerLoop.Speaker() {
                    @Override
                    public void say(String sentence) {
                    }

                    @Override
                    public void filler(String text) {
                    }
                }, () -> { }, Map.of(MemoryTools.TURN, n, MemoryTools.OTHERS_PRESENT, false));
        history.add(ChatMessage.user(question));
        history.addAll(out.exchange());
        history.add(ChatMessage.assistant(out.saidAnswer()));
        return out;
    }

    /** The tool results the model read in a request. */
    @SuppressWarnings("unchecked")
    static List<String> toolResults(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) body.get("messages")) {
            Map<String, Object> msg = (Map<String, Object>) o;
            if ("tool".equals(msg.get("role"))) {
                out.add((String) msg.get("content"));
            }
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void rememberRecallAndForgetThroughTheAnswerLoop() {
        // turn 1: "remember that my bike is blue"
        stub.chat = body -> toolResults(body).isEmpty()
                ? StubOllama.toolCall("remember", Map.of("statement", "The owner's bike is blue."))
                : StubOllama.text("I will remember that.");
        AnswerLoop.Outcome one = turn(1, "Remember that my bike is blue.");
        assertThat(one.failure()).isNull();
        assertThat(one.saidAnswer()).isEqualTo("I will remember that.");
        List<Map<String, Object>> tl = (List<Map<String, Object>>) stub.requests.getFirst().get("tools");
        assertThat(tl).extracting(t -> ((Map<String, Object>) t.get("function")).get("name"))
                .containsExactly("forget", "recall", "remember");
        Fact bike = m.store.facts.rows.values().stream().filter(f -> f.statement().equals("The owner's bike is blue."))
                .findFirst().orElseThrow();
        assertThat(bike.origin()).isEqualTo(FactOrigin.OWNER);
        assertThat(bike.confidence()).isEqualTo(1.0);
        assertThat(one.calls()).singleElement().satisfies(c -> assertThat(c).containsEntry("name", "remember").containsEntry("ok", true));

        // turn 2: "what colour is my bike?"
        stub.requests.clear();
        stub.chat = body -> toolResults(body).size() == 1
                ? StubOllama.toolCall("recall", Map.of("query", "bike colour"))
                : StubOllama.text("Your bike is blue.");
        AnswerLoop.Outcome two = turn(2, "What colour is my bike?");
        assertThat(two.saidAnswer()).isEqualTo("Your bike is blue.");
        String recalled = toolResults(stub.requests.getLast()).getLast();
        assertThat(recalled).contains("The owner's bike is blue.").contains("the owner told you on 29 September 2026");

        // turn 3: "forget my bike": proposed; the model trying to confirm at once is refused
        stub.requests.clear();
        stub.chat = body -> {
            List<String> results = toolResults(body);
            if (results.size() == 2) {
                return StubOllama.toolCall("forget", Map.of("query", "bike"));
            }
            Matcher code = CODE.matcher(results.getLast());
            if (results.size() == 3 && code.find()) {
                return StubOllama.toolCall("forget", Map.of("query", "bike", "confirm", code.group(1)));
            }
            return StubOllama.text("Shall I forget that your bike is blue?");
        };
        AnswerLoop.Outcome three = turn(3, "Forget about my bike.");
        assertThat(three.calls()).hasSize(2);
        assertThat(three.calls().get(0)).containsEntry("ok", true);
        assertThat(three.calls().get(1)).containsEntry("ok", false);
        assertThat((String) three.calls().get(1).get("error")).contains("has not confirmed yet");
        assertThat(m.store.facts.get(bike.id())).isPresent();
        assertThat(forgetting.pending()).hasSize(1);

        // turn 4: "yes": the code from the history confirms it
        stub.requests.clear();
        String proposal = history.stream().filter(msg -> "tool".equals(msg.role()) && msg.content().contains("\"confirm\""))
                .findFirst().orElseThrow().content();
        Matcher mc = CODE.matcher(proposal);
        assertThat(mc.find()).isTrue();
        String code = mc.group(1);
        stub.chat = body -> toolResults(body).size() == 4
                ? StubOllama.toolCall("forget", Map.of("query", "bike", "confirm", code))
                : StubOllama.text("Done, I forgot it.");
        AnswerLoop.Outcome four = turn(4, "Yes.");
        assertThat(four.calls()).singleElement().satisfies(c -> assertThat(c).containsEntry("ok", true));
        assertThat(toolResults(stub.requests.getLast()).getLast()).contains("\"forgotten\": 1");
        assertThat(m.store.facts.get(bike.id())).isEmpty();
        assertThat(four.saidAnswer()).isEqualTo("Done, I forgot it.");
    }
}
