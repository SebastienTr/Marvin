// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.List;
import java.util.Objects;

/**
 * One message of a conversation with the model, as Ollama's chat API takes it.
 *
 * @param role      {@code system}, {@code user}, {@code assistant} or {@code tool}
 * @param toolCalls an assistant message's tool calls (empty otherwise)
 * @param toolName  a tool message's tool ({@code null} otherwise)
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolName) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of(), null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, List.of(), null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> calls) {
        return new ChatMessage("assistant", content, calls, null);
    }

    public static ChatMessage tool(String name, String content) {
        return new ChatMessage("tool", content, List.of(), name);
    }
}
