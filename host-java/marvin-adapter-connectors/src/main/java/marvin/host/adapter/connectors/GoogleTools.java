// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

import marvin.host.application.connector.port.in.ConnectorAccess;
import marvin.host.application.conversation.port.out.ToolSource;
import marvin.host.domain.connector.Connector;
import marvin.host.domain.conversation.tool.ToolAccess;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.ToolPreview;
import marvin.host.domain.conversation.tool.ToolSpec;
import marvin.host.domain.conversation.tool.ToolSurface;

/**
 * The connectors' tools (docs/design.md 7.1, 6.3), offered while their connector is connected:
 *
 * <ul>
 * <li>{@code calendar_list_events} (read): the owner's primary calendar's events in a period, with their title, times,
 * place and the attendees' names only;</li>
 * <li>{@code gmail_search} (read): messages matching a Gmail search, as their sender, recipients, subject, date and
 * snippet (bodies are not read);</li>
 * <li>{@code gmail_create_draft} (writes outside this computer, in background tasks only): a draft in Gmail, once the
 * owner approved its preview; the owner sends it from Gmail. There is no tool that sends, forwards, replies or deletes,
 * and {@link GoogleGuard} would refuse the call if there were.</li>
 * </ul>
 */
public final class GoogleTools implements ToolSource {
    static final int MAX_EVENTS = 50;
    static final int MAX_MESSAGES = 10;
    static final int MAX_BODY = 10_000;
    static final Pattern EMAIL = Pattern.compile("[^\\s@<>,;\"]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,63}");

    private final ConnectorAccess access;
    private final GoogleHttp http;
    private final ZoneId zone;
    private final DoubleSupplier wallSeconds;

    public GoogleTools(ConnectorAccess access, GoogleHttp http, ZoneId zone, DoubleSupplier wallSeconds) {
        this.access = access;
        this.http = http;
        this.zone = zone;
        this.wallSeconds = wallSeconds;
    }

    @Override
    public List<SourcedTool> tools() {
        List<SourcedTool> out = new ArrayList<>();
        if (access.connected(Connector.GOOGLE_CALENDAR.id())) {
            out.add(new SourcedTool(calendarSpec(), ToolAccess.READ, ToolSurface.ALL, this::listEvents));
        }
        if (access.connected(Connector.GMAIL.id())) {
            out.add(new SourcedTool(searchSpec(), ToolAccess.READ, ToolSurface.ALL, this::search));
            out.add(new SourcedTool(draftSpec(), ToolAccess.EXTERNAL_WRITE, Set.of(ToolSurface.TASK), new Draft()));
        }
        return out;
    }

    // ------------------------------------------------------------------ specs

    static ToolSpec calendarSpec() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", string("start of the period: a date (2026-10-15) or a date and time, in the owner's time zone; "
                + "leave it out for now", 40));
        p.put("to", string("end of the period, same forms; leave it out for the end of the day after 'from'", 40));
        p.put("query", string("words to look for in the events, if any", 100));
        p.put("max", integer("how many events at most (1 to " + MAX_EVENTS + ")"));
        return new ToolSpec("calendar_list_events", "The owner's Google Calendar events in a period (read only): title, "
                + "start and end, place, the attendees' names. Use it for their schedule, a day, a week, a meeting.",
                object(p, List.of()), 20, true, false, null);
    }

    static ToolSpec searchSpec() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("query", string("a Gmail search, e.g. 'from:garage insurance newer_than:30d'", 200));
        p.put("max", integer("how many messages at most (1 to " + MAX_MESSAGES + ")"));
        return new ToolSpec("gmail_search", "Search the owner's Gmail (read only): each message's sender, recipients, "
                + "subject, date and a short snippet. It cannot send, reply, forward or delete anything.",
                object(p, List.of("query")), 25, true, false, null);
    }

    static ToolSpec draftSpec() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("to", string("the recipients' email addresses, separated by commas", 1000));
        p.put("cc", string("copy recipients' addresses, separated by commas, if any", 1000));
        p.put("subject", string("the subject", 200));
        p.put("body", string("the message, plain text, in the language the owner wants it in", MAX_BODY));
        return new ToolSpec("gmail_create_draft", "Create an email draft in the owner's Gmail. The owner approves the "
                + "draft in Marvin's app first, then sends it from Gmail themselves: Marvin never sends email.",
                object(p, List.of("to", "subject", "body")), 25, true, false, false);
    }

    // ------------------------------------------------------------------ calendar

    Object listEvents(Map<String, Object> args, Map<String, Object> context) {
        String token = token(Connector.GOOGLE_CALENDAR);
        ZonedDateTime now = Instant.ofEpochMilli((long) (wallSeconds.getAsDouble() * 1000)).atZone(zone);
        ZonedDateTime from = args.get("from") instanceof String f && !f.isBlank() ? time(f, true) : now;
        ZonedDateTime to = args.get("to") instanceof String t && !t.isBlank() ? time(t, false)
                : from.toLocalDate().plusDays(2).atStartOfDay(zone);
        if (!to.isAfter(from)) {
            throw new ToolError("'to' must be after 'from'");
        }
        if (to.isAfter(from.plusDays(62))) {
            throw new ToolError("a period of at most two months");
        }
        long max = args.get("max") instanceof Number n ? Math.max(1, Math.min(MAX_EVENTS, n.longValue())) : 20;
        List<Map.Entry<String, String>> q = new ArrayList<>(List.of(
                Map.entry("timeMin", from.toOffsetDateTime().toString()), Map.entry("timeMax", to.toOffsetDateTime().toString()),
                Map.entry("singleEvents", "true"), Map.entry("orderBy", "startTime"), Map.entry("maxResults", Long.toString(max))));
        if (args.get("query") instanceof String s && !s.isBlank()) {
            q.add(Map.entry("q", s.strip()));
        }
        Object json = request(Connector.GOOGLE_CALENDAR, () -> http.get("/calendar/v3/calendars/primary/events", q, token));
        List<Map<String, Object>> events = new ArrayList<>();
        if (json instanceof Map<?, ?> m && m.get("items") instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> e && !"cancelled".equals(e.get("status"))) {
                    events.add(event(e));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toOffsetDateTime().toString());
        out.put("to", to.toOffsetDateTime().toString());
        out.put("events", events);
        if (events.isEmpty()) {
            out.put("note", "no event in this period");
        }
        return out;
    }

    private Map<String, Object> event(Map<?, ?> e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", e.get("summary") instanceof String s ? s : "(no title)");
        boolean allDay = e.get("start") instanceof Map<?, ?> st && st.get("date") != null;
        m.put("start", when(e.get("start")));
        m.put("end", when(e.get("end")));
        m.put("all_day", allDay);
        if (e.get("location") instanceof String l && !l.isBlank()) {
            m.put("place", l);
        }
        List<String> names = new ArrayList<>();
        if (e.get("attendees") instanceof List<?> as) {
            for (Object a : as) {
                if (a instanceof Map<?, ?> am && am.get("displayName") instanceof String n && !n.isBlank()
                        && !Boolean.TRUE.equals(am.get("self"))) {
                    names.add(n);                                   // names only: never their addresses
                }
            }
        }
        if (!names.isEmpty()) {
            m.put("with", names);
        }
        return m;
    }

    private String when(Object o) {
        if (o instanceof Map<?, ?> t) {
            if (t.get("dateTime") instanceof String dt) {
                try {
                    return OffsetDateTime.parse(dt).atZoneSameInstant(zone).toLocalDateTime().toString();
                } catch (DateTimeParseException e) {
                    return dt;
                }
            }
            if (t.get("date") instanceof String d) {
                return d;
            }
        }
        return "";
    }

    /** A date or a date and time the model gave, in the owner's zone. */
    ZonedDateTime time(String s, boolean start) {
        String v = s.strip();
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(zone);
        } catch (DateTimeParseException e) {
            // not with an offset
        }
        try {
            return LocalDateTime.parse(v).atZone(zone);
        } catch (DateTimeParseException e) {
            // not a date and time
        }
        try {
            LocalDate d = LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
            return (start ? d : d.plusDays(1)).atStartOfDay(zone);
        } catch (DateTimeParseException e) {
            throw new ToolError("'" + v + "' is not a date (2026-10-15) or a date and time (2026-10-15T14:00)");
        }
    }

    // ------------------------------------------------------------------ gmail

    Object search(Map<String, Object> args, Map<String, Object> context) {
        String token = token(Connector.GMAIL);
        String query = String.valueOf(args.get("query")).strip();
        long max = args.get("max") instanceof Number n ? Math.max(1, Math.min(MAX_MESSAGES, n.longValue())) : 5;
        Object list = request(Connector.GMAIL, () -> http.get("/gmail/v1/users/me/messages",
                List.of(Map.entry("q", query), Map.entry("maxResults", Long.toString(max))), token));
        List<Map<String, Object>> messages = new ArrayList<>();
        if (list instanceof Map<?, ?> m && m.get("messages") instanceof List<?> ids) {
            for (Object o : ids) {
                if (messages.size() >= max) {
                    break;
                }
                if (o instanceof Map<?, ?> idm && idm.get("id") instanceof String id && id.matches("[A-Za-z0-9]{1,64}")) {
                    Object msg = request(Connector.GMAIL, () -> http.get("/gmail/v1/users/me/messages/" + id, List.of(
                            Map.entry("format", "metadata"), Map.entry("metadataHeaders", "From"),
                            Map.entry("metadataHeaders", "To"), Map.entry("metadataHeaders", "Subject"),
                            Map.entry("metadataHeaders", "Date")), token));
                    messages.add(message(id, msg));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("messages", messages);
        if (messages.isEmpty()) {
            out.put("note", "no message matches");
        }
        return out;
    }

    private static Map<String, Object> message(String id, Object json) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        Map<String, String> headers = new LinkedHashMap<>();
        if (json instanceof Map<?, ?> j && j.get("payload") instanceof Map<?, ?> p && p.get("headers") instanceof List<?> hs) {
            for (Object h : hs) {
                if (h instanceof Map<?, ?> hm && hm.get("name") instanceof String n && hm.get("value") instanceof String v) {
                    headers.putIfAbsent(n.toLowerCase(java.util.Locale.ROOT), v);
                }
            }
        }
        m.put("from", headers.getOrDefault("from", ""));
        m.put("to", headers.getOrDefault("to", ""));
        m.put("subject", headers.getOrDefault("subject", ""));
        m.put("date", headers.getOrDefault("date", ""));
        m.put("snippet", json instanceof Map<?, ?> j && j.get("snippet") instanceof String s ? s : "");
        return m;
    }

    /** The draft tool: its preview for the approval, then the draft. */
    final class Draft implements ToolSource.Handler, ToolPreview {
        @Override
        public Map<String, Object> preview(Map<String, Object> args) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("kind", "email_draft");
            p.put("to", addresses(args.get("to"), "to", true));
            p.put("subject", subject(args));
            p.put("body", String.valueOf(args.getOrDefault("body", "")));
            List<String> cc = addresses(args.get("cc"), "cc", false);
            if (!cc.isEmpty()) {
                p.put("body", "Cc: " + String.join(", ", cc) + "\n\n" + p.get("body"));
            }
            return p;
        }

        @Override
        public Object call(Map<String, Object> args, Map<String, Object> context) {
            List<String> to = addresses(args.get("to"), "to", true);
            List<String> cc = addresses(args.get("cc"), "cc", false);
            String subject = subject(args);
            String body = String.valueOf(args.getOrDefault("body", ""));
            String token = token(Connector.GMAIL);
            String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime(to, cc, subject, body)
                    .getBytes(StandardCharsets.UTF_8));
            Object json = request(Connector.GMAIL, () -> http.post("/gmail/v1/users/me/drafts", Map.of("message",
                    Map.of("raw", raw)), token));
            String draftId = json instanceof Map<?, ?> m && m.get("id") instanceof String id ? id : "";
            String messageId = json instanceof Map<?, ?> m && m.get("message") instanceof Map<?, ?> msg
                    && msg.get("id") instanceof String mid ? mid : "";
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", true);
            out.put("draft_id", draftId);
            out.put("open_url", "https://mail.google.com/mail/u/0/#drafts" + (messageId.matches("[A-Za-z0-9]{1,64}")
                    ? "?compose=" + messageId : ""));
            out.put("note", "The draft is in the owner's Gmail; they send it themselves.");
            return out;
        }
    }

    static String subject(Map<String, Object> args) {
        String s = String.valueOf(args.getOrDefault("subject", "")).strip();
        if (s.contains("\r") || s.contains("\n")) {
            throw new ToolError("a subject is one line");
        }
        return s;
    }

    static List<String> addresses(Object value, String field, boolean required) {
        List<String> out = new ArrayList<>();
        if (value instanceof String s) {
            if (s.contains("\r") || s.contains("\n")) {
                throw new ToolError("'" + field + "' must be addresses separated by commas");
            }
            for (String part : s.split("[,;]")) {
                String a = part.strip();
                int lt = a.indexOf('<');
                int gt = a.indexOf('>');
                if (lt >= 0 && gt > lt) {
                    a = a.substring(lt + 1, gt).strip();
                }
                if (a.isEmpty()) {
                    continue;
                }
                if (!EMAIL.matcher(a).matches()) {
                    throw new ToolError("'" + a + "' is not an email address");
                }
                out.add(a);
            }
        }
        if (required && out.isEmpty()) {
            throw new ToolError("give at least one address in '" + field + "'");
        }
        if (out.size() > 20) {
            throw new ToolError("at most 20 addresses in '" + field + "'");
        }
        return out;
    }

    /** The message as RFC 5322 text: UTF-8, the subject encoded, the body in base64. */
    static String mime(List<String> to, List<String> cc, String subject, String body) {
        StringBuilder b = new StringBuilder();
        b.append("To: ").append(String.join(", ", to)).append("\r\n");
        if (!cc.isEmpty()) {
            b.append("Cc: ").append(String.join(", ", cc)).append("\r\n");
        }
        b.append("Subject: =?UTF-8?B?").append(Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)))
                .append("?=\r\n");
        b.append("MIME-Version: 1.0\r\n");
        b.append("Content-Type: text/plain; charset=UTF-8\r\n");
        b.append("Content-Transfer-Encoding: base64\r\n\r\n");
        b.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        return b.toString();
    }

    // ------------------------------------------------------------------ calls

    private String token(Connector c) {
        return access.accessToken(c.id()).orElseThrow(() -> new ToolError(c.name() + " is not connected: the owner can "
                + "connect it in Marvin's app"));
    }

    private Object request(Connector c, java.util.function.Supplier<Object> request) {
        try {
            return request.get();
        } catch (GoogleHttp.Refused e) {
            access.refused(c.id(), c.name() + " refused Marvin's access (" + e.status() + "): connect it again");
            throw new ToolError(c.name() + " refused the access: the owner must connect it again in Marvin's app");
        } catch (GoogleHttp.Failed e) {
            throw new ToolError(e.getMessage());
        } catch (GoogleGuard.Blocked e) {
            throw new ToolError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ schema helpers

    private static Map<String, Object> string(String description, int maxLength) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        m.put("maxLength", maxLength);
        return m;
    }

    private static Map<String, Object> integer(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "integer");
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        return m;
    }
}
