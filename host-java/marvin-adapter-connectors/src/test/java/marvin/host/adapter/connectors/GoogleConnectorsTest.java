// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import marvin.host.application.connector.ConnectorService;
import marvin.host.application.conversation.port.out.ToolSource;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.connector.Connector;
import marvin.host.domain.conversation.tool.ToolAccess;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.ToolPreview;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.conversation.tool.ToolSurface;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.JsonText;

/**
 * Google Calendar and Gmail end to end against a local fake Google (no real call ever): connecting with the owner's own
 * OAuth client (PKCE, the loopback redirect), the tokens kept in the secret store, the tools' requests and answers, the
 * draft created only through the drafts call, a refused token, and no call to anything that sends or deletes.
 */
class GoogleConnectorsTest {
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final String REDIRECT = "http://127.0.0.1:8765/api/connectors/callback";

    @TempDir
    Path dir;
    FakeGoogle google;
    volatile double wall = 1_792_044_000;          // Thursday 15 October 2026, 08:00 in Paris
    MemorySecretStore secrets;
    ConnectorService connectors;
    GoogleTools tools;
    GoogleHttp http;
    final Clocks clocks = new Clocks() {
        @Override
        public double wallSeconds() {
            return wall;
        }

        @Override
        public double monotonicSeconds() {
            return wall;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        google = new FakeGoogle();
        Path client = dir.resolve("google-oauth-client.json");
        Files.writeString(client, "{\"installed\": {\"client_id\": \"owner-client.apps.googleusercontent.com\", "
                + "\"client_secret\": \"owner-secret\", \"redirect_uris\": [\"http://localhost\"]}}");
        http = new GoogleHttp(GoogleEndpoints.at(google.base()));
        secrets = new MemorySecretStore();
        connectors = new ConnectorService(new GoogleOAuth(new GoogleClientFile(client), http, clocks::wallSeconds), secrets,
                clocks);
        tools = new GoogleTools(connectors, http, PARIS, clocks::wallSeconds);
        google.on("POST", "/token", r -> {
            Map<String, String> f = r.form();
            if ("authorization_code".equals(f.get("grant_type"))) {
                return new FakeGoogle.Answer(200, Map.of("access_token", "at-" + f.get("code"), "refresh_token", "rt-1",
                        "expires_in", 3599, "token_type", "Bearer", "scope", scopesFor(f.get("code"))));
            }
            return new FakeGoogle.Answer(200, Map.of("access_token", "at-refreshed", "expires_in", 3599, "scope",
                    scopesFor("gmail")));
        });
        google.on("POST", "/revoke", 200, Map.of());
        google.on("GET", "/gmail/v1/users/me/profile", 200, Map.of("emailAddress", "owner@gmail.com"));
        google.on("GET", "/calendar/v3/calendars/primary", 200, Map.of("id", "owner@gmail.com"));
    }

    static String scopesFor(String code) {
        return code.startsWith("cal") ? Connector.CALENDAR_READONLY : Connector.GMAIL_READONLY + " " + Connector.GMAIL_COMPOSE;
    }

    @AfterEach
    void tearDown() {
        google.close();
    }

    /** Connects a connector the way the browser does: the consent page's state comes back with a code. */
    void connect(String id, String code) {
        String url = connectors.connect(id, REDIRECT);
        String state = FakeGoogle.parse(url.substring(url.indexOf('?') + 1)).stream()
                .filter(e -> e.getKey().equals("state")).findFirst().orElseThrow().getValue();
        connectors.callback(state, code, null);
    }

    ToolRegistry taskTools() {
        return new ToolRegistry(ToolRegistry.fromSources(List.of(tools), ToolSurface.TASK), true, true, () -> wall,
                ToolSurface.TASK, true);
    }

    @Test
    void theConsentPageAndTheCodeExchangeUseTheOwnersClientWithPkce() {
        String url = connectors.connect("gmail", REDIRECT);
        Map<String, String> q = new java.util.LinkedHashMap<>();
        FakeGoogle.parse(url.substring(url.indexOf('?') + 1)).forEach(e -> q.put(e.getKey(), e.getValue()));
        assertThat(url).startsWith(google.base() + "/o/oauth2/v2/auth?");
        assertThat(q).containsEntry("client_id", "owner-client.apps.googleusercontent.com").containsEntry("redirect_uri", REDIRECT)
                .containsEntry("response_type", "code").containsEntry("code_challenge_method", "S256")
                .containsEntry("access_type", "offline")
                .containsEntry("scope", Connector.GMAIL_READONLY + " " + Connector.GMAIL_COMPOSE);
        assertThat(q.get("scope")).doesNotContain("gmail.send").doesNotContain("mail.google.com");
        connectors.callback(q.get("state"), "gmail-code", null);
        Map<String, String> form = google.requests.stream().filter(r -> r.path().equals("/token")).findFirst().orElseThrow().form();
        assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("code", "gmail-code")
                .containsEntry("client_secret", "owner-secret").containsEntry("redirect_uri", REDIRECT).containsKey("code_verifier");
        assertThat(secrets.get("connector.gmail").orElseThrow()).contains("rt-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) connectors.connectors().get("connectors");
        assertThat(list.get(1)).containsEntry("state", "connected").containsEntry("account", "owner@gmail.com");
        assertThat(connectors.connectors().toString()).doesNotContain("rt-1").doesNotContain("at-gmail-code");
    }

    @Test
    void toolsComeWithTheirConnectorAndTheDraftToolIsATaskOnlyExternalWriteWithAPreview() {
        assertThat(tools.tools()).isEmpty();
        connect("google_calendar", "cal-code");
        assertThat(tools.tools()).extracting(t -> t.spec().name()).containsExactly("calendar_list_events");
        connect("gmail", "gmail-code");
        List<ToolSource.SourcedTool> all = tools.tools();
        assertThat(all).extracting(t -> t.spec().name()).containsExactly("calendar_list_events", "gmail_search",
                "gmail_create_draft");
        ToolSource.SourcedTool draft = all.get(2);
        assertThat(draft.access()).isEqualTo(ToolAccess.EXTERNAL_WRITE);
        assertThat(draft.surfaces()).containsExactly(ToolSurface.TASK);
        assertThat(draft.handler()).isInstanceOf(ToolPreview.class);
        assertThat(((ToolPreview) draft.handler()).preview(Map.of("to", "Plumber <plumber@example.com>", "subject", "Re: Thursday",
                "body", "Thursday works."))).containsEntry("kind", "email_draft")
                .containsEntry("to", List.of("plumber@example.com")).containsEntry("subject", "Re: Thursday");
        assertThat(all.get(0).access()).isEqualTo(ToolAccess.READ);
        // the conversation never gets the draft tool
        ToolRegistry conversation = new ToolRegistry(ToolRegistry.fromSources(List.of(tools), ToolSurface.CONVERSATION), true,
                true, () -> wall);
        assertThat(conversation.offered()).extracting(t -> t.spec().name()).containsExactly("calendar_list_events",
                "gmail_search");
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarEventsOfADayInTheOwnersTimeZoneWithAttendeesNamesOnly() {
        connect("google_calendar", "cal-code");
        google.on("GET", "/calendar/v3/calendars/primary/events", 200, Map.of("items", List.of(
                Map.of("summary", "Football", "start", Map.of("dateTime", "2026-10-16T18:30:00+02:00"),
                        "end", Map.of("dateTime", "2026-10-16T20:00:00+02:00"), "location", "Stade du Ray",
                        "attendees", List.of(Map.of("email", "tom@example.com", "displayName", "Thomas"),
                                Map.of("email", "owner@gmail.com", "self", true, "displayName", "Seb"))),
                Map.of("summary", "Holiday", "start", Map.of("date", "2026-10-16"), "end", Map.of("date", "2026-10-17")),
                Map.of("summary", "Cancelled one", "status", "cancelled", "start", Map.of("date", "2026-10-16")))));
        ToolResult r = taskTools().call("calendar_list_events", Map.of("from", "2026-10-16", "to", "2026-10-16"), Map.of());
        assertThat(r.ok()).as(r.error()).isTrue();
        FakeGoogle.Request rq = google.requests.getLast();
        assertThat(rq.path()).isEqualTo("/calendar/v3/calendars/primary/events");
        assertThat(rq.authorization()).isEqualTo("Bearer at-cal-code");
        assertThat(rq.param("timeMin")).isEqualTo("2026-10-16T00:00+02:00");
        assertThat(rq.param("timeMax")).isEqualTo("2026-10-17T00:00+02:00");
        assertThat(rq.param("singleEvents")).isEqualTo("true");
        Map<String, Object> out = (Map<String, Object>) JsonText.parse(r.content());
        List<Map<String, Object>> events = (List<Map<String, Object>>) out.get("events");
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).containsEntry("title", "Football").containsEntry("start", "2026-10-16T18:30")
                .containsEntry("place", "Stade du Ray").containsEntry("with", List.of("Thomas"));
        assertThat(r.content()).doesNotContain("tom@example.com");
        assertThat(events.get(1)).containsEntry("all_day", true).containsEntry("start", "2026-10-16");
        // without a period: from now to the end of tomorrow
        taskTools().call("calendar_list_events", Map.of(), Map.of());
        assertThat(google.requests.getLast().param("timeMin")).startsWith("2026-10-15T08:00");
        assertThat(google.requests.getLast().param("timeMax")).isEqualTo("2026-10-17T00:00+02:00");
        assertThat(taskTools().call("calendar_list_events", Map.of("from", "next week"), Map.of()).error())
                .contains("is not a date");
    }

    @Test
    @SuppressWarnings("unchecked")
    void gmailSearchReadsMetadataOnly() {
        connect("gmail", "gmail-code");
        google.on("GET", "/gmail/v1/users/me/messages", 200, Map.of("messages", List.of(Map.of("id", "18c2f0a1", "threadId", "t1"),
                Map.of("id", "18c2f0a2", "threadId", "t2"))));
        google.on("GET", "/gmail/v1/users/me/messages/18c2f0a1", 200, Map.of("id", "18c2f0a1", "snippet", "Your renewal date is "
                + "1 November", "payload", Map.of("headers", List.of(Map.of("name", "From", "value", "Garage <garage@example.com>"),
                Map.of("name", "Subject", "value", "Insurance renewal"), Map.of("name", "Date", "value", "Mon, 12 Oct 2026")))));
        google.on("GET", "/gmail/v1/users/me/messages/18c2f0a2", 200, Map.of("id", "18c2f0a2", "snippet", "x"));
        ToolResult r = taskTools().call("gmail_search", Map.of("query", "insurance newer_than:30d", "max", 5), Map.of());
        assertThat(r.ok()).as(r.error()).isTrue();
        Map<String, Object> out = (Map<String, Object>) JsonText.parse(r.content());
        List<Map<String, Object>> messages = (List<Map<String, Object>>) out.get("messages");
        assertThat(messages.getFirst()).containsEntry("subject", "Insurance renewal").containsEntry("from",
                "Garage <garage@example.com>").containsEntry("snippet", "Your renewal date is 1 November");
        FakeGoogle.Request list = google.requests.stream().filter(x -> x.path().equals("/gmail/v1/users/me/messages"))
                .findFirst().orElseThrow();
        assertThat(list.param("q")).isEqualTo("insurance newer_than:30d");
        FakeGoogle.Request one = google.requests.stream().filter(x -> x.path().endsWith("/18c2f0a1")).findFirst().orElseThrow();
        assertThat(one.param("format")).isEqualTo("metadata");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDraftIsCreatedThroughTheDraftsCallOnlyAndNothingIsEverSent() {
        connect("gmail", "gmail-code");
        google.on("POST", "/gmail/v1/users/me/drafts", 200, Map.of("id", "r-123", "message", Map.of("id", "18c2f0ff",
                "threadId", "t9")));
        google.on("POST", "/gmail/v1/users/me/drafts/send", 200, Map.of("id", "SENT"));
        google.on("POST", "/gmail/v1/users/me/messages/send", 200, Map.of("id", "SENT"));
        ToolResult r = taskTools().call("gmail_create_draft", Map.of("to", "plumber@example.com, Tom <tom@example.com>",
                "subject", "Re: Jeudi ça marche", "body", "Bonjour,\nJeudi me va très bien.\nSébastien"), Map.of());
        assertThat(r.ok()).as(r.error()).isTrue();
        Map<String, Object> out = (Map<String, Object>) JsonText.parse(r.content());
        assertThat(out).containsEntry("created", true).containsEntry("draft_id", "r-123")
                .containsEntry("open_url", "https://mail.google.com/mail/u/0/#drafts?compose=18c2f0ff");
        FakeGoogle.Request post = google.requests.getLast();
        assertThat(post.path()).isEqualTo("/gmail/v1/users/me/drafts");
        Map<String, Object> body = (Map<String, Object>) JsonText.parse(post.body());
        String raw = new String(Base64.getUrlDecoder().decode((String) ((Map<String, Object>) body.get("message")).get("raw")),
                StandardCharsets.UTF_8);
        assertThat(raw).startsWith("To: plumber@example.com, tom@example.com\r\nSubject: =?UTF-8?B?")
                .contains("Content-Type: text/plain; charset=UTF-8\r\n");
        String encodedBody = raw.substring(raw.indexOf("\r\n\r\n") + 4).replaceAll("\\s", "");
        assertThat(new String(Base64.getDecoder().decode(encodedBody), StandardCharsets.UTF_8))
                .isEqualTo("Bonjour,\nJeudi me va très bien.\nSébastien");
        // header injection is refused before anything is sent
        int before = google.requests.size();
        assertThat(taskTools().call("gmail_create_draft", Map.of("to", "a@example.com", "subject", "x\r\nBcc: spy@example.com",
                "body", "b"), Map.of()).error()).isEqualTo("a subject is one line");
        assertThat(taskTools().call("gmail_create_draft", Map.of("to", "not an address", "subject", "x", "body", "b"), Map.of())
                .error()).contains("is not an email address");
        assertThat(google.requests).hasSize(before);
        // and a send or delete call cannot even be made by hand with a real token
        String token = connectors.accessToken("gmail").orElseThrow();
        assertThatThrownBy(() -> http.post("/gmail/v1/users/me/drafts/send", Map.of("id", "r-123"), token))
                .isInstanceOf(GoogleGuard.Blocked.class);
        assertThatThrownBy(() -> http.post("/gmail/v1/users/me/messages/send", Map.of("raw", "x"), token))
                .isInstanceOf(GoogleGuard.Blocked.class);
        assertThatThrownBy(() -> http.post("/gmail/v1/users/me/messages/18c2f0ff/trash", Map.of(), token))
                .isInstanceOf(GoogleGuard.Blocked.class);
        assertThat(google.paths()).noneMatch(p -> p.contains("send") || p.contains("trash") || p.startsWith("DELETE"));
    }

    @Test
    void aRefusedTokenPutsTheConnectorInErrorAndTheRefreshIsUsedBeforeExpiry() {
        connect("gmail", "gmail-code");
        wall += 3590;                                               // about to expire: refreshed first
        google.on("GET", "/gmail/v1/users/me/messages", 200, Map.of());
        ToolResult ok = taskTools().call("gmail_search", Map.of("query", "x"), Map.of());
        assertThat(ok.ok()).isTrue();
        assertThat(google.requests.getLast().authorization()).isEqualTo("Bearer at-refreshed");
        assertThat(google.requests.stream().filter(r -> r.path().equals("/token")).toList().getLast().form())
                .containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "rt-1");
        google.on("GET", "/gmail/v1/users/me/messages", 401, Map.of("error", Map.of("message", "Invalid Credentials")));
        ToolResult refused = taskTools().call("gmail_search", Map.of("query", "x"), Map.of());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.error()).contains("connect it again");
        assertThat(connectors.connected("gmail")).isFalse();
        assertThat(tools.tools()).isEmpty();                       // its tools are gone until connected again
        connectors.disconnect("gmail");
        assertThat(google.requests.getLast().path()).isEqualTo("/revoke");
        assertThat(google.requests.getLast().form()).containsEntry("token", "rt-1");
        assertThat(secrets.get("connector.gmail")).isEmpty();
    }

    @Test
    void withoutTheOwnersClientNothingConnects() {
        GoogleOAuth none = new GoogleOAuth(new GoogleClientFile(dir.resolve("missing.json")), http, clocks::wallSeconds);
        assertThat(none.clientInstalled()).isFalse();
        ConnectorService s = new ConnectorService(none, secrets, clocks);
        assertThat(s.connectors()).containsEntry("oauth_client", false);
        assertThatThrownBy(() -> s.connect("gmail", REDIRECT)).hasMessageContaining("OAuth client");
        assertThatThrownBy(() -> new GoogleTools(s, http, PARIS, clocks::wallSeconds).listEvents(Map.of(), Map.of()))
                .isInstanceOf(ToolError.class).hasMessageContaining("not connected");
        assertThat(google.requests).isEmpty();
    }
}
