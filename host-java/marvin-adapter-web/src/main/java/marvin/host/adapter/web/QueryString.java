// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Python's {@code parse_qs} (blank values dropped) and {@code urlencode(..., doseq=True)}. */
final class QueryString {
    private QueryString() {
    }

    static Map<String, List<String>> parse(String query) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = decode(pair.substring(0, eq));
            String v = decode(pair.substring(eq + 1));
            if (v.isEmpty()) {
                continue;
            }
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }

    static String first(Map<String, List<String>> q, String key) {
        List<String> v = q.get(key);
        return v == null || v.isEmpty() ? null : v.getFirst();
    }

    static String encode(Map<String, List<String>> q) {
        StringJoiner j = new StringJoiner("&");
        q.forEach((k, vs) -> vs.forEach(v -> j.add(quotePlus(k) + "=" + quotePlus(v))));
        return j.toString();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String quotePlus(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("*", "%2A").replace("%7E", "~");
    }
}
