// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

/**
 * What a tool does to the world (docs/design.md 6.3), which decides where it may run: a background task runs read and
 * local-write tools on its own; a tool that writes outside this computer needs the owner's approval, which does not
 * exist yet, so no task is offered one. Irreversible external actions (sending an email, deleting anything remote,
 * paying) are never tools at all.
 */
public enum ToolAccess {
    /** Reads only: the weather, recall, a picture described. */
    READ,
    /** Writes on this computer, logged and undoable in the app: remember. */
    LOCAL_WRITE,
    /** Writes outside this computer (an email draft, a calendar event): needs the owner's approval in a task. */
    EXTERNAL_WRITE
}
