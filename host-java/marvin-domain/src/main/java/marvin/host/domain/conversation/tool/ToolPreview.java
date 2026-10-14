// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.Map;

/**
 * What the owner is shown before approving a call of a tool that writes outside this computer (docs/design.md 6.3): a
 * tool's handler (a {@code ToolSource.Handler}) may also implement it.
 * The preview is a small JSON-like map with a {@code kind} ({@code email_draft}: {@code to}, {@code subject},
 * {@code body}); without one, the approval shows the call's arguments.
 */
@FunctionalInterface
public interface ToolPreview {

    /** The preview of a call with these validated arguments. Never contacts anything outside this computer. */
    Map<String, Object> preview(Map<String, Object> arguments);
}
