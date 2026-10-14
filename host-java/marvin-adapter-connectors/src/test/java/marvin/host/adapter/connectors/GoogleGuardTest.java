// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The allow-list of Google calls: what the tools need, and never a send, a delete, a trash or a modify. */
class GoogleGuardTest {

    @ParameterizedTest
    @CsvSource({"GET,/calendar/v3/calendars/primary/events", "GET,/calendar/v3/calendars/primary",
            "GET,/gmail/v1/users/me/profile", "GET,/gmail/v1/users/me/messages", "GET,/gmail/v1/users/me/messages/18c2f0a1b2",
            "POST,/gmail/v1/users/me/drafts"})
    void theCallsTheToolsNeed(String method, String path) {
        assertThatCode(() -> GoogleGuard.check(method, path)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"POST,/gmail/v1/users/me/drafts/send", "POST,/gmail/v1/users/me/messages/send",
            "POST,/upload/gmail/v1/users/me/messages/send", "DELETE,/gmail/v1/users/me/drafts/r123",
            "DELETE,/gmail/v1/users/me/messages/18c2", "POST,/gmail/v1/users/me/messages/18c2/trash",
            "POST,/gmail/v1/users/me/threads/18c2/trash", "POST,/gmail/v1/users/me/messages/18c2/modify",
            "POST,/gmail/v1/users/me/messages/batchDelete", "POST,/gmail/v1/users/me/messages/import",
            "POST,/gmail/v1/users/me/messages", "PUT,/gmail/v1/users/me/drafts/r123", "GET,/gmail/v1/users/me/drafts",
            "POST,/gmail/v1/users/me/settings/forwardingAddresses", "POST,/calendar/v3/calendars/primary/events",
            "DELETE,/calendar/v3/calendars/primary/events/e1", "PATCH,/calendar/v3/calendars/primary/events/e1",
            "GET,/gmail/v1/users/me/messages/../drafts/send", "GET,/drive/v3/files", "POST,/gmail/v1/users/me/drafts?x=send"})
    void everythingElseIsBlockedBeforeItIsSent(String method, String path) {
        assertThatThrownBy(() -> GoogleGuard.check(method, path)).isInstanceOf(GoogleGuard.Blocked.class)
                .hasMessageStartingWith("blocked: Marvin never makes this call");
    }
}
