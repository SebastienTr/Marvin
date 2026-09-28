// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** Responses with the Python host's headers: content type, cache policy, and a strict CSP for pages. */
final class Responses {
    static final String JSON = "application/json";
    static final String HTML = "text/html; charset=utf-8";
    static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
            + "connect-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private Responses() {
    }

    static ResponseEntity<byte[]> send(int status, byte[] body, String type) {
        return send(status, body, type, null);
    }

    static ResponseEntity<byte[]> send(int status, byte[] body, String type, String cacheControl) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.CONTENT_TYPE, type);
        h.set(HttpHeaders.CACHE_CONTROL, cacheControl != null ? cacheControl
                : type.startsWith("text/css") || type.startsWith("text/javascript") ? "no-cache" : "no-store");
        if (type.startsWith("text/html")) {
            h.set("Content-Security-Policy", CSP);
        }
        return ResponseEntity.status(status).headers(h).body(body);
    }

    static ResponseEntity<byte[]> json(Object body) {
        return json(200, body);
    }

    static ResponseEntity<byte[]> json(int status, Object body) {
        return send(status, PyJson.bytes(body), JSON);
    }

    static ResponseEntity<byte[]> error(int status, String message) {
        return json(status, Map.of("error", message));
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
