// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.Set;

/** Where a tool is offered: in the conversation (spoken and typed turns), in background tasks, or both. */
public enum ToolSurface {
    CONVERSATION, TASK;

    /** Both. */
    public static final Set<ToolSurface> ALL = Set.of(CONVERSATION, TASK);
}
