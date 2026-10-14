// SPDX-License-Identifier: MIT
package marvin.host.domain.connector;

import java.util.List;
import java.util.Objects;

/**
 * A connector Marvin knows (docs/design.md 7): its id, its name, the OAuth scopes it asks for (the narrowest that serve
 * its tools), and its tools with what each does to the world ({@code READ}, {@code LOCAL_WRITE}, {@code EXTERNAL_WRITE},
 * the conversation's tool classes by name).
 */
public record Connector(String id, String name, List<String> scopes, List<Tool> tools) {

    /** A tool of a connector, and its class. */
    public record Tool(String name, String access) {
    }

    public Connector {
        Objects.requireNonNull(id, "id");
        scopes = List.copyOf(scopes);
        tools = List.copyOf(tools);
        ScopePolicy.check(scopes);
    }

    public static final String CALENDAR_READONLY = "https://www.googleapis.com/auth/calendar.readonly";
    public static final String GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    public static final String GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose";

    /** Google Calendar, read only: the events of the owner's calendars. */
    public static final Connector GOOGLE_CALENDAR = new Connector("google_calendar", "Google Calendar",
            List.of(CALENDAR_READONLY), List.of(new Tool("calendar_list_events", "READ")));

    /**
     * Gmail: search (read only), and drafts after the owner's approval. Never send: {@code gmail.compose} is the
     * narrowest scope that can create a draft, and the connector's HTTP client allows only the draft-creating call.
     */
    public static final Connector GMAIL = new Connector("gmail", "Gmail", List.of(GMAIL_READONLY, GMAIL_COMPOSE),
            List.of(new Tool("gmail_search", "READ"), new Tool("gmail_create_draft", "EXTERNAL_WRITE")));

    /** Every connector, in the order the app lists them. */
    public static final List<Connector> ALL = List.of(GOOGLE_CALENDAR, GMAIL);

    /** @throws IllegalArgumentException no such connector */
    public static Connector of(String id) {
        return ALL.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such connector: " + id));
    }
}
