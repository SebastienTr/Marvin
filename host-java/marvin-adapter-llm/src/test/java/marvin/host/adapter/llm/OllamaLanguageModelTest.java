// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.conversation.tool.Weather;

/** Spring AI's Ollama client against a stub Ollama: the request body, streaming, tool calls and errors. */
class OllamaLanguageModelTest {
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
        int cancelAfter = Integer.MAX_VALUE;

        @Override
        public void text(String piece) {
            got.add(piece);
        }

        @Override
        public void toolCall(ToolCall call) {
            got.add(call);
        }

        @Override
        public boolean cancelled() {
            return got.size() >= cancelAfter;
        }
    }

    List<ChatMessage> messages() {
        return List.of(ChatMessage.system("You are Marvin."), ChatMessage.user("Bonjour."),
                ChatMessage.assistant("", List.of(new ToolCall("get_weather", Map.of("day", "now"), null))),
                ChatMessage.tool("get_weather", "{\"temperature_c\": 21}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRequestHasTheSameOptionsAndStreamsTheAnswer() throws Exception {
        stub = new StubOllama();
        stub.chat = r -> StubOllama.text("Il fait ", "vingt et un ", "degrés.");
        Collect c = new Collect();
        model.streamChat(stub.url(), "qwen3:4b-instruct", messages(), List.of(Weather.spec().schema()), 60, c);
        assertThat(c.got).containsExactly("Il fait ", "vingt et un ", "degrés.");
        Map<String, Object> body = stub.requests.get(0);
        assertThat(body).containsEntry("model", "qwen3:4b-instruct").containsEntry("stream", true)
                .containsEntry("keep_alive", "30m").containsEntry("think", false)
                .containsEntry("options", Map.of("temperature", 0.6, "num_predict", 200L, "num_ctx", 8192L));
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) body.get("messages");
        assertThat(msgs).extracting(m -> m.get("role")).containsExactly("system", "user", "assistant", "tool");
        assertThat(msgs.get(2).get("tool_calls")).isEqualTo(List.of(Map.of("function",
                Map.of("name", "get_weather", "arguments", Map.of("day", "now")))));
        assertThat(msgs.get(3)).containsEntry("tool_name", "get_weather").containsEntry("content", "{\"temperature_c\": 21}");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).containsEntry("type", "function");
        Map<String, Object> fn = (Map<String, Object>) tools.get(0).get("function");
        assertThat(fn).containsEntry("name", "get_weather").containsEntry("description", Weather.DESCRIPTION);
        assertThat(fn.get("parameters")).isEqualTo(Weather.spec().schema().get("function") instanceof Map<?, ?> f
                ? f.get("parameters") : null);
        // without tools, an empty list (Spring AI's request always has the key; Ollama reads it as none)
        model.streamChat(stub.url(), "qwen3:4b-instruct", messages(), null, 60, new Collect());
        assertThat(stub.requests.get(1).getOrDefault("tools", List.of())).isEqualTo(List.of());
    }

    @Test
    void toolCallsArePassedOn() throws Exception {
        stub = new StubOllama();
        stub.chat = r -> StubOllama.toolCall("get_weather", Map.of("place", "Paris", "day", "tomorrow"));
        Collect c = new Collect();
        model.streamChat(stub.url(), "m", messages(), List.of(Weather.spec().schema()), 60, c);
        assertThat(c.got).hasSize(1);
        ToolCall call = (ToolCall) c.got.get(0);
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).isEqualTo(Map.of("place", "Paris", "day", "tomorrow"));
    }

    @Test
    void readingStopsWhenCancelled() throws Exception {
        stub = new StubOllama();
        stub.chat = r -> StubOllama.text("warm");
        model.streamChat(stub.url(), "m", messages(), null, 60, new Collect());       // the client is ready
        stub.delayMs = 50;
        stub.chat = r -> StubOllama.text("a", "b", "c", "d", "e", "f", "g", "h");
        Collect c = new Collect();
        c.cancelAfter = 2;
        long t = System.nanoTime();
        model.streamChat(stub.url(), "m", messages(), null, 60, c);
        assertThat(c.got).containsExactly("a", "b");
        assertThat((System.nanoTime() - t) / 1e6).isLessThan(350);
    }

    @Test
    void errorsSayWhatToDo() throws Exception {
        stub = new StubOllama();
        stub.chat = r -> List.of("!400 {\"error\": \"registry.ollama.ai/library/gemma3:1b does not support tools\"}");
        assertThatThrownBy(() -> model.streamChat(stub.url(), "gemma3:1b", messages(), List.of(Weather.spec().schema()), 60,
                new Collect())).isInstanceOf(LanguageModel.ToolsUnsupported.class)
                .hasMessage("gemma3:1b cannot use tools: registry.ollama.ai/library/gemma3:1b does not support tools");
        stub.chat = r -> List.of("!404 {\"error\": \"model 'nope' not found\"}");
        assertThatThrownBy(() -> model.streamChat(stub.url(), "nope", messages(), null, 60, new Collect()))
                .isInstanceOf(LanguageModel.Unavailable.class).hasMessage("Ollama has no model 'nope': model 'nope' not found")
                .extracting(e -> ((LanguageModel.Unavailable) e).hint()).isEqualTo("Run `ollama pull nope`.");
        stub.chat = r -> List.of("!500 {\"error\": \"out of memory\"}");
        assertThatThrownBy(() -> model.streamChat(stub.url(), "m", messages(), null, 60, new Collect()))
                .isInstanceOf(LanguageModel.Unavailable.class).hasMessage("Ollama error 500: out of memory");
        String url = stub.url();
        stub.close();
        stub = null;
        assertThatThrownBy(() -> model.streamChat(url, "m", messages(), null, 5, new Collect()))
                .isInstanceOf(LanguageModel.Unavailable.class).hasMessageStartingWith("cannot reach Ollama at " + url)
                .extracting(e -> ((LanguageModel.Unavailable) e).hint()).asString().contains("ollama pull m");
        assertThatThrownBy(() -> model.models(url)).isInstanceOf(LanguageModel.Unavailable.class);
    }

    @Test
    void modelsAreListed() throws Exception {
        stub = new StubOllama();
        stub.models = List.of("qwen3:4b-instruct", "llama3.2:3b");
        assertThat(model.models(stub.url())).containsExactly("qwen3:4b-instruct", "llama3.2:3b");
    }
}
