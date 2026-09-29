// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ToolCall;

/**
 * A scripted model (the Python host's {@code FakeLLM}): each request gets the next reply, a list of text
 * pieces and tool calls streamed in order (a plain string is streamed in pieces of 7 characters).
 */
final class FakeModel implements LanguageModel {
    final List<List<ChatMessage>> calls = new ArrayList<>();
    final List<List<Map<String, Object>>> tools = new ArrayList<>();
    private final Function<List<ChatMessage>, List<Object>> replies;
    boolean down;
    boolean noTools;
    List<String> models = List.of("qwen3:4b-instruct");
    /** What the model server reports at the end of each answer ({@code null}: nothing, as before). */
    volatile Function<List<ChatMessage>, Usage> usage;
    /** When each request came (System.nanoTime). */
    final List<Long> callNanos = new java.util.concurrent.CopyOnWriteArrayList<>();

    FakeModel(Function<List<ChatMessage>, List<Object>> replies) {
        this.replies = replies;
    }

    static FakeModel of(Object... script) {
        List<Object> left = new ArrayList<>(List.of(script));
        return new FakeModel(m -> {
            Object r = left.isEmpty() ? "" : left.remove(0);
            return r instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(List.of(r));
        });
    }

    @Override
    public void streamChat(String host, String model, List<ChatMessage> messages, List<Map<String, Object>> t,
                           double timeoutS, Stream stream) {
        if (down) {
            throw new Unavailable("cannot reach Ollama at " + host + ": Connection refused", "Start Ollama.");
        }
        if (noTools && t != null) {
            throw new ToolsUnsupported(model + " cannot use tools: " + model + " does not support tools");
        }
        callNanos.add(System.nanoTime());
        modelNames.add(model);
        calls.add(List.copyOf(messages));
        tools.add(t);
        for (Object part : replies.apply(messages)) {
            if (stream.cancelled()) {
                return;
            }
            if (part instanceof ToolCall c) {
                stream.toolCall(c);
            } else if (part instanceof List<?> pieces) {
                for (Object p : pieces) {
                    stream.text((String) p);
                }
            } else {
                String s = (String) part;
                for (int i = 0; i < s.length() && !stream.cancelled(); i += 7) {
                    stream.text(s.substring(i, Math.min(s.length(), i + 7)));
                }
            }
        }
        Function<List<ChatMessage>, Usage> u = usage;
        if (u != null) {
            stream.usage(u.apply(messages));
        }
    }

    /** What each model can do; a model not here can only write. */
    volatile Map<String, Set<String>> capabilities = Map.of();
    /** The models asked for their capabilities, in order. */
    final List<String> capabilityCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The model name of each request, in order. */
    final List<String> modelNames = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public Set<String> capabilities(String host, String model) {
        if (down) {
            throw new Unavailable("cannot reach Ollama at " + host + ": Connection refused", "Start Ollama.");
        }
        capabilityCalls.add(model);
        return capabilities.getOrDefault(model, Set.of("completion"));
    }

    @Override
    public List<String> models(String host) {
        if (down) {
            throw new Unavailable("Connection refused", "");
        }
        return models;
    }
}
