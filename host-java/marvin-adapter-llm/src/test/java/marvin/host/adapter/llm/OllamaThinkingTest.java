// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.port.out.LanguageModel.Thinking;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.Reasoning;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.conversation.tool.Weather;

/**
 * Requests that think (background tasks, or the conversation when the owner wants it): the level Ollama is sent for what
 * the model lists, the thinking kept apart from the answer, the fallbacks when Ollama refuses a level, cancelling while
 * Ollama is still silent, and what the app shows of a model.
 */
class OllamaThinkingTest {
    static final String QWEN = "qwen3.8:27b-mxfp8";
    final OllamaLanguageModel model = new OllamaLanguageModel();
    StubOllama stub;

    @AfterEach
    void stop() {
        if (stub != null) {
            stub.close();
        }
    }

    static final class Collect implements LanguageModel.Stream {
        final List<Object> got = new ArrayList<>();
        final StringBuilder thought = new StringBuilder();
        final List<LanguageModel.Usage> usage = new ArrayList<>();
        volatile boolean cancel;

        @Override
        public void text(String piece) {
            got.add(piece);
        }

        @Override
        public void toolCall(ToolCall call) {
            got.add(call);
        }

        @Override
        public void thinking(String piece) {
            thought.append(piece);
        }

        @Override
        public boolean cancelled() {
            return cancel;
        }

        @Override
        public void usage(LanguageModel.Usage u) {
            usage.add(u);
        }
    }

    static List<ChatMessage> messages() {
        return List.of(ChatMessage.system("You are Marvin's agent."), ChatMessage.user("Compare two trains."));
    }

    StubOllama qwen() throws Exception {
        StubOllama s = new StubOllama();
        s.models = List.of(QWEN, "qwen3:4b-instruct");
        s.thinks = Set.of(QWEN);
        s.thinkingOf = Map.of(QWEN, Map.of("values", List.of(false, "low", "medium", "xhigh"), "default", "medium"));
        return s;
    }

    @Test
    void offIsTheSpokenRequestByteForByte() throws Exception {
        stub = qwen();
        stub.chat = r -> StubOllama.text("Bonjour.");
        model.streamChat(stub.url(), QWEN, messages(), null, 60, new Collect());
        model.streamChat(stub.url(), QWEN, messages(), null, 60, Thinking.OFF, new Collect());
        assertThat(stub.requests.get(1)).isEqualTo(stub.requests.get(0));
        assertThat(stub.requests.get(1)).containsEntry("think", false);
        assertThat(stub.showRequests).isEmpty();          // nothing to ask the model when it does not think
    }

    @Test
    @SuppressWarnings("unchecked")
    void highAsksQwenForXhighAndTheThinkingComesApart() throws Exception {
        stub = qwen();
        stub.chat = r -> StubOllama.thought(List.of("The user wants ", "a comparison."), "Here ", "it is.");
        Collect c = new Collect();
        model.streamChat(stub.url(), QWEN, messages(), List.of(Weather.spec().schema()), 120,
                new Thinking(Reasoning.HIGH, Thinking.TASK_TOKENS), c);
        assertThat(c.got).containsExactly("Here ", "it is.");
        assertThat(c.thought).hasToString("The user wants a comparison.");
        assertThat(c.usage).hasSize(1);
        Map<String, Object> body = stub.requests.get(0);
        assertThat(body).containsEntry("think", "xhigh").containsEntry("stream", true).containsEntry("keep_alive", "30m")
                .containsEntry("options", Map.of("temperature", 0.6, "num_predict", (long) Thinking.TASK_TOKENS,
                        "num_ctx", (long) OllamaLanguageModel.NUM_CTX));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(((Map<String, Object>) tools.get(0).get("function")).get("name")).isEqualTo("get_weather");
        // the levels the model lists are asked for once
        model.streamChat(stub.url(), QWEN, messages(), null, 120, new Thinking(Reasoning.LOW, 1000), new Collect());
        assertThat(stub.requests.get(1)).containsEntry("think", "low");
        assertThat(stub.showRequests).containsExactly(QWEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMessagesAreWrittenAsSpringAiWritesThem() throws Exception {
        stub = qwen();
        stub.chat = r -> StubOllama.text("ok");
        List<ChatMessage> msgs = List.of(ChatMessage.system("S"), ChatMessage.user("Q"),
                ChatMessage.assistant("", List.of(new ToolCall("get_weather", Map.of("day", "now"), null))),
                ChatMessage.tool("get_weather", "{\"temperature_c\": 21}"));
        model.streamChat(stub.url(), QWEN, msgs, null, 60, new Collect());
        model.streamChat(stub.url(), QWEN, msgs, null, 60, new Thinking(Reasoning.MEDIUM, 400), new Collect());
        assertThat(stub.requests.get(1).get("messages")).isEqualTo(stub.requests.get(0).get("messages"));
        assertThat(stub.requests.get(1)).containsEntry("think", "medium");
        List<Map<String, Object>> sent = (List<Map<String, Object>>) stub.requests.get(1).get("messages");
        assertThat(sent.get(2).get("tool_calls")).isEqualTo(List.of(Map.of("function",
                Map.of("name", "get_weather", "arguments", Map.of("day", "now")))));
    }

    @Test
    void aRefusedLevelFallsBackToOnThenOffAndIsRemembered() throws Exception {
        stub = new StubOllama();
        stub.models = List.of("old:7b");
        stub.thinks = Set.of("old:7b");                  // no descriptor: an older server
        stub.chat = r -> r.get("think") instanceof String
                ? List.of("!400 {\"error\": \"invalid think value: \\\"high\\\"\"}") : StubOllama.text("fine");
        Collect c = new Collect();
        model.streamChat(stub.url(), "old:7b", messages(), null, 60, new Thinking(Reasoning.HIGH, 2000), c);
        assertThat(c.got).containsExactly("fine");
        assertThat(stub.requests).extracting(r -> r.get("think")).containsExactly("high", true);
        model.streamChat(stub.url(), "old:7b", messages(), null, 60, new Thinking(Reasoning.HIGH, 2000), new Collect());
        assertThat(stub.requests.get(2)).containsEntry("think", true);

        stub.chat = r -> Boolean.TRUE.equals(r.get("think"))
                ? List.of("!400 {\"error\": \"\\\"plain:3b\\\" does not support thinking\"}") : StubOllama.text("plain");
        stub.models = List.of("plain:3b");
        Collect p = new Collect();
        model.streamChat(stub.url(), "plain:3b", messages(), null, 60, new Thinking(Reasoning.MEDIUM, 2000), p);
        assertThat(p.got).containsExactly("plain");
        assertThat(stub.requests.subList(3, stub.requests.size())).extracting(r -> r.get("think")).containsExactly(false);
    }

    @Test
    void otherErrorsAreTheUsualOnes() throws Exception {
        stub = qwen();
        stub.chat = r -> List.of("!400 {\"error\": \"registry.ollama.ai/library/x does not support tools\"}");
        assertThatThrownBy(() -> model.streamChat(stub.url(), QWEN, messages(), List.of(Weather.spec().schema()), 60,
                new Thinking(Reasoning.HIGH, 1000), new Collect())).isInstanceOf(LanguageModel.ToolsUnsupported.class);
        stub.chat = r -> List.of("!500 {\"error\": \"out of memory\"}");
        assertThatThrownBy(() -> model.streamChat(stub.url(), QWEN, messages(), null, 60,
                new Thinking(Reasoning.HIGH, 1000), new Collect())).isInstanceOf(LanguageModel.Unavailable.class)
                .hasMessage("Ollama error 500: out of memory");
        String url = stub.url();
        stub.close();
        stub = null;
        assertThatThrownBy(() -> model.streamChat(url, QWEN, messages(), null, 5, new Thinking(Reasoning.HIGH, 1000),
                new Collect())).isInstanceOf(LanguageModel.Unavailable.class).hasMessageStartingWith("cannot reach Ollama")
                .satisfies(e -> assertThat(((LanguageModel.Unavailable) e).hint()).contains("ollama pull"));
    }

    @Test
    void cancellingWhileOllamaIsSilentStopsAtOnce() throws Exception {
        stub = qwen();
        stub.chat = r -> StubOllama.text("late");
        model.streamChat(stub.url(), QWEN, messages(), null, 60, new Thinking(Reasoning.HIGH, 1000), new Collect());
        stub.firstChunkDelayMs = 3000;                   // reading a long prompt
        Collect c = new Collect();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            c.cancel = true;
        });
        long t = System.nanoTime();
        model.streamChat(stub.url(), QWEN, messages(), null, 60, new Thinking(Reasoning.HIGH, 1000), c);
        assertThat((System.nanoTime() - t) / 1e6).isLessThan(1500);
        assertThat(c.got).isEmpty();
    }

    @Test
    void aSilentServerTimesOut() throws Exception {
        stub = qwen();
        stub.chat = r -> StubOllama.text("late");
        stub.firstChunkDelayMs = 3000;
        long t = System.nanoTime();
        assertThatThrownBy(() -> model.streamChat(stub.url(), QWEN, messages(), null, 0.5,
                new Thinking(Reasoning.HIGH, 1000), new Collect())).isInstanceOf(LanguageModel.Unavailable.class);
        assertThat((System.nanoTime() - t) / 1e6).isLessThan(2500);
    }

    @Test
    void describeSaysTheSizeTheCapabilitiesAndTheLevels() throws Exception {
        stub = qwen();
        stub.sizes = Map.of(QWEN, 32_000_000_000L);
        LanguageModel.ModelInfo info = model.describe(stub.url(), QWEN);
        assertThat(info.sizeBytes()).isEqualTo(32_000_000_000L);
        assertThat(info.capabilities()).contains("thinking", "tools");
        assertThat(info.thinking().thinks()).isTrue();
        assertThat(info.thinking().levels()).containsExactly("low", "medium", "xhigh");
        assertThat(Reasoning.HIGH.thinkValue(info.thinking())).isEqualTo("xhigh");
        LanguageModel.ModelInfo small = model.describe(stub.url(), "qwen3:4b-instruct");
        assertThat(small.sizeBytes()).isEqualTo(-1);
        assertThat(small.thinking().thinks()).isFalse();
        String url = stub.url();
        stub.close();
        stub = null;
        assertThat(model.describe(url, QWEN).thinking()).isEqualTo(marvin.host.domain.conversation.ModelThinking.UNKNOWN);
    }
}
