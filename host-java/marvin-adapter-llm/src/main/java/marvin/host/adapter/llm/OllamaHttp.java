// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.shared.JsonText;

/**
 * Ollama's HTTP API without Spring AI, for what its client cannot say: {@code think} with any level the model lists
 * (Spring AI 2.0's {@code ThinkLevel} accepts only low, medium and high, and Qwen3.8's strongest is {@code xhigh}), the
 * thinking streamed apart from the answer, and {@code /api/show}'s {@code thinking} descriptor. The request body is the
 * one Spring AI writes for the same messages and tools, so a model sees the same prompt either way.
 *
 * <p>A streamed answer is read line by line (newline-delimited JSON). The connection closes as soon as the caller
 * cancels, even while Ollama is still reading the prompt and sends nothing: Ollama then stops the request, so a spoken
 * question waiting behind a background task gets the model at once.
 */
final class OllamaHttp {
    /** How often a running request looks whether it was cancelled. */
    static final long WATCH_MS = 50;

    private final HttpClient client = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)             // Ollama is on this computer or the local network
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    /** An HTTP error from Ollama, with its {@code error} message. */
    static final class Failed extends RuntimeException {
        final int status;
        final String error;

        Failed(int status, String error) {
            super("Ollama error " + status + ": " + (error.isEmpty() ? "failed" : error));
            this.status = status;
            this.error = error;
        }
    }

    private static String base(String host) {
        return host.replaceAll("/+$", "");
    }

    /** {@code POST /api/show}: the model's description. @throws Failed, RuntimeException on network errors */
    Map<?, ?> show(String host, String model, double timeoutS) {
        return post(host, "/api/show", Map.of("model", model), timeoutS);
    }

    /** {@code GET /api/tags}: the installed models. */
    Map<?, ?> tags(String host, double timeoutS) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base(host) + "/api/tags"))
                .timeout(Duration.ofMillis((long) (timeoutS * 1000))).GET().build();
        return json(send(req));
    }

    private Map<?, ?> post(String host, String path, Object body, double timeoutS) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base(host) + path))
                .timeout(Duration.ofMillis((long) (timeoutS * 1000)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonText.write(body), StandardCharsets.UTF_8)).build();
        return json(send(req));
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() >= 400) {
                throw new Failed(r.statusCode(), errorOf(r.body()));
            }
            return r;
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static Map<?, ?> json(HttpResponse<String> r) {
        return JsonText.parse(r.body()) instanceof Map<?, ?> m ? m : Map.of();
    }

    static String errorOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            if (JsonText.parse(body) instanceof Map<?, ?> m && m.get("error") instanceof String s) {
                return s;
            }
        } catch (IllegalArgumentException e) {
            // not JSON
        }
        return "";
    }

    // ------------------------------------------------------------------ chat

    /** The chat request's body, as Spring AI writes it, with {@code think} set to {@code think}. */
    static Map<String, Object> chatBody(String model, List<ChatMessage> messages, List<Map<String, Object>> tools,
                                        Map<String, Object> options, Object think) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("model", model);
        b.put("messages", messages.stream().map(OllamaHttp::message).toList());
        b.put("stream", true);
        b.put("keep_alive", OllamaLanguageModel.KEEP_ALIVE);
        if (tools != null && !tools.isEmpty()) {
            b.put("tools", tools);
        }
        b.put("options", options);
        b.put("think", think);
        return b;
    }

    static Map<String, Object> message(ChatMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", switch (m.role()) {
            case "system", "assistant", "tool" -> m.role();
            default -> "user";
        });
        out.put("content", m.content());
        if (!m.images().isEmpty()) {
            out.put("images", m.images());
        }
        if (!m.toolCalls().isEmpty()) {
            out.put("tool_calls", m.toolCalls().stream().map(c -> {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", c.name());
                fn.put("arguments", c.argumentsMap());
                Map<String, Object> call = new LinkedHashMap<>();
                if (c.id() != null) {
                    call.put("id", c.id());
                }
                call.put("function", fn);
                return call;
            }).toList());
        }
        if (m.toolName() != null) {
            out.put("tool_name", m.toolName());
        }
        return out;
    }

    /**
     * Streams {@code POST /api/chat} into {@code stream}: thinking, text, tool calls, the usage at the end. The body
     * arrives as a publisher of buffers: a cancel or a timeout cancels it (and the request before its headers), which
     * closes the connection at once, whatever Ollama is doing.
     *
     * @param timeoutS how long Ollama may stay silent: before its first line, and between two lines
     * @throws Failed           Ollama answered with an error status (before any line)
     * @throws LanguageModel.Unavailable an error line in the stream, the server unreachable, or silent for too long
     */
    void chat(String host, Map<String, Object> body, double timeoutS, LanguageModel.Stream stream) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base(host) + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonText.write(body), StandardCharsets.UTF_8)).build();
        long limit = (long) (timeoutS * 1e9);
        long start = System.nanoTime();
        Lines lines = new Lines();
        CompletableFuture<HttpResponse<Void>> f = client.sendAsync(req, info -> {
            lines.status = info.statusCode();
            return HttpResponse.BodySubscribers.fromSubscriber(lines);
        });
        try {
            while (true) {
                String line;
                try {
                    line = lines.next(WATCH_MS);
                } catch (IOException e) {
                    if (stream.cancelled()) {
                        return;
                    }
                    throw new LanguageModel.Unavailable("cannot reach Ollama at " + host + ": " + NetErrors.reason(e), "");
                }
                if (stream.cancelled()) {
                    return;
                }
                if (line == null) {
                    if (lines.ended()) {
                        break;
                    }
                    if (f.isCompletedExceptionally() && !lines.started()) {
                        try {
                            f.join();
                        } catch (java.util.concurrent.CompletionException e) {
                            throw new LanguageModel.Unavailable("cannot reach Ollama at " + host + ": "
                                    + NetErrors.reason(e.getCause() == null ? e : e.getCause()), "");
                        }
                    }
                    if (System.nanoTime() - Math.max(start, lines.lastAt) > limit) {
                        throw new LanguageModel.Unavailable("cannot reach Ollama at " + host + ": no answer for "
                                + fmt(timeoutS) + " s", "");
                    }
                    continue;
                }
                if (lines.status >= 400) {
                    StringBuilder all = new StringBuilder(line);
                    String more;
                    try {
                        while ((more = lines.next(1000)) != null) {
                            all.append(more);
                        }
                    } catch (IOException e) {
                        // what came is the error
                    }
                    throw new Failed(lines.status, errorOf(all.toString()));
                }
                if (line.isBlank() || !(JsonText.parse(line) instanceof Map<?, ?> chunk)) {
                    continue;
                }
                if (chunk.get("error") instanceof String err) {
                    throw new LanguageModel.Unavailable("Ollama error: " + err, "");
                }
                if (chunk.get("message") instanceof Map<?, ?> m) {
                    if (m.get("thinking") instanceof String t && !t.isEmpty()) {
                        stream.thinking(t);
                    }
                    if (m.get("tool_calls") instanceof List<?> calls) {
                        for (Object c : calls) {
                            ToolCall call = ToolCall.parse(c);
                            if (call != null) {
                                stream.toolCall(call);
                            }
                        }
                    }
                    if (m.get("content") instanceof String c && !c.isEmpty()) {
                        stream.text(c);
                    }
                }
                if (Boolean.TRUE.equals(chunk.get("done"))) {
                    if (chunk.get("prompt_eval_count") instanceof Number p) {
                        stream.usage(new LanguageModel.Usage(p.intValue(), seconds(chunk.get("prompt_eval_duration")),
                                chunk.get("eval_count") instanceof Number e ? e.intValue() : 0,
                                seconds(chunk.get("eval_duration")), seconds(chunk.get("load_duration"))));
                    }
                    break;
                }
            }
        } catch (IllegalArgumentException e) {
            throw new LanguageModel.Unavailable("Ollama sent something that is not JSON: " + e.getMessage(), "");
        } finally {
            lines.cancel();                         // Ollama stops the request when its client goes away
            f.cancel(true);
        }
    }

    private static String fmt(double s) {
        return s == Math.rint(s) ? String.valueOf((long) s) : String.valueOf(s);
    }

    /** The body's lines as they arrive, for one reader; {@link #cancel()} closes the connection. */
    static final class Lines implements Flow.Subscriber<List<ByteBuffer>> {
        private static final String END = new String("end");
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        private volatile boolean cancelled;
        private volatile boolean ended;
        private volatile boolean started;
        /** The response's status, once its headers came (0 before). */
        volatile int status;
        volatile long lastAt;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (cancelled) {
                s.cancel();
            } else {
                s.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            started = true;
            lastAt = System.nanoTime();
            for (ByteBuffer b : buffers) {
                while (b.hasRemaining()) {
                    byte c = b.get();
                    if (c == '\n') {
                        queue.add(partial.toString(StandardCharsets.UTF_8));
                        partial.reset();
                    } else {
                        partial.write(c);
                    }
                }
            }
            if (!cancelled) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            queue.add(t instanceof IOException io ? io : new IOException(t.getMessage(), t));
        }

        @Override
        public void onComplete() {
            if (partial.size() > 0) {
                queue.add(partial.toString(StandardCharsets.UTF_8));
                partial.reset();
            }
            queue.add(END);
        }

        /** The next line, {@code null} when none came within {@code ms} or the body ended. */
        String next(long ms) throws IOException {
            if (ended) {
                return null;
            }
            Object o;
            try {
                o = queue.poll(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            if (o == END) {
                ended = true;
                return null;
            }
            if (o instanceof IOException e) {
                ended = true;
                throw e;
            }
            return (String) o;
        }

        boolean ended() {
            return ended;
        }

        boolean started() {
            return started;
        }

        void cancel() {
            cancelled = true;
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }

    private static double seconds(Object nanos) {
        return nanos instanceof Number n ? n.doubleValue() / 1e9 : 0;
    }
}
