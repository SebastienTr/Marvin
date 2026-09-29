// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;
import java.util.Map;
import java.util.Set;

import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ToolCall;

/**
 * The language model: a local Ollama server, streamed. Every request uses the same options (thinking off,
 * the same context size, temperature, length and keep-alive), so the model server never reloads the model
 * or reprocesses the prompt because of them.
 */
public interface LanguageModel {

    /**
     * Streams the answer to {@code messages} into {@code stream}: text pieces, and a tool call for each tool
     * the model calls from {@code tools} (Ollama's {@code tools} list, {@code null} for none). Returns when
     * the model is done or {@link Stream#cancelled()} says so.
     *
     * @throws Unavailable    the model server cannot be reached, has no such model, or failed
     * @throws ToolsUnsupported the model cannot use tools (ask again without them)
     */
    void streamChat(String host, String model, List<ChatMessage> messages, List<Map<String, Object>> tools,
                    double timeoutS, Stream stream);

    /** The model names the server has. @throws Unavailable when it cannot be reached */
    List<String> models(String host);

    /**
     * What {@code model} can do, as the model server says (Ollama's {@code /api/show} {@code capabilities}:
     * {@code completion}, {@code vision}, {@code tools}, {@code thinking} ...). Empty when it does not say.
     *
     * @throws Unavailable the server cannot be reached or has no such model
     */
    Set<String> capabilities(String host, String model);

    /** The capability of a model that can look at images. */
    String VISION = "vision";

    /** Receives a streamed answer. Called from the model's thread. */
    interface Stream {
        void text(String piece);

        void toolCall(ToolCall call);

        /** Stop reading the answer. */
        boolean cancelled();

        /** What the model server reports at the end of an answer (not every server does). */
        default void usage(Usage usage) {
        }
    }

    /**
     * The model server's counts for one request (Ollama's {@code prompt_eval_count} and friends). With a cached
     * prompt prefix, {@code promptEvalCount} counts only the tokens evaluated again, not the whole prompt.
     *
     * @param loadSeconds loading the model (0 when it was loaded)
     */
    record Usage(int promptEvalCount, double promptEvalSeconds, int evalCount, double evalSeconds, double loadSeconds) {
    }

    /** The model cannot be used; {@code hint} says how to fix it. */
    class Unavailable extends RuntimeException {
        private final String hint;

        public Unavailable(String message, String hint) {
            super(message);
            this.hint = hint == null ? "" : hint;
        }

        public String hint() {
            return hint;
        }
    }

    /** The model cannot use tools (Ollama: "... does not support tools"). */
    class ToolsUnsupported extends Unavailable {
        public ToolsUnsupported(String message) {
            super(message, "");
        }
    }
}
