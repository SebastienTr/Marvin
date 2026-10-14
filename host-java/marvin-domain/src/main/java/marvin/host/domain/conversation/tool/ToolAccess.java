// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

/**
 * What a tool does to the world (docs/design.md 6.3), which decides where it may run: a background task runs read and
 * local-write tools on its own; a tool that writes outside this computer runs only in a task, once the owner approved
 * the call in the app, and never in the conversation. Irreversible external actions (sending an email, deleting
 * anything remote, paying) are never tools at all ({@link ToolPolicy}).
 *
 * <p>Frozen for Marvin v1 (contract C7, 2026-10-14): these three values, no {@code IRREVERSIBLE} one
 * ({@code ToolAccessTest}).
 */
public enum ToolAccess {
    /** Reads only: the weather, recall, a picture described. */
    READ,
    /** Writes on this computer, logged and undoable in the app: remember. */
    LOCAL_WRITE,
    /** Writes outside this computer (an email draft, a calendar event): in a task, with the owner's approval of each call. */
    EXTERNAL_WRITE
}
