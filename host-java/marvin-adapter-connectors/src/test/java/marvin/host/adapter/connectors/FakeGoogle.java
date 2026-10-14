// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import marvin.host.domain.shared.JsonText;

/**
 * A local stand-in for Google's OAuth and APIs, on 127.0.0.1: every request is recorded (method, path, query, the
 * Authorization header, the body), and answered by a route (status and JSON). No test ever calls Google.
 */
final class FakeGoogle implements AutoCloseable {

    record Request(String method, String path, List<Map.Entry<String, String>> query, String authorization, String body) {
        String param(String name) {
            return query.stream().filter(e -> e.getKey().equals(name)).map(Map.Entry::getValue).findFirst().orElse(null);
        }

        Map<String, String> form() {
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : parse(body)) {
                m.put(e.getKey(), e.getValue());
            }
            return m;
        }
    }

    record Answer(int status, Object json) {
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    final Map<String, Function<Request, Answer>> routes = new LinkedHashMap<>();
    private final HttpServer server;

    FakeGoogle() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Answers {@code method path} with this status and JSON. */
    FakeGoogle on(String method, String path, Function<Request, Answer> answer) {
        routes.put(method + " " + path, answer);
        return this;
    }

    FakeGoogle on(String method, String path, int status, Object json) {
        return on(method, path, r -> new Answer(status, json));
    }

    List<String> paths() {
        return requests.stream().map(r -> r.method() + " " + r.path()).toList();
    }

    private void handle(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request r = new Request(x.getRequestMethod(), x.getRequestURI().getRawPath(), parse(x.getRequestURI().getRawQuery()),
                x.getRequestHeaders().getFirst("Authorization"), body);
        requests.add(r);
        Function<Request, Answer> route = routes.get(r.method() + " " + r.path());
        Answer a = route == null ? new Answer(404, Map.of("error", Map.of("message", "not found"))) : route.apply(r);
        byte[] out = JsonText.write(a.json()).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(a.status(), out.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(out);
        }
    }

    static List<Map.Entry<String, String>> parse(String raw) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.add(Map.entry(k, v));
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
