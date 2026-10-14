// SPDX-License-Identifier: MIT
package marvin.host.domain.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Marvin's connectors ask for the narrowest scopes, never one that sends, deletes or writes a calendar (docs/design.md 7.3). */
class ScopePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://www.googleapis.com/auth/gmail.send", "https://mail.google.com/",
            "https://www.googleapis.com/auth/gmail.modify", "https://www.googleapis.com/auth/gmail.insert",
            "https://www.googleapis.com/auth/gmail.settings.basic", "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/calendar.events", "https://www.googleapis.com/auth/drive"})
    void neverAScopeThatSendsDeletesOrWrites(String scope) {
        assertThatThrownBy(() -> ScopePolicy.check(List.of(scope))).hasMessageContaining("never asks");
        assertThatThrownBy(() -> new Connector("x", "X", List.of(scope), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(ScopePolicy.grantedOk(List.of(Connector.GMAIL_READONLY, scope))).isFalse();
    }

    @Test
    void theConnectorsOfV1() {
        assertThat(Connector.ALL).extracting(Connector::id).containsExactly("google_calendar", "gmail");
        assertThat(Connector.GOOGLE_CALENDAR.scopes()).containsExactly("https://www.googleapis.com/auth/calendar.readonly");
        assertThat(Connector.GMAIL.scopes()).containsExactly("https://www.googleapis.com/auth/gmail.readonly",
                "https://www.googleapis.com/auth/gmail.compose");
        assertThat(Connector.GMAIL.tools()).extracting(Connector.Tool::name).containsExactly("gmail_search", "gmail_create_draft");
        assertThat(Connector.ALL).flatExtracting(Connector::tools).extracting(Connector.Tool::name)
                .noneMatch(n -> n.contains("send") || n.contains("delete") || n.contains("trash"));
        assertThat(Connector.of("gmail")).isSameAs(Connector.GMAIL);
        assertThatThrownBy(() -> Connector.of("slack")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ScopePolicy.grantedOk(List.of(Connector.CALENDAR_READONLY, "openid",
                "https://www.googleapis.com/auth/userinfo.email"))).isTrue();
    }
}
