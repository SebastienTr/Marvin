// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The only calls Marvin makes to Google's APIs (docs/design.md 6.3: Marvin never sends an email): read a calendar's
 * events, search and read Gmail's messages' metadata, read the Gmail profile (the account's address), and create a
 * draft. Anything else is refused before it is sent, first of all Gmail's {@code drafts.send} and {@code messages.send},
 * every {@code DELETE}, trash and modify call, whatever the token's scopes would allow.
 */
public final class GoogleGuard {

    /** A call that may be made: a method and a path pattern under the API's base. */
    record Allowed(String method, Pattern path) {
    }

    static final List<Allowed> ALLOWED = List.of(
            new Allowed("GET", Pattern.compile("/calendar/v3/calendars/[A-Za-z0-9._%@+-]{1,200}/events")),
            new Allowed("GET", Pattern.compile("/calendar/v3/calendars/primary")),
            new Allowed("GET", Pattern.compile("/gmail/v1/users/me/profile")),
            new Allowed("GET", Pattern.compile("/gmail/v1/users/me/messages")),
            new Allowed("GET", Pattern.compile("/gmail/v1/users/me/messages/[A-Za-z0-9]{1,64}")),
            new Allowed("POST", Pattern.compile("/gmail/v1/users/me/drafts")));

    /** Words that are never in a path Marvin calls, whatever the list above says (a second lock). */
    static final List<String> NEVER = List.of("send", "delete", "trash", "modify", "import", "insert", "batch", "forward",
            "settings", "filters", "watch", "stop");

    private GoogleGuard() {
    }

    /**
     * Checks a call before it is made.
     *
     * @param path the path under the API's base, without its query
     * @throws Blocked a call that is not on the list
     */
    public static void check(String method, String path) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String p = path == null ? "" : path;
        String lower = p.toLowerCase(Locale.ROOT);
        for (String word : NEVER) {
            if (lower.contains(word)) {
                throw new Blocked(m + " " + p);
            }
        }
        if (p.contains("..") || p.contains("//")) {
            throw new Blocked(m + " " + p);
        }
        for (Allowed a : ALLOWED) {
            if (a.method().equals(m) && a.path().matcher(p).matches()) {
                return;
            }
        }
        throw new Blocked(m + " " + p);
    }

    /** A call Marvin must never make: it was not sent. */
    public static final class Blocked extends RuntimeException {
        public Blocked(String call) {
            super("blocked: Marvin never makes this call (" + call + ")");
        }
    }
}
