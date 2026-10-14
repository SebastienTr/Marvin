// SPDX-License-Identifier: MIT
package marvin.host.application.connector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.connector.port.in.ConnectorAccess;
import marvin.host.application.connector.port.in.ConnectorControl;
import marvin.host.application.connector.port.out.OAuthProvider;
import marvin.host.application.connector.port.out.SecretStore;
import marvin.host.domain.connector.Connector;
import marvin.host.domain.connector.ConnectorState;
import marvin.host.domain.connector.ScopePolicy;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.JsonText;

/**
 * The connectors (docs/design.md 7.3): connected one at a time in the browser of this computer with the owner's OAuth
 * client (authorization code with PKCE, a loopback redirect), each with only its own scopes; tokens in the
 * {@link SecretStore} under {@code connector.<id>}, refreshed a minute before they expire, revoked on disconnect. A token
 * never appears in a log line, an error, or what the app is told: only "connected as ...".
 */
public final class ConnectorService implements ConnectorControl, ConnectorAccess {
    private static final Logger log = Logger.getLogger("marvin.connectors");
    /** How long a consent page stays valid. */
    static final double PENDING_S = 600;
    /** A token this close to its expiry is refreshed first. */
    static final double REFRESH_AHEAD_S = 60;
    static final String KEY_PREFIX = "connector.";

    private record Pending(String connector, String verifier, String redirectUri, double created) {
    }

    private final OAuthProvider provider;
    private final SecretStore secrets;
    private final Clocks clocks;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();
    /** What is known to be kept, so that listing the tools does not ask the Keychain each time. */
    private final Map<String, Boolean> kept = new ConcurrentHashMap<>();

    public ConnectorService(OAuthProvider provider, SecretStore secrets, Clocks clocks) {
        this.provider = provider;
        this.secrets = secrets;
        this.clocks = clocks;
    }

    // ------------------------------------------------------------------ the app

    @Override
    public Map<String, Object> connectors() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Connector c : Connector.ALL) {
            Map<String, Object> s = secret(c.id()).orElse(null);
            String error = lastError.get(c.id());
            ConnectorState state = error != null ? ConnectorState.ERROR
                    : s != null ? ConnectorState.CONNECTED : ConnectorState.DISCONNECTED;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("state", state.id());
            m.put("account", s != null && s.get("account") instanceof String a && !a.isEmpty() ? a : null);
            m.put("scopes", c.scopes());
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Connector.Tool t : c.tools()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name", t.name());
                tm.put("class", t.access());
                tools.add(tm);
            }
            m.put("tools", tools);
            m.put("last_error", error);
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectors", list);
        out.put("oauth_client", clientInstalled());
        return out;
    }

    private boolean clientInstalled() {
        try {
            return provider.clientInstalled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String connect(String id, String redirectUri) {
        Connector c = Connector.of(id);
        if (!clientInstalled()) {
            throw new NotReady("Install your Google OAuth client first (docs/intelligence.md, Connecting Google): "
                    + "Marvin uses your own, never a shared one");
        }
        double now = clocks.wallSeconds();
        pending.values().removeIf(p -> now - p.created() > PENDING_S);
        String state = token(24);
        String verifier = token(48);
        pending.put(state, new Pending(c.id(), verifier, redirectUri, now));
        log.info("connector " + c.id() + ": consent page opened");
        return provider.authorizationUrl(c.scopes(), state, challenge(verifier), redirectUri);
    }

    @Override
    public String callback(String state, String code, String error) {
        Pending p = state == null ? null : pending.remove(state);
        if (p == null || clocks.wallSeconds() - p.created() > PENDING_S) {
            throw new Failed("This sign-in is unknown or too old: connect again from Marvin's app");
        }
        Connector c = Connector.of(p.connector());
        if (error != null && !error.isBlank()) {
            String why = "access_denied".equals(error) ? "You did not allow it" : "Google said: " + error;
            log.info("connector " + c.id() + ": not connected (" + error + ")");
            throw new Failed(why);
        }
        if (code == null || code.isBlank()) {
            throw new Failed("Google sent no code: connect again");
        }
        OAuthProvider.Tokens t;
        try {
            t = provider.exchange(code, p.verifier(), p.redirectUri());
        } catch (OAuthProvider.Failed e) {
            lastError.put(c.id(), e.getMessage());
            throw new Failed(e.getMessage());
        }
        if (!ScopePolicy.grantedOk(t.scopes())) {
            revokeQuietly(t.refreshToken() != null ? t.refreshToken() : t.accessToken());
            throw new Failed("Google granted more than Marvin asks for (" + String.join(", ", t.scopes()) + "): the "
                    + "access was revoked. Check your OAuth client's scopes");
        }
        List<String> missing = c.scopes().stream().filter(s -> !t.scopes().contains(s)).toList();
        if (!missing.isEmpty()) {
            revokeQuietly(t.refreshToken() != null ? t.refreshToken() : t.accessToken());
            throw new Failed("Not every permission was given (" + String.join(", ", missing) + "): connect again and tick "
                    + "them all");
        }
        if (t.refreshToken() == null || t.refreshToken().isBlank()) {
            throw new Failed("Google gave no refresh token: remove Marvin's access in your Google account's security "
                    + "page, then connect again");
        }
        String account = null;
        try {
            account = provider.account(c.id(), t.accessToken());
        } catch (RuntimeException e) {
            log.log(Level.FINE, "connector " + c.id() + ": could not tell the account", e);
        }
        save(c.id(), t, t.refreshToken(), account);
        lastError.remove(c.id());
        log.info("connector " + c.id() + ": connected" + (account == null ? "" : " as " + account));
        return c.id();
    }

    @Override
    public void disconnect(String id) {
        Connector c = Connector.of(id);
        Optional<Map<String, Object>> s = secret(c.id());
        s.ifPresent(m -> revokeQuietly(m.get("refresh_token") instanceof String r ? r
                : m.get("access_token") instanceof String a ? a : null));
        secrets.delete(key(c.id()));
        kept.put(c.id(), false);
        lastError.remove(c.id());
        log.info("connector " + c.id() + ": disconnected");
    }

    // ------------------------------------------------------------------ the tools

    @Override
    public boolean connected(String connectorId) {
        return kept.computeIfAbsent(connectorId, k -> secret(k).isPresent()) && !lastError.containsKey(connectorId);
    }

    @Override
    public synchronized Optional<String> accessToken(String connectorId) {
        Optional<Map<String, Object>> s = secret(connectorId);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> m = s.get();
        double expires = m.get("expires_at") instanceof Number n ? n.doubleValue() : 0;
        if (m.get("access_token") instanceof String a && !a.isEmpty() && expires - REFRESH_AHEAD_S > clocks.wallSeconds()) {
            return Optional.of(a);
        }
        if (!(m.get("refresh_token") instanceof String refresh) || refresh.isEmpty()) {
            lastError.put(connectorId, "No refresh token: connect again");
            return Optional.empty();
        }
        try {
            OAuthProvider.Tokens t = provider.refresh(refresh);
            save(connectorId, t, t.refreshToken() != null ? t.refreshToken() : refresh,
                    m.get("account") instanceof String acc ? acc : null);
            lastError.remove(connectorId);
            return Optional.of(t.accessToken());
        } catch (OAuthProvider.Failed e) {
            lastError.put(connectorId, "Google refused to renew the access (" + e.getMessage() + "): connect again");
            log.warning("connector " + connectorId + ": the refresh was refused");
            return Optional.empty();
        }
    }

    @Override
    public void refused(String connectorId, String why) {
        lastError.put(connectorId, why == null || why.isBlank() ? "Google refused a call: connect again" : why);
    }

    // ------------------------------------------------------------------ secrets

    private void save(String id, OAuthProvider.Tokens t, String refresh, String account) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", t.accessToken());
        m.put("refresh_token", refresh);
        m.put("expires_at", t.expiresAt());
        m.put("scopes", t.scopes());
        m.put("account", account == null ? "" : account);
        secrets.put(key(id), JsonText.write(m));
        kept.put(id, true);
    }

    private Optional<Map<String, Object>> secret(String id) {
        try {
            Optional<String> raw = secrets.get(key(id));
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JsonText.parse(raw.get()) instanceof Map<?, ?> x ? (Map<String, Object>) x : null;
            return Optional.ofNullable(m);
        } catch (RuntimeException e) {
            log.warning("connector " + id + ": its secret could not be read (" + e.getClass().getSimpleName() + ")");
            return Optional.empty();
        }
    }

    static String key(String id) {
        return KEY_PREFIX + id;
    }

    private void revokeQuietly(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            provider.revoke(token);
        } catch (RuntimeException e) {
            log.info("a token could not be revoked at the provider (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ------------------------------------------------------------------ PKCE

    private String token(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** RFC 7636: the S256 challenge of a verifier. */
    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
