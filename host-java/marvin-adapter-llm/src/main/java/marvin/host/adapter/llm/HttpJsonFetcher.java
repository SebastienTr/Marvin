// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

import marvin.host.application.conversation.port.out.JsonFetcher;
import marvin.host.domain.shared.JsonText;

/**
 * The tools' HTTPS requests (the Python host's {@code voice/net.py}): a GET with a query string, the answer
 * parsed as JSON. The JVM's trust store verifies TLS; a system proxy is used when one is configured.
 */
public final class HttpJsonFetcher implements JsonFetcher {
    private final HttpClient client = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Object get(String url, Map<String, Object> params, double timeoutS) {
        String full = url;
        if (params != null && !params.isEmpty()) {
            StringJoiner q = new StringJoiner("&");
            params.forEach((k, v) -> q.add(enc(k) + "=" + enc(value(v))));
            full += "?" + q;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(full))
                .timeout(Duration.ofMillis(Math.max(1, (long) (timeoutS * 1000))))
                .header("Accept", "application/json")
                .header("User-Agent", "marvin-host")
                .GET().build();
        try {
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() >= 400) {
                String body = r.body();
                try {
                    Object parsed = JsonText.parse(body);
                    if (parsed instanceof Map<?, ?> m && m.get("error") != null) {
                        return parsed;             // Open-Meteo explains a refused request in JSON
                    }
                } catch (IllegalArgumentException e) {
                    // not JSON: the status says it
                }
                throw new Failed("HTTP Error " + r.statusCode(), false);
            }
            return JsonText.parse(r.body());
        } catch (HttpTimeoutException e) {
            throw new Failed("timed out", true);
        } catch (IOException e) {
            throw new Failed(NetErrors.reason(e), false);
        } catch (IllegalArgumentException e) {
            throw new Failed("not JSON: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("interrupted", false);
        }
    }

    private static String value(Object v) {
        if (v instanceof Double d) {
            return JsonText.repr(d);
        }
        return String.valueOf(v);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
