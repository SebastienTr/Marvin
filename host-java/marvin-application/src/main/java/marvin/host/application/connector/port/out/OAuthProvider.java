// SPDX-License-Identifier: MIT
package marvin.host.application.connector.port.out;

import java.util.List;

/**
 * The OAuth provider (Google) with the owner's own desktop client: the consent page's address, the code exchange, the
 * refresh, the revocation, and the account a token belongs to.
 */
public interface OAuthProvider {

    /**
     * Tokens as the provider gave them.
     *
     * @param refreshToken {@code null} when the provider gave none (a refresh keeps the previous one)
     * @param expiresAt    Unix seconds
     * @param scopes       the scopes granted
     */
    record Tokens(String accessToken, String refreshToken, double expiresAt, List<String> scopes) {
        @Override
        public String toString() {
            return "Tokens[scopes=" + scopes + ", expiresAt=" + expiresAt + "]";             // never the tokens themselves
        }
    }

    /** Whether the owner's OAuth client is installed (its file is there and readable). */
    boolean clientInstalled();

    /** The consent page for these scopes (PKCE S256 challenge, offline access, the loopback redirect). */
    String authorizationUrl(List<String> scopes, String state, String codeChallenge, String redirectUri);

    /** @throws Failed the provider refused the code */
    Tokens exchange(String code, String codeVerifier, String redirectUri);

    /** @throws Failed the provider refused the refresh (revoked, expired) */
    Tokens refresh(String refreshToken);

    /** Revokes a token at the provider; never throws for a token that is already invalid. */
    void revoke(String token);

    /** The account a connector's token belongs to (an email address), or {@code null} when it cannot tell. */
    String account(String connectorId, String accessToken);

    /** A refusal or a failure of the provider, in words for the owner (never a token). */
    final class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }
}
