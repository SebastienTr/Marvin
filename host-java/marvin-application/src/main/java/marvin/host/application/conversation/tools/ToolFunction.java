// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.tools;

import java.util.Map;

/**
 * What a tool does: called with its validated arguments (and, when the tool wants it, the conversation's
 * {@code language} in {@code context}). Returns a short text or anything JSON can encode (a small map is
 * best: the model reads it); throws {@link marvin.host.domain.conversation.tool.ToolError} for an expected
 * failure.
 */
@FunctionalInterface
public interface ToolFunction {
    Object call(Map<String, Object> arguments, Map<String, Object> context);
}
