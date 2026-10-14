// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ModelThinking;
import marvin.host.domain.conversation.Reasoning;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.shared.JsonText;

/**
 * The language model through Spring AI's Ollama client ({@code POST /api/chat}, streamed; {@code GET
 * /api/tags}). Every request carries the same options: thinking off, temperature 0.6, at most 400 tokens
 * (the persona keeps spoken answers short; the cap only stops runaways), a context of 16384 tokens (set
 * explicitly: a change reloads the
 * model), and the model kept in memory for 30 minutes. The tool loop is the conversation service's, not
 * Spring AI's: tool calls are passed on as they stream.
 *
 * <p>A request that thinks ({@link LanguageModel.Thinking}) goes through {@link OllamaHttp} instead, with the same
 * options but its own length: {@code think} is the level the model lists for the one asked ({@link Reasoning#thinkValue}),
 * read once per model from {@code /api/show}. When Ollama refuses it (an older server, a model without levels), the
 * request is made again with thinking simply on, then off, and the value that worked is kept for that model.
 */
public final class OllamaLanguageModel implements LanguageModel {
    private static final Logger log = LoggerFactory.getLogger("marvin.voice.llm");
    public static final String KEEP_ALIVE = "30m";

    private final Map<String, OllamaApi> clients = new ConcurrentHashMap<>();

    /** The options of every request, in the Python host's order. */
    static Map<String, Object> options() {
        return options(Thinking.OFF.maxTokens());
    }

    /** The same, at most {@code numPredict} tokens written. The context size never changes: a change reloads the model. */
    static Map<String, Object> options(int numPredict) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("temperature", 0.6);
        o.put("num_predict", numPredict);
        o.put("num_ctx", NUM_CTX);
        return o;
    }

    /** The context of every request (prompt and answer together). */
    public static final int NUM_CTX = 16384;

    private final OllamaHttp http = new OllamaHttp();
    private final OllamaKeepAlive keepAlive;

    public OllamaLanguageModel() {
        this(new OllamaKeepAlive());
    }

    /** With the keep-alive policy shared with memory's model and the embeddings. */
    public OllamaLanguageModel(OllamaKeepAlive keepAlive) {
        this.keepAlive = java.util.Objects.requireNonNull(keepAlive, "keepAlive");
    }

    /** The voice's model stays loaded ({@code keep_alive: -1}). */
    @Override
    public void keepLoaded(String host, String model) {
        keepAlive.pin(host, model);
    }
    /** What each model says of its thinking, by host and model (asked once). */
    private final Map<String, ModelThinking> thinking = new ConcurrentHashMap<>();
    /** The {@code think} value that worked after Ollama refused the one asked, by host, model and level. */
    private final Map<String, Object> thinkFallback = new ConcurrentHashMap<>();

    private OllamaApi api(String host) {
        return clients.computeIfAbsent(host.replaceAll("/+$", ""), h -> OllamaApi.builder().baseUrl(h).build());
    }

    private static String hint(String model) {
        return "Install Ollama (https://ollama.com/download or `brew install ollama`), start it "
                + "(`ollama serve` or the Ollama app), then `ollama pull " + model + "`.";
    }

    /** The request body: warm-up and questions use exactly the same shape. */
    static OllamaApi.ChatRequest request(String model, List<ChatMessage> messages, List<Map<String, Object>> tools) {
        return request(model, messages, tools, Thinking.OFF.maxTokens());
    }

    static OllamaApi.ChatRequest request(String model, List<ChatMessage> messages, List<Map<String, Object>> tools,
                                         int numPredict) {
        return request(model, messages, tools, numPredict, KEEP_ALIVE);
    }

    static OllamaApi.ChatRequest request(String model, List<ChatMessage> messages, List<Map<String, Object>> tools,
                                         int numPredict, String keep) {
        OllamaApi.ChatRequest.Builder b = OllamaApi.ChatRequest.builder(model)
                .messages(messages.stream().map(OllamaLanguageModel::message).toList())
                .stream(true)
                .options(options(numPredict))
                .keepAlive(keep)
                .disableThinking();
        if (tools != null && !tools.isEmpty()) {
            b.tools(tools.stream().map(OllamaLanguageModel::tool).toList());
        }
        return b.build();
    }

    static OllamaApi.Message message(ChatMessage m) {
        OllamaApi.Message.Builder b = OllamaApi.Message.builder(switch (m.role()) {
            case "system" -> OllamaApi.Message.Role.SYSTEM;
            case "assistant" -> OllamaApi.Message.Role.ASSISTANT;
            case "tool" -> OllamaApi.Message.Role.TOOL;
            default -> OllamaApi.Message.Role.USER;
        }).content(m.content());
        if (!m.toolCalls().isEmpty()) {
            b.toolCalls(m.toolCalls().stream().map(c -> new OllamaApi.Message.ToolCall(c.id(),
                    new OllamaApi.Message.ToolCallFunction(c.name(), c.argumentsMap()))).toList());
        }
        if (m.toolName() != null) {
            b.toolName(m.toolName());
        }
        if (!m.images().isEmpty()) {
            b.images(m.images());               // base64, on the question being asked only
        }
        return b.build();
    }

    @SuppressWarnings("unchecked")
    static OllamaApi.ChatRequest.Tool tool(Map<String, Object> schema) {
        Map<String, Object> fn = (Map<String, Object>) schema.get("function");
        return new OllamaApi.ChatRequest.Tool(OllamaApi.ChatRequest.Tool.Type.FUNCTION,
                new OllamaApi.ChatRequest.Tool.Function((String) fn.get("name"), (String) fn.get("description"),
                        (Map<String, Object>) fn.get("parameters")));
    }

    @Override
    public void streamChat(String host, String model, List<ChatMessage> messages, List<Map<String, Object>> tools,
                           double timeoutS, LanguageModel.Stream stream) {
        springChat(host, model, request(model, messages, tools, Thinking.OFF.maxTokens(), keepAlive.of(host, model)),
                timeoutS, stream);
    }

    @Override
    public void streamChat(String host, String model, List<ChatMessage> messages, List<Map<String, Object>> tools,
                           double timeoutS, Thinking thinks, LanguageModel.Stream stream) {
        if (thinks.level() == Reasoning.OFF) {
            springChat(host, model, request(model, messages, tools, thinks.maxTokens(), keepAlive.of(host, model)), timeoutS,
                    stream);
            return;
        }
        String key = host + " " + model + " " + thinks.level().id();
        Object think = thinkFallback.containsKey(key) ? thinkFallback.get(key) : thinks.level().thinkValue(thinkingOf(host, model));
        while (true) {
            Map<String, Object> body = OllamaHttp.chatBody(model, messages, tools, options(thinks.maxTokens()), think);
            body.put("keep_alive", keepAlive.of(host, model));
            try {
                http.chat(host, body, timeoutS, stream);
                keepAlive.used(host, model);
                return;
            } catch (ToolsUnsupported e) {
                throw e;
            } catch (Unavailable e) {
                throw e.hint().isEmpty() ? new Unavailable(e.getMessage(), hint(model)) : e;
            } catch (OllamaHttp.Failed e) {
                Object next = nextThink(think, e);
                if (next == null) {
                    throw translate(e, host, model);
                }
                log.warn("{} refused think={} ({}): asking again with think={}", model, think, e.error, next);
                think = next;
                thinkFallback.put(key, think);
            }
        }
    }

    /** What to send after Ollama refused {@code think}: thinking simply on, then off; {@code null} when not about thinking. */
    static Object nextThink(Object think, OllamaHttp.Failed e) {
        String err = e.error.toLowerCase(java.util.Locale.ROOT);
        if (e.status != 400 || !err.contains("think")) {
            return null;
        }
        if (err.contains("does not support thinking")) {
            return Boolean.FALSE.equals(think) ? null : Boolean.FALSE;
        }
        if (think instanceof String) {
            return Boolean.TRUE;
        }
        return Boolean.TRUE.equals(think) ? Boolean.FALSE : null;
    }

    /** An HTTP error of the direct client, as {@link #translate} words Spring AI's. */
    private static RuntimeException translate(OllamaHttp.Failed e, String host, String model) {
        if (e.status == 400 && e.error.contains("does not support tools")) {
            return new ToolsUnsupported(model + " cannot use tools: " + e.error);
        }
        if (e.status == 404) {
            return new Unavailable("Ollama has no model '" + model + "': " + (e.error.isEmpty() ? "not found" : e.error),
                    "Run `ollama pull " + model + "`.");
        }
        return new Unavailable(e.getMessage(), hint(model));
    }

    /** What {@code model} says of its thinking (asked once; unknown when the server cannot be asked). */
    ModelThinking thinkingOf(String host, String model) {
        String key = host + " " + model;
        ModelThinking t = thinking.get(key);
        if (t != null) {
            return t;
        }
        try {
            Map<?, ?> show = http.show(host, model, 10);
            t = thinkingFrom(show);
            thinking.put(key, t);
            log.info("{} thinks: {}", model, t.toMap());
            return t;
        } catch (OllamaHttp.Failed e) {
            if (e.status == 404) {
                throw translate(e, host, model);
            }
            return ModelThinking.UNKNOWN;
        } catch (RuntimeException e) {
            return ModelThinking.UNKNOWN;           // the chat request says what is wrong with the server
        }
    }

    static ModelThinking thinkingFrom(Map<?, ?> show) {
        List<String> caps = show.get("capabilities") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : null;
        return ModelThinking.of(caps, show.get("thinking"));
    }

    /**
     * The model's size on disk ({@code /api/tags}), what it can do and how it thinks ({@code /api/show}); unknown parts
     * when the server cannot say.
     */
    @Override
    public ModelInfo describe(String host, String model) {
        long size = -1;
        Set<String> caps = Set.of();
        ModelThinking t = ModelThinking.UNKNOWN;
        try {
            if (http.tags(host, 5).get("models") instanceof List<?> models) {
                for (Object o : models) {
                    if (o instanceof Map<?, ?> m && (model.equals(m.get("name")) || (model + ":latest").equals(m.get("name")))
                            && m.get("size") instanceof Number n) {
                        size = n.longValue();
                    }
                }
            }
            Map<?, ?> show = http.show(host, model, 10);
            if (show.get("capabilities") instanceof List<?> l) {
                caps = new LinkedHashSet<>(l.stream().map(String::valueOf).toList());
            }
            t = thinkingFrom(show);
            thinking.put(host + " " + model, t);
        } catch (RuntimeException e) {
            log.debug("cannot describe {}: {}", model, e.getMessage());
        }
        return new ModelInfo(model, size, caps, t);
    }

    private void springChat(String host, String model, OllamaApi.ChatRequest req, double timeoutS,
                            LanguageModel.Stream stream) {
        try {
            springChatOnce(host, model, req, timeoutS, stream);
        } finally {
            keepAlive.used(host, model);        // another model than the voice's: is the voice's still loaded?
        }
    }

    private void springChatOnce(String host, String model, OllamaApi.ChatRequest req, double timeoutS,
                                LanguageModel.Stream stream) {
        Duration timeout = Duration.ofMillis((long) (timeoutS * 1000));
        try (java.util.stream.Stream<OllamaApi.ChatResponse> s = api(host).streamingChat(req).timeout(timeout).toStream(1)) {
            Iterator<OllamaApi.ChatResponse> it = s.iterator();
            while (!stream.cancelled() && it.hasNext()) {
                OllamaApi.ChatResponse r = it.next();
                OllamaApi.Message m = r.message();
                if (m != null) {
                    if (m.toolCalls() != null) {
                        for (OllamaApi.Message.ToolCall tc : m.toolCalls()) {
                            ToolCall call = toolCall(tc);
                            if (call != null) {
                                stream.toolCall(call);
                            }
                        }
                    }
                    if (m.content() != null && !m.content().isEmpty()) {
                        stream.text(m.content());
                    }
                }
                if (Boolean.TRUE.equals(r.done())) {
                    if (r.promptEvalCount() != null) {
                        stream.usage(new Usage(r.promptEvalCount(), seconds(r.promptEvalDuration()),
                                r.evalCount() == null ? 0 : r.evalCount(), seconds(r.evalDuration()), seconds(r.loadDuration())));
                    }
                    break;
                }
            }
        } catch (RuntimeException e) {
            RuntimeException t = translate(e, host, model);
            if (t.getCause() == null && t != e) {
                t.initCause(e);
            }
            throw t;
        }
    }

    private static double seconds(Long nanos) {
        return nanos == null ? 0 : nanos / 1e9;
    }

    private static ToolCall toolCall(OllamaApi.Message.ToolCall tc) {
        if (tc.function() == null) {
            return null;
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", tc.function().name());
        fn.put("arguments", tc.function().arguments() == null ? Map.of() : tc.function().arguments());
        raw.put("function", fn);
        if (tc.id() != null) {
            raw.put("id", tc.id());
        }
        return ToolCall.parse(raw);
    }

    @Override
    public List<String> models(String host) {
        try {
            OllamaApi.ListModelResponse r = api(host).listModels();
            List<String> names = new ArrayList<>();
            if (r != null && r.models() != null) {
                for (OllamaApi.Model m : r.models()) {
                    if (m.name() != null && !m.name().isEmpty()) {
                        names.add(m.name());
                    }
                }
            }
            return names;
        } catch (RuntimeException e) {
            throw new Unavailable(reason(e), "");
        }
    }

    /**
     * Ollama's {@code POST /api/show}: its {@code capabilities}; a server too old to list them is asked whether the
     * model has a vision projector ({@code projector_info}), which is what "vision" means there.
     */
    @Override
    public Set<String> capabilities(String host, String model) {
        OllamaApi.ShowModelResponse r;
        try {
            r = api(host).showModel(new OllamaApi.ShowModelRequest(model));
        } catch (RuntimeException e) {
            RuntimeException t = translate(e, host, model);
            if (t.getCause() == null && t != e) {
                t.initCause(e);
            }
            throw t;
        }
        Set<String> caps = new LinkedHashSet<>();
        if (r != null && r.capabilities() != null) {
            caps.addAll(r.capabilities());
        } else if (r != null && r.projectorInfo() != null && !r.projectorInfo().isEmpty()) {
            caps.add("completion");
            caps.add(VISION);
        }
        log.info("{} can: {}", model, caps);
        return caps;
    }

    /** The model server's error, as the Python host words it. */
    static RuntimeException translate(RuntimeException e, String host, String model) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String body = null;
            int code = 0;
            if (t instanceof WebClientResponseException w) {
                body = w.getResponseBodyAsString();
                code = w.getStatusCode().value();
            } else if (t instanceof RestClientResponseException w) {
                body = w.getResponseBodyAsString();
                code = w.getStatusCode().value();
            } else if (t instanceof org.springframework.ai.retry.NonTransientAiException
                    && t.getMessage() != null && t.getMessage().matches("(?s)\\d{3} - .*")) {
                // Spring AI's error handler on the blocking calls (/api/show): "404 - {body}"
                code = Integer.parseInt(t.getMessage().substring(0, 3));
                body = t.getMessage().substring(6);
            }
            if (code != 0) {
                String msg = errorOf(body);
                if (code == 400 && msg.contains("does not support tools")) {
                    return new ToolsUnsupported(model + " cannot use tools: " + msg);
                }
                if (code == 404) {
                    return new Unavailable("Ollama has no model '" + model + "': " + (msg.isEmpty() ? "not found" : msg),
                            "Run `ollama pull " + model + "`.");
                }
                return new Unavailable("Ollama error " + code + ": " + (msg.isEmpty() ? "failed" : msg), hint(model));
            }
            if (t instanceof TimeoutException) {
                return new Unavailable("cannot reach Ollama at " + host + ": timed out", hint(model));
            }
        }
        log.debug("model request failed", e);
        return new Unavailable("cannot reach Ollama at " + host + ": " + reason(e), hint(model));
    }

    private static String errorOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            if (JsonText.parse(body) instanceof Map<?, ?> m && m.get("error") instanceof String s) {
                return s;
            }
        } catch (IllegalArgumentException e) {
            // not JSON
        }
        return "";
    }

    private static String reason(Throwable e) {
        return NetErrors.reason(e);
    }
}
