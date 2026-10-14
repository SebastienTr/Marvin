// SPDX-License-Identifier: MIT
package marvin.host.application.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import marvin.host.application.connector.port.in.ConnectorControl;
import marvin.host.application.connector.port.out.OAuthProvider;
import marvin.host.application.connector.port.out.SecretStore;
import marvin.host.domain.connector.Connector;
import marvin.host.domain.shared.Clocks;

/**
 * Connecting, refreshing and disconnecting on stand-ins (docs/design.md 7.3): PKCE and a one-time state, only the
 * connector's own scopes, tokens kept only in the secret store and never shown, refreshed before they expire, revoked on
 * disconnect.
 */
class ConnectorServiceTest {
    volatile double wall = 1_790_000_000;
    final Map<String, String> kept = new LinkedHashMap<>();
    final List<String> revoked = new ArrayList<>();
    final List<String> asked = new ArrayList<>();
    boolean client = true;
    List<String> granted;
    boolean refuseRefresh;
    int refreshes;

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

    final SecretStore secrets = new SecretStore() {
        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(kept.get(key));
        }

        @Override
        public void put(String key, String value) {
            kept.put(key, value);
        }

        @Override
        public void delete(String key) {
            kept.remove(key);
        }

        @Override
        public String where() {
            return "memory";
        }
    };

    final OAuthProvider provider = new OAuthProvider() {
        @Override
        public boolean clientInstalled() {
            return client;
        }

        @Override
        public String authorizationUrl(List<String> scopes, String state, String codeChallenge, String redirectUri) {
            asked.add(String.join(" ", scopes) + "|" + state + "|" + codeChallenge + "|" + redirectUri);
            return "https://accounts.example/auth?state=" + state;
        }

        @Override
        public Tokens exchange(String code, String codeVerifier, String redirectUri) {
            if (!ConnectorService.challenge(codeVerifier).equals(asked.getLast().split("\\|")[2])) {
                throw new Failed("bad verifier");
            }
            if ("bad".equals(code)) {
                throw new Failed("Google refused the code (invalid_grant)");
            }
            return new Tokens("access-1", "refresh-1", wall + 3600, granted);
        }

        @Override
        public Tokens refresh(String refreshToken) {
            if (refuseRefresh) {
                throw new Failed("invalid_grant");
            }
            refreshes++;
            return new Tokens("access-" + (refreshes + 1), null, wall + 3600, granted);
        }

        @Override
        public void revoke(String token) {
            revoked.add(token);
        }

        @Override
        public String account(String connectorId, String accessToken) {
            return "owner@gmail.com";
        }
    };

    final ConnectorService service = new ConnectorService(provider, secrets, clocks);

    static String state(String url) {
        return url.substring(url.indexOf("state=") + 6);
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectRefreshAndDisconnect() {
        granted = Connector.GMAIL.scopes();
        String url = service.connect("gmail", "http://127.0.0.1:8765/api/connectors/callback");
        assertThat(asked.getLast()).startsWith("https://www.googleapis.com/auth/gmail.readonly "
                + "https://www.googleapis.com/auth/gmail.compose|").endsWith("|http://127.0.0.1:8765/api/connectors/callback");
        assertThat(service.connected("gmail")).isFalse();
        assertThat(service.callback(state(url), "code-1", null)).isEqualTo("gmail");
        assertThat(service.connected("gmail")).isTrue();
        Map<String, Object> gmail = ((List<Map<String, Object>>) service.connectors().get("connectors")).get(1);
        assertThat(gmail).containsEntry("state", "connected").containsEntry("account", "owner@gmail.com")
                .containsEntry("last_error", null);
        assertThat(service.connectors().toString()).doesNotContain("access-1").doesNotContain("refresh-1");
        assertThat(kept.get("connector.gmail")).contains("refresh-1");
        // the state is used once
        assertThatThrownBy(() -> service.callback(state(url), "code-1", null)).isInstanceOf(ConnectorControl.Failed.class);

        assertThat(service.accessToken("gmail")).contains("access-1");
        wall += 3590;                                                 // a minute before it expires: renewed first
        assertThat(service.accessToken("gmail")).contains("access-2");
        assertThat(kept.get("connector.gmail")).contains("refresh-1");   // the refresh token is kept

        service.disconnect("gmail");
        assertThat(revoked).containsExactly("refresh-1");
        assertThat(kept).isEmpty();
        assertThat(service.connected("gmail")).isFalse();
        assertThat(service.accessToken("gmail")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusalsAndMissingPieces() {
        client = false;
        assertThatThrownBy(() -> service.connect("gmail", "http://127.0.0.1/cb")).isInstanceOf(ConnectorControl.NotReady.class);
        assertThat(service.connectors()).containsEntry("oauth_client", false);
        client = true;
        assertThatThrownBy(() -> service.connect("slack", "http://127.0.0.1/cb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.callback("forged", "x", null)).hasMessageContaining("unknown or too old");
        String denied = service.connect("google_calendar", "http://127.0.0.1/cb");
        assertThatThrownBy(() -> service.callback(state(denied), null, "access_denied")).hasMessage("You did not allow it");
        String old = service.connect("google_calendar", "http://127.0.0.1/cb");
        wall += ConnectorService.PENDING_S + 1;
        assertThatThrownBy(() -> service.callback(state(old), "c", null)).hasMessageContaining("too old");
        // more than asked: revoked at once, never kept
        granted = List.of(Connector.CALENDAR_READONLY, "https://www.googleapis.com/auth/gmail.send");
        String more = service.connect("google_calendar", "http://127.0.0.1/cb");
        assertThatThrownBy(() -> service.callback(state(more), "c", null)).hasMessageContaining("granted more");
        assertThat(revoked).containsExactly("refresh-1");
        // less than asked
        granted = List.of(Connector.GMAIL_READONLY);
        String less = service.connect("gmail", "http://127.0.0.1/cb");
        assertThatThrownBy(() -> service.callback(state(less), "c", null)).hasMessageContaining("Not every permission");
        assertThat(kept).isEmpty();
        // a refused refresh: in error, its tools hidden, until connected again
        granted = Connector.GOOGLE_CALENDAR.scopes();
        service.callback(state(service.connect("google_calendar", "http://127.0.0.1/cb")), "c", null);
        refuseRefresh = true;
        wall += 7200;
        assertThat(service.accessToken("google_calendar")).isEmpty();
        assertThat(service.connected("google_calendar")).isFalse();
        Map<String, Object> cal = ((List<Map<String, Object>>) service.connectors().get("connectors")).get(0);
        assertThat(cal).containsEntry("state", "error");
        assertThat((String) cal.get("last_error")).contains("connect again").doesNotContain("refresh-1");
    }

    @Test
    void theChallengeIsRfc7636() {
        // RFC 7636 appendix B
        assertThat(ConnectorService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
}
