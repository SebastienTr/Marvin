// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import marvin.host.domain.shared.JsonText;

/**
 * A stand-in for Ollama on a free local port, for tests (there is no Ollama in CI):
 * <ul>
 *   <li>{@code /api/tags} lists {@link #models};</li>
 *   <li>{@code /api/chat} answers each request with the lines {@link #chat} gives (newline-delimited JSON, flushed one
 *       by one: streamed text, tool calls, or a structured answer with {@link #json}), or with an HTTP error when the
 *       first line is {@code "!<status> <body>"};</li>
 *   <li>{@code /api/show} gives {@link #capabilities} ({@code completion} and {@code tools}, and {@code vision} for the
 *       models in {@link #vision}); a model not in {@link #models} gets Ollama's 404;</li>
 *   <li>{@code /api/embed} answers with {@link #embedding} vectors: deterministic, and texts that share words are
 *       close, which is enough to exercise reconciliation; a model not in {@link #models} gets Ollama's 404.</li>
 * </ul>
 * Request bodies are kept in {@link #requests} (chat) and {@link #embedRequests}; the images chat requests carried,
 * where they were and what they were, in {@link #images}. The {@code done} line of a chat
 * reports a {@code prompt_eval_count} as Ollama does with its prompt cache: only the messages after those the previous
 * request shared, at {@link #CHARS_PER_TOKEN} characters per token, plus the chat template's tokens.
 */
public final class StubOllama implements AutoCloseable {
    public final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> embedRequests = new CopyOnWriteArrayList<>();
    /** The models {@code /api/show} was asked about. */
    public final List<String> showRequests = new CopyOnWriteArrayList<>();
    /** Models that can see images. */
    public volatile Set<String> vision = Set.of();
    /**
     * Each image a chat request carried: {@code request} (its index in {@link #requests}), {@code message} (the index of
     * its message), {@code role}, {@code last} (whether it was on the last message), {@code bytes}, {@code sha256}.
     */
    public final List<Map<String, Object>> images = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    public volatile List<String> models = List.of("qwen3:4b-instruct", "qwen3-embedding:8b");
    public volatile Function<Map<String, Object>, List<String>> chat = r -> List.of();
    public volatile long delayMs = 5;
    /** Silence before the first chunk (Ollama loading a model and reading the prompt). */
    public volatile long firstChunkDelayMs = 0;
    /** Streamed answers the client abandoned (the connection closed while the stub still had lines to send). */
    public final java.util.concurrent.atomic.AtomicInteger abandoned = new java.util.concurrent.atomic.AtomicInteger();
    public volatile int dimensions = 1024;
    /** The stub's "tokenizer". */
    public static final double CHARS_PER_TOKEN = 4.0;
    private volatile List<Object> cached = List.of();

    public StubOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", this::tags);
        server.createContext("/api/chat", this::chat);
        server.createContext("/api/embed", this::embed);
        server.createContext("/api/show", this::show);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A streamed text answer, in pieces, then {@code done}. */
    public static List<String> text(String... pieces) {
        List<String> out = new ArrayList<>();
        for (String p : pieces) {
            out.add(JsonText.write(Map.of("message", Map.of("role", "assistant", "content", p), "done", false)));
        }
        out.add(done(0));
        return out;
    }

    /** A tool call chunk (Ollama sends it whole, with empty content), then {@code done}. */
    public static List<String> toolCall(String name, Map<String, Object> arguments) {
        return List.of(JsonText.write(Map.of("message", Map.of("role", "assistant", "content", "",
                        "tool_calls", List.of(Map.of("function", Map.of("name", name, "arguments", arguments)))), "done", false)),
                done(0));
    }

    /** A structured answer (what the model writes with a {@code format} schema): the JSON in two pieces, then {@code done}. */
    public static List<String> json(Object value) {
        String s = JsonText.write(value);
        int half = s.length() / 2;
        return List.of(JsonText.write(Map.of("message", Map.of("role", "assistant", "content", s.substring(0, half)), "done", false)),
                JsonText.write(Map.of("message", Map.of("role", "assistant", "content", s.substring(half)), "done", false)),
                done(s.length() / 4));
    }

    private static String done(int evalCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", Map.of("role", "assistant", "content", ""));
        m.put("done", true);
        m.put("done_reason", "stop");
        m.put("prompt_eval_count", 100);
        m.put("prompt_eval_duration", 50_000_000L);
        m.put("eval_count", evalCount);
        m.put("total_duration", 120_000_000L);
        return JsonText.write(m);
    }

    /** The system and user messages of a chat request, joined. */
    @SuppressWarnings("unchecked")
    public static String prompt(Map<String, Object> body) {
        StringBuilder b = new StringBuilder();
        if (body.get("messages") instanceof List<?> ms) {
            for (Object o : ms) {
                if (o instanceof Map<?, ?> m && m.get("content") instanceof String c) {
                    b.append(c).append('\n');
                }
            }
        }
        return b.toString();
    }

    private static final Set<String> STOP = Set.of("the", "owner", "and", "for", "with", "that", "this", "has", "have", "are",
            "was", "his", "her", "its", "they", "their", "from", "who", "les", "des", "une", "est");

    /**
     * A deterministic embedding: the text's words (lower case, accents and the endings -s, -ed, -ing removed, short and common
     * words skipped) hashed into {@code dims} buckets, normalised. Texts with words in common are close.
     */
    public static float[] embedding(String text, int dims) {
        float[] v = new float[dims];
        String norm = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        for (String w : norm.split("[^a-z0-9]+")) {
            if (w.length() < 3 || STOP.contains(w)) {
                continue;
            }
            String stem = w.length() > 5 && w.endsWith("ing") ? w.substring(0, w.length() - 3)
                    : w.length() > 4 && w.endsWith("ed") ? w.substring(0, w.length() - 2)
                    : w.length() > 4 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
            int h = stem.hashCode();
            v[Math.floorMod(h, dims)] += 1f;
            v[Math.floorMod(h * 31 + 7, dims)] += 0.5f;
        }
        double n = 0;
        for (float x : v) {
            n += x * x;
        }
        if (n == 0) {
            v[0] = 1;
            return v;
        }
        float inv = (float) (1 / Math.sqrt(n));
        for (int i = 0; i < dims; i++) {
            v[i] *= inv;
        }
        return v;
    }

    private void tags(HttpExchange ex) throws IOException {
        StringBuilder b = new StringBuilder("{\"models\": [");
        for (int i = 0; i < models.size(); i++) {
            b.append(i > 0 ? ", " : "").append("{\"name\": ").append(JsonText.write(models.get(i))).append("}");
        }
        send(ex, 200, "application/json", b.append("]}").toString());
    }

    /** What {@code model} can do, as Ollama lists it. */
    public List<String> capabilities(String model) {
        List<String> caps = new ArrayList<>(List.of("completion", "tools"));
        if (vision.contains(model)) {
            caps.add("vision");
        }
        return caps;
    }

    @SuppressWarnings("unchecked")
    private void show(HttpExchange ex) throws IOException {
        Map<String, Object> body = (Map<String, Object>) JsonText.parse(new String(ex.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        String model = String.valueOf(body.getOrDefault("model", body.get("name")));
        showRequests.add(model);
        if (!models.contains(model) && !models.contains(model + ":latest")) {
            send(ex, 404, "application/json", "{\"error\":\"model '" + model + "' not found\"}");
            return;
        }
        send(ex, 200, "application/json", JsonText.write(Map.of("capabilities", capabilities(model),
                "details", Map.of("family", "qwen3"), "model_info", Map.of("general.architecture", "qwen3"))));
    }

    /** Records the images of a chat request (never keeps them). */
    private void recordImages(Map<String, Object> body, int request) {
        List<?> messages = body.get("messages") instanceof List<?> l ? l : List.of();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof Map<?, ?> m && m.get("images") instanceof List<?> imgs) {
                for (Object o : imgs) {
                    byte[] raw = java.util.Base64.getDecoder().decode(String.valueOf(o));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("request", request);
                    r.put("message", i);
                    r.put("role", m.get("role"));
                    r.put("last", i == messages.size() - 1);
                    r.put("bytes", raw.length);
                    r.put("sha256", sha256(raw));
                    images.add(r);
                }
            }
        }
    }

    private static String sha256(byte[] raw) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void embed(HttpExchange ex) throws IOException {
        Map<String, Object> body = (Map<String, Object>) JsonText.parse(new String(ex.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        embedRequests.add(body);
        String model = String.valueOf(body.get("model"));
        if (!models.contains(model) && !models.contains(model + ":latest")) {
            send(ex, 404, "application/json", "{\"error\":\"model \\\"" + model + "\\\" not found, try pulling it first\"}");
            return;
        }
        List<String> inputs = body.get("input") instanceof List<?> l ? (List<String>) l : List.of(String.valueOf(body.get("input")));
        StringBuilder b = new StringBuilder("{\"model\":").append(JsonText.write(model)).append(",\"embeddings\":[");
        for (int i = 0; i < inputs.size(); i++) {
            float[] v = embedding(inputs.get(i), dimensions);
            b.append(i > 0 ? "," : "").append('[');
            for (int j = 0; j < v.length; j++) {
                b.append(j > 0 ? "," : "").append(v[j]);
            }
            b.append(']');
        }
        b.append("],\"total_duration\":1000000,\"load_duration\":0,\"prompt_eval_count\":").append(inputs.size() * 8).append('}');
        send(ex, 200, "application/json", b.toString());
    }

    @SuppressWarnings("unchecked")
    private void chat(HttpExchange ex) throws IOException {
        Map<String, Object> body = (Map<String, Object>) JsonText.parse(new String(ex.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        requests.add(body);
        recordImages(body, requests.size() - 1);
        int promptTokens = promptEvalCount(body);
        List<String> lines = chat.apply(body);
        if (!lines.isEmpty() && lines.get(0).startsWith("!")) {
            String[] parts = lines.get(0).substring(1).split(" ", 2);
            send(ex, Integer.parseInt(parts[0]), "application/json", parts[1]);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            if (firstChunkDelayMs > 0) {
                // silent, like Ollama while it loads the model and reads the prompt; then empty chunks (harmless to a
                // client still there) until a write fails if the client closed the connection meanwhile
                try {
                    Thread.sleep(firstChunkDelayMs);
                    for (int i = 0; i < 20; i++) {
                        out.write("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":false}\n"
                                .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            for (String line : lines) {
                if (line.contains("\"done\": true") && JsonText.parse(line) instanceof Map<?, ?> m) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    m.forEach((k, v) -> d.put(String.valueOf(k), v));
                    d.put("prompt_eval_count", promptTokens);
                    line = JsonText.write(d);
                }
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IOException e) {
            abandoned.incrementAndGet();     // the client stopped reading
        }
    }

    /** What Ollama would evaluate again: the messages after the prefix this request shares with the previous one. */
    private synchronized int promptEvalCount(Map<String, Object> body) {
        List<?> messages = body.get("messages") instanceof List<?> l ? l : List.of();
        int shared = 0;
        while (shared < cached.size() && shared < messages.size() - 1 && cached.get(shared).equals(messages.get(shared))) {
            shared++;
        }
        cached = new ArrayList<>(messages);
        int chars = 0;
        for (int i = shared; i < messages.size(); i++) {
            if (messages.get(i) instanceof Map<?, ?> m && m.get("content") instanceof String c) {
                chars += c.length();
            }
        }
        return (int) Math.ceil(chars / CHARS_PER_TOKEN) + 5 * (messages.size() - shared) + 3;
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
