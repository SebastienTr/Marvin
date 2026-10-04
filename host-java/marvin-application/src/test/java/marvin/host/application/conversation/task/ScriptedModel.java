// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ModelThinking;
import marvin.host.domain.conversation.ToolCall;

/**
 * A model for task tests: each request gets the next reply, a list of parts streamed in order: {@link Think} pieces, text,
 * tool calls, and {@link #HOLD} (the request then waits, as a model thinking at length, until it is cancelled or
 * {@link #release()}d). Records each request's messages, tools and thinking.
 */
final class ScriptedModel implements LanguageModel {
    /** A piece of thinking. */
    record Think(String text) {
    }

    /** The request waits here until cancelled or released. */
    static final Object HOLD = new Object();

    final List<List<ChatMessage>> calls = new CopyOnWriteArrayList<>();
    final List<List<Map<String, Object>>> tools = new CopyOnWriteArrayList<>();
    final List<Thinking> thinking = new CopyOnWriteArrayList<>();
    /** Requests a cancel stopped. */
    final List<Integer> cut = new CopyOnWriteArrayList<>();
    private final Function<List<ChatMessage>, List<Object>> replies;
    volatile RuntimeException fail;
    volatile CountDownLatch held = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);
    volatile Usage usage = new Usage(500, 0.2, 120, 2.0, 0);

    ScriptedModel(Function<List<ChatMessage>, List<Object>> replies) {
        this.replies = replies;
    }

    static ScriptedModel of(Object... script) {
        List<Object> left = new ArrayList<>(List.of(script));
        return new ScriptedModel(m -> {
            Object r = left.isEmpty() ? "" : left.remove(0);
            return r instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(List.of(r));
        });
    }

    void release() {
        release.countDown();
    }

    @Override
    public void streamChat(String host, String model, List<ChatMessage> messages, List<Map<String, Object>> t,
                           double timeoutS, Stream stream) {
        streamChat(host, model, messages, t, timeoutS, Thinking.OFF, stream);
    }

    @Override
    public void streamChat(String host, String model, List<ChatMessage> messages, List<Map<String, Object>> t,
                           double timeoutS, Thinking th, Stream stream) {
        if (fail != null) {
            throw fail;
        }
        int index = calls.size();
        calls.add(List.copyOf(messages));
        tools.add(t);
        thinking.add(th);
        for (Object part : replies.apply(messages)) {
            if (stream.cancelled()) {
                cut.add(index);
                return;
            }
            if (part == HOLD) {
                held.countDown();
                while (!stream.cancelled()) {
                    try {
                        if (release.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            release = new CountDownLatch(1);
                            break;
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (stream.cancelled()) {
                    cut.add(index);
                    return;
                }
            } else if (part instanceof Think k) {
                stream.thinking(k.text());
            } else if (part instanceof ToolCall c) {
                stream.toolCall(c);
            } else {
                stream.text((String) part);
            }
        }
        if (usage != null) {
            stream.usage(usage);
        }
    }

    @Override
    public List<String> models(String host) {
        return List.of("qwen3.8:27b-mxfp8");
    }

    @Override
    public Set<String> capabilities(String host, String model) {
        return Set.of("completion", "tools", "thinking");
    }

    @Override
    public ModelInfo describe(String host, String model) {
        return new ModelInfo(model, 32_000_000_000L, capabilities(host, model), ModelThinking.of(List.of("thinking"),
                Map.of("values", List.of(false, "low", "medium", "xhigh"), "default", "medium")));
    }

    /** A tool call. */
    static ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall(name, args, null);
    }
}
