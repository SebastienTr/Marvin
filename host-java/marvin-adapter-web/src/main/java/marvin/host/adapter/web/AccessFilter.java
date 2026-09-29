// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The app's access rules, as the Python host applies them (docs/ui.md):
 * <ul>
 * <li>requests from this computer (loopback) need nothing; other devices need the access key;</li>
 * <li>without a key check, the {@code Host} header must be an IP address, {@code localhost} or this
 * machine's name (a DNS-rebinding page cannot read the loopback API);</li>
 * <li>{@code /static/*}, the icon and the manifest are public;</li>
 * <li>a page opened with {@code ?token=} sets the {@code marvin_key} cookie and redirects to the same
 * address without it;</li>
 * <li>every POST must be JSON (at most 16 KiB) from the same origin.</li>
 * </ul>
 * Every response carries {@code X-Content-Type-Options}, {@code Referrer-Policy} and
 * {@code X-Frame-Options}. The checked POST body is handed on as the {@link #BODY} request attribute.
 */
public final class AccessFilter implements Filter {
    static final String COOKIE = "marvin_key";
    static final String BODY = "marvin.body";
    static final int MAX_BODY = 16384;
    static final Set<String> POST_PATHS = Set.of("/api/settings", "/api/voice/settings", "/api/voice/on",
            "/api/voice/off", "/api/voice/ask", "/api/voice/listen", "/api/voice/mute", "/api/voice/stop-speaking",
            "/api/memory/consolidate");
    static final Set<String> PUBLIC = Set.of("/icon.png", "/favicon.ico", "/manifest.webmanifest");

    static final byte[] LOCKED_PAGE = """
            <!doctype html>
            <!-- SPDX-License-Identifier: MIT -->
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Marvin</title><link rel="stylesheet" href="/static/style.css"></head>
            <body class="locked"><main class="locked-card">
            <h1>Marvin</h1>
            <p>This app needs its access key.</p>
            <p class="muted">Open the address that <code>marvin-host</code> printed when it started, the one that ends
            with <code>?token=</code>. Your browser will remember it.</p>
            </main></body></html>
            """.getBytes(StandardCharsets.UTF_8);

    private enum Access { OK, NO_KEY, BAD_HOST }

    private final AccessKey key;
    private final Set<String> ownNames;

    public AccessFilter(AccessKey key, String hostName) {
        this.key = key;
        this.ownNames = ownNames(hostName);
    }

    /**
     * The names a browser may use for this machine: the full hostname, the short one and
     * {@code <short>.local} (mDNS). Whole names only: comparing the first label would accept
     * {@code <short>.attacker.example}, which a rebinding DNS can point at 127.0.0.1.
     */
    static Set<String> ownNames(String hostName) {
        String full = stripDot(hostName.strip().toLowerCase(Locale.ROOT));
        String shortName = full.split("\\.", -1)[0];
        return java.util.stream.Stream.of(full, shortName, shortName + ".local")
                .filter(n -> !n.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String stripDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest rq = (HttpServletRequest) req;
        HttpServletResponse rs = (HttpServletResponse) res;
        rs.setHeader("X-Content-Type-Options", "nosniff");
        rs.setHeader("Referrer-Policy", "no-referrer");
        rs.setHeader("X-Frame-Options", "DENY");
        String path = rq.getRequestURI();
        Map<String, List<String>> query = QueryString.parse(rq.getQueryString());
        Access why = access(rq, query);
        String method = rq.getMethod();
        if ("POST".equals(method)) {
            if (why != Access.OK) {
                denied(rs, why, path);
                return;
            }
            if (!POST_PATHS.contains(path)) {
                write(rs, 404, Responses.JSON, error("not found"));
                return;
            }
            String origin = rq.getHeader("Origin");
            if (origin != null && !origin.isEmpty() && !netloc(origin).equals(header(rq, "Host").toLowerCase(Locale.ROOT))) {
                write(rs, 403, Responses.JSON, error("cross-origin request"));
                return;
            }
            String type = header(rq, "Content-Type").split(";")[0].strip();
            if (!type.equals("application/json")) {
                write(rs, 415, Responses.JSON, error("send JSON"));
                return;
            }
            long length = rq.getContentLengthLong();
            if (length > MAX_BODY) {
                write(rs, 413, Responses.JSON, error("too large"));
                return;
            }
            byte[] body;
            try (InputStream in = rq.getInputStream()) {
                body = in.readNBytes(MAX_BODY + 1);
            }
            if (body.length > MAX_BODY) {
                write(rs, 413, Responses.JSON, error("too large"));
                return;
            }
            rq.setAttribute(BODY, body);
            chain.doFilter(rq, rs);
            return;
        }
        boolean isPublic = path.startsWith("/static/") || PUBLIC.contains(path);
        if (why != Access.OK && !(isPublic && why == Access.NO_KEY)) {
            denied(rs, why, path);
            return;
        }
        String given = QueryString.first(query, "token");
        if (why == Access.OK && key.enabled() && given != null && !path.startsWith("/api/") && key.matches(given)) {
            // remember the key in a cookie and drop it from the address bar
            query.remove("token");
            String loc = path + (query.isEmpty() ? "" : "?" + QueryString.encode(query));
            rs.setHeader("Location", loc);
            rs.setHeader("Set-Cookie", COOKIE + "=" + key.value() + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict");
            write(rs, 303, "text/plain", new byte[0]);
            return;
        }
        chain.doFilter(rq, rs);
    }

    // ------------------------------------------------------------------ access

    private Access access(HttpServletRequest rq, Map<String, List<String>> query) {
        String given = presentedToken(rq, query);
        if (key.enabled() && given != null && key.matches(given)) {
            return Access.OK;
        }
        if (!key.enabled() || clientIsLocal(rq)) {
            return hostIsKnown(rq) ? Access.OK : Access.BAD_HOST;
        }
        return Access.NO_KEY;
    }

    private static String presentedToken(HttpServletRequest rq, Map<String, List<String>> query) {
        if (query.containsKey("token")) {
            return QueryString.first(query, "token");
        }
        String auth = header(rq, "Authorization");
        if (auth.startsWith("Bearer ")) {
            return auth.substring(7).strip();
        }
        Cookie[] cookies = rq.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private static boolean clientIsLocal(HttpServletRequest rq) {
        InetAddress a = literal(rq.getRemoteAddr());
        return a != null && a.isLoopbackAddress();
    }

    /** The Host header is an IP address, localhost, or this machine's name (anti DNS rebinding). */
    private boolean hostIsKnown(HttpServletRequest rq) {
        String host = header(rq, "Host").strip().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return false;
        }
        String name;
        if (host.startsWith("[")) {
            name = host.split("]")[0] + "]";
        } else {
            int colon = host.lastIndexOf(':');
            name = colon < 0 ? host : host.substring(0, colon);
        }
        if (literal(name) != null || name.equals("localhost") || name.endsWith(".localhost")) {
            return true;
        }
        return ownNames.contains(stripDot(name));
    }

    /** An IP literal (brackets allowed), never a DNS lookup; {@code null} if it is not one. */
    static InetAddress literal(String s) {
        if (s == null) {
            return null;
        }
        String t = s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
        if (t.isEmpty() || !t.matches("[0-9a-fA-F:.%a-zA-Z]+") || (!t.contains(":") && !t.matches("[0-9.]+"))) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String netloc(String origin) {
        try {
            String a = URI.create(origin).getRawAuthority();
            return a == null ? "" : a.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ responses

    private static void denied(HttpServletResponse rs, Access why, String path) throws IOException {
        if (why == Access.BAD_HOST) {
            write(rs, 403, Responses.JSON, error("unknown host name: open the app by IP address or localhost"));
        } else if (path.startsWith("/api/") || !(path.endsWith("/") || path.endsWith(".html"))) {
            write(rs, 401, Responses.JSON, error("access key required"));
        } else {
            write(rs, 401, Responses.HTML, LOCKED_PAGE);
        }
    }

    private static byte[] error(String message) {
        return PyJson.bytes(Map.of("error", message));
    }

    private static void write(HttpServletResponse rs, int status, String type, byte[] body) throws IOException {
        rs.setStatus(status);
        rs.setHeader("Content-Type", type);
        rs.setHeader("Cache-Control", "no-store");
        if (type.startsWith("text/html")) {
            rs.setHeader("Content-Security-Policy", Responses.CSP);
        }
        rs.setContentLength(body.length);
        rs.getOutputStream().write(body);
        rs.flushBuffer();
    }

    private static String header(HttpServletRequest rq, String name) {
        String v = rq.getHeader(name);
        return v == null ? "" : v;
    }

    /** This machine's name, without the domain, for the Host check. */
    public static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
