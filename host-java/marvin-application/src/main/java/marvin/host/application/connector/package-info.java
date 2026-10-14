// SPDX-License-Identifier: MIT
/**
 * Connectors' use cases (docs/design.md 7): connecting one with the owner's own OAuth client in the browser (PKCE,
 * a loopback redirect to this host), keeping its tokens in the {@code SecretStore} (never the database, the logs or a
 * prompt), refreshing them, revoking them on disconnect, and telling the app where each one is. The connectors' tools
 * are an adapter's, behind the conversation's {@code ToolSource} port; they ask this context for an access token.
 */
package marvin.host.application.connector;
