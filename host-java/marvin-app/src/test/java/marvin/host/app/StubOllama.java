// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import marvin.host.domain.shared.JsonText;

/**
 * A stand-in for Ollama on a free local port: {@code /api/tags} lists models, {@code /api/chat} answers each
 * request with the lines {@code chat} gives (newline-delimited JSON, flushed one by one), or with an HTTP
 * error when the first line is {@code "!<status> <body>"}. The request bodies are kept.
 */
public final class StubOllama implements AutoCloseable {
    public final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    public volatile List<String> models = List.of("qwen3:4b-instruct");
    public volatile Function<Map<String, Object>, List<String>> chat = r -> List.of();
    public volatile long delayMs = 5;

    public StubOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", this::tags);
        server.createContext("/api/chat", this::chat);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A streamed text answer, in pieces, then {@code done}. */
    public static List<String> text(String... pieces) {
        List<String> out = new java.util.ArrayList<>();
        for (String p : pieces) {
            out.add(JsonText.write(Map.of("message", Map.of("role", "assistant", "content", p), "done", false)));
        }
        out.add(JsonText.write(Map.of("message", Map.of("role", "assistant", "content", ""), "done", true,
                "done_reason", "stop")));
        return out;
    }

    /** A tool call chunk (Ollama sends it whole, with empty content), then {@code done}. */
    public static List<String> toolCall(String name, Map<String, Object> arguments) {
        return List.of(JsonText.write(Map.of("message", Map.of("role", "assistant", "content", "",
                        "tool_calls", List.of(Map.of("function", Map.of("name", name, "arguments", arguments)))), "done", false)),
                JsonText.write(Map.of("message", Map.of("role", "assistant", "content", ""), "done", true,
                        "done_reason", "stop")));
    }

    private void tags(HttpExchange ex) throws IOException {
        StringBuilder b = new StringBuilder("{\"models\": [");
        for (int i = 0; i < models.size(); i++) {
            b.append(i > 0 ? ", " : "").append("{\"name\": ").append(JsonText.write(models.get(i))).append("}");
        }
        send(ex, 200, "application/json", b.append("]}").toString());
    }

    @SuppressWarnings("unchecked")
    private void chat(HttpExchange ex) throws IOException {
        Map<String, Object> body = (Map<String, Object>) JsonText.parse(new String(ex.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        requests.add(body);
        List<String> lines = chat.apply(body);
        if (!lines.isEmpty() && lines.get(0).startsWith("!")) {
            String[] parts = lines.get(0).substring(1).split(" ", 2);
            send(ex, Integer.parseInt(parts[0]), "application/json", parts[1]);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            for (String line : lines) {
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
            // the client stopped reading
        }
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
