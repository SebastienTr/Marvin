// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import marvin.host.domain.shared.JsonText;

/**
 * Google's HTTP: the APIs' calls, each checked by {@link GoogleGuard} before it is sent, and the token endpoints' forms.
 * Answers are JSON; an error status becomes {@link Refused} (401, 403: the token) or {@link Failed}, with Google's short
 * message, never the token.
 */
public final class GoogleHttp {
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final GoogleEndpoints endpoints;

    public GoogleHttp(GoogleEndpoints endpoints) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(), endpoints);
    }

    GoogleHttp(HttpClient http, GoogleEndpoints endpoints) {
        this.http = http;
        this.endpoints = endpoints;
    }

    public GoogleEndpoints endpoints() {
        return endpoints;
    }

    /** {@code GET} an API path with query parameters (a name may repeat). */
    public Object get(String path, List<Map.Entry<String, String>> query, String accessToken) {
        GoogleGuard.check("GET", path);
        URI uri = URI.create(endpoints.api().toString().replaceAll("/+$", "") + path + (query.isEmpty() ? "" : "?" + form(query)));
        return send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json").GET().build());
    }

    /** {@code POST} a JSON body to an API path. */
    public Object post(String path, Object body, String accessToken) {
        GoogleGuard.check("POST", path);
        URI uri = URI.create(endpoints.api().toString().replaceAll("/+$", "") + path);
        return send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonText.write(body), StandardCharsets.UTF_8)).build());
    }

    /** A form to the token or the revocation endpoint (the only other places Marvin posts to). */
    public Object tokenForm(URI endpoint, List<Map.Entry<String, String>> fields) {
        if (!endpoint.equals(endpoints.token()) && !endpoint.equals(endpoints.revoke())) {
            throw new GoogleGuard.Blocked("POST " + endpoint);
        }
        return send(HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields), StandardCharsets.UTF_8)).build());
    }

    private Object send(HttpRequest rq) {
        HttpResponse<String> r;
        try {
            r = http.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new Failed(0, "cannot reach Google (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed(0, "interrupted");
        }
        Object json;
        try {
            json = r.body() == null || r.body().isBlank() ? Map.of() : JsonText.parse(r.body());
        } catch (IllegalArgumentException e) {
            json = Map.of();
        }
        int status = r.statusCode();
        if (status >= 200 && status < 300) {
            return json;
        }
        String message = message(json);
        if (status == 401 || status == 403 || status == 400 && message.contains("invalid_grant")) {
            throw new Refused(status, message);
        }
        throw new Failed(status, message);
    }

    /** Google's short error: {@code error.message}, or {@code error} and {@code error_description} (OAuth). */
    static String message(Object json) {
        if (json instanceof Map<?, ?> m) {
            if (m.get("error") instanceof Map<?, ?> e && e.get("message") instanceof String s) {
                return s;
            }
            if (m.get("error") instanceof String e) {
                return e + (m.get("error_description") instanceof String d ? ": " + d : "");
            }
        }
        return "no details";
    }

    static String form(List<Map.Entry<String, String>> fields) {
        StringJoiner j = new StringJoiner("&");
        for (Map.Entry<String, String> f : fields) {
            j.add(URLEncoder.encode(f.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(f.getValue(), StandardCharsets.UTF_8));
        }
        return j.toString();
    }

    /** Google answered with an error. */
    public static class Failed extends RuntimeException {
        private final int status;

        public Failed(int status, String message) {
            super("Google answered " + (status == 0 ? "nothing" : status) + ": " + message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** Google refused the token (revoked, expired, not allowed). */
    public static final class Refused extends Failed {
        public Refused(int status, String message) {
            super(status, message);
        }
    }
}
