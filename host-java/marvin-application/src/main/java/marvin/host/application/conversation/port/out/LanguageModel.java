// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;
import java.util.Map;
import java.util.Set;

import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ModelThinking;
import marvin.host.domain.conversation.Reasoning;
import marvin.host.domain.conversation.ToolCall;

/**
 * The language model: a local Ollama server, streamed. Every request uses the same context size, temperature and
 * keep-alive, so the model server never reloads the model because of them. Spoken turns do not think (the default);
 * a request may ask the model to think first ({@link Thinking}), for background tasks or when the owner wants
 * reasoning in the conversation too: its thinking comes apart from its answer ({@link Stream#thinking(String)}).
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

    /**
     * The same, the model thinking first as {@code thinking} says and writing at most its {@code maxTokens} (thinking
     * included). With {@link Thinking#OFF} it is {@link #streamChat(String, String, List, List, double, Stream)}, byte for
     * byte. A model or a server that cannot think at the level asked thinks as it can (a level it accepts, then on, then
     * not at all), never fails for it.
     */
    default void streamChat(String host, String model, List<ChatMessage> messages, List<Map<String, Object>> tools,
                            double timeoutS, Thinking thinking, Stream stream) {
        streamChat(host, model, messages, tools, timeoutS, stream);
    }

    /** How a request thinks: its level, and how many tokens it may write (its thinking included). */
    record Thinking(Reasoning level, int maxTokens) {
        /** Spoken turns: no thinking, short answers (the persona keeps them short; the cap only stops runaways). */
        public static final Thinking OFF = new Thinking(Reasoning.OFF, 400);
        /** A turn of the conversation that thinks: room for the thinking, then a short answer. */
        public static final int CHAT_TOKENS = 4096;
        /** A step of a background task: its thinking, then a tool call or the result. */
        public static final int TASK_TOKENS = 6144;

        public Thinking {
            level = level == null ? Reasoning.OFF : level;
            maxTokens = Math.max(1, maxTokens);
        }

        /** A conversation turn at {@code level}. */
        public static Thinking chat(Reasoning level) {
            return level == null || level == Reasoning.OFF ? OFF : new Thinking(level, CHAT_TOKENS);
        }
    }

    /**
     * What the app shows of a model: its size on disk, what it can do, how it thinks. Never throws: what cannot be read
     * is unknown ({@code sizeBytes} -1, no capabilities).
     */
    default ModelInfo describe(String host, String model) {
        try {
            Set<String> caps = capabilities(host, model);
            return new ModelInfo(model, -1, caps, ModelThinking.of(caps.isEmpty() ? null : caps, null));
        } catch (Unavailable e) {
            return new ModelInfo(model, -1, Set.of(), ModelThinking.UNKNOWN);
        }
    }

    /**
     * A model as the server describes it.
     *
     * @param sizeBytes its size on disk ({@code /api/tags}), -1 when unknown
     */
    record ModelInfo(String name, long sizeBytes, Set<String> capabilities, ModelThinking thinking) {
        public ModelInfo {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            thinking = thinking == null ? ModelThinking.UNKNOWN : thinking;
        }
    }

    /** The model names the server has. @throws Unavailable when it cannot be reached */
    List<String> models(String host);

    /**
     * {@code model} is the voice's: the model server should keep it loaded (Ollama: {@code keep_alive: -1}), and say
     * when another model pushed it out. Nothing by default.
     */
    default void keepLoaded(String host, String model) {
    }

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

        /** A piece of the model's thinking, apart from its answer (only when the request asked it to think). */
        default void thinking(String piece) {
        }

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
