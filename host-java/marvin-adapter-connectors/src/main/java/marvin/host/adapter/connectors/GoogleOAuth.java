// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import marvin.host.application.connector.port.out.OAuthProvider;

/**
 * Google's OAuth 2.0 for installed apps (an authorization code with PKCE, a loopback redirect), with the owner's own
 * desktop client ({@link GoogleClientFile}); the account a token belongs to, from the Gmail profile or the primary
 * calendar's id (no extra scope).
 */
public final class GoogleOAuth implements OAuthProvider {
    private final GoogleClientFile client;
    private final GoogleHttp http;
    private final java.util.function.DoubleSupplier wallSeconds;

    public GoogleOAuth(GoogleClientFile client, GoogleHttp http, java.util.function.DoubleSupplier wallSeconds) {
        this.client = client;
        this.http = http;
        this.wallSeconds = wallSeconds;
    }

    @Override
    public boolean clientInstalled() {
        return client.read().isPresent();
    }

    private GoogleClientFile.Client clientOrFail() {
        return client.read().orElseThrow(() -> new Failed("the OAuth client file is missing: " + client.path()));
    }

    @Override
    public String authorizationUrl(List<String> scopes, String state, String codeChallenge, String redirectUri) {
        GoogleClientFile.Client c = clientOrFail();
        return http.endpoints().auth() + "?" + GoogleHttp.form(List.of(
                Map.entry("client_id", c.id()),
                Map.entry("redirect_uri", redirectUri),
                Map.entry("response_type", "code"),
                Map.entry("scope", String.join(" ", scopes)),
                Map.entry("state", state),
                Map.entry("code_challenge", codeChallenge),
                Map.entry("code_challenge_method", "S256"),
                Map.entry("access_type", "offline"),
                Map.entry("prompt", "consent")));
    }

    @Override
    public Tokens exchange(String code, String codeVerifier, String redirectUri) {
        GoogleClientFile.Client c = clientOrFail();
        return tokens(post(List.of(Map.entry("code", code), Map.entry("client_id", c.id()),
                Map.entry("client_secret", c.secret()), Map.entry("redirect_uri", redirectUri),
                Map.entry("grant_type", "authorization_code"), Map.entry("code_verifier", codeVerifier))));
    }

    @Override
    public Tokens refresh(String refreshToken) {
        GoogleClientFile.Client c = clientOrFail();
        return tokens(post(List.of(Map.entry("refresh_token", refreshToken), Map.entry("client_id", c.id()),
                Map.entry("client_secret", c.secret()), Map.entry("grant_type", "refresh_token"))));
    }

    private Object post(List<Map.Entry<String, String>> form) {
        try {
            return http.tokenForm(http.endpoints().token(), form);
        } catch (GoogleHttp.Failed e) {
            throw new Failed(e.getMessage());
        }
    }

    private Tokens tokens(Object json) {
        if (!(json instanceof Map<?, ?> m) || !(m.get("access_token") instanceof String access)) {
            throw new Failed("Google sent no access token");
        }
        double expiresIn = m.get("expires_in") instanceof Number n ? n.doubleValue() : 3600;
        List<String> scopes = new ArrayList<>();
        if (m.get("scope") instanceof String s) {
            for (String x : s.trim().split("\\s+")) {
                if (!x.isEmpty()) {
                    scopes.add(x);
                }
            }
        }
        return new Tokens(access, m.get("refresh_token") instanceof String r ? r : null, wallSeconds.getAsDouble() + expiresIn,
                scopes);
    }

    @Override
    public void revoke(String token) {
        try {
            http.tokenForm(http.endpoints().revoke(), List.of(Map.entry("token", token)));
        } catch (GoogleHttp.Failed e) {
            // an invalid token is already as good as revoked
        }
    }

    @Override
    public String account(String connectorId, String accessToken) {
        Object json = "gmail".equals(connectorId)
                ? http.get("/gmail/v1/users/me/profile", List.of(), accessToken)
                : http.get("/calendar/v3/calendars/primary", List.of(), accessToken);
        if (json instanceof Map<?, ?> m) {
            if (m.get("emailAddress") instanceof String e) {
                return e;
            }
            if (m.get("id") instanceof String id && id.contains("@")) {
                return id;
            }
        }
        return null;
    }
}
