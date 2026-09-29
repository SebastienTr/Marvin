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
 * @param images    images the model sees with a user message, base64 (Ollama's {@code images}); only ever on the
 *                  question being asked, never in the history (docs/voice.md "Showing Marvin an image")
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolName, List<String> images) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
        images = List.copyOf(images == null ? List.of() : images);
    }

    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolName) {
        this(role, content, toolCalls, toolName, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of(), null);
    }

    /** A user message with images (base64) the model sees with it. */
    public static ChatMessage user(String content, List<String> images) {
        return new ChatMessage("user", content, List.of(), null, images);
    }

    /** The same message without its images (what the history keeps). */
    public ChatMessage withoutImages() {
        return images.isEmpty() ? this : new ChatMessage(role, content, toolCalls, toolName, List.of());
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
