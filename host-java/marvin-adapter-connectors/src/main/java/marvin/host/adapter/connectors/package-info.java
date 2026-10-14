// SPDX-License-Identifier: MIT
/**
 * The connectors' adapter (docs/design.md 7; docs/intelligence.md, Connecting Google): Google's OAuth with the owner's
 * own desktop client, Google Calendar and Gmail through their REST APIs, offered to the conversation as tools
 * ({@code ToolSource}); and the secret stores. Every call to Google goes through {@link marvin.host.adapter.connectors.GoogleGuard}: an allow-list of
 * the few calls the tools need. Sending, deleting, trashing or modifying anything is not on it and is refused before a
 * byte leaves this computer.
 */
package marvin.host.adapter.connectors;
