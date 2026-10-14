// SPDX-License-Identifier: MIT
/**
 * Connectors (docs/design.md 7): what Marvin may reach outside this computer on the owner's behalf, with the owner's
 * own accounts. In v1: Google Calendar (read only) and Gmail (search, and drafts after an approval), through Google's
 * own APIs with the owner's OAuth desktop client. The scope policy here decides which permissions may ever be asked:
 * never one that sends or deletes.
 */
package marvin.host.domain.connector;
