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
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.shared.JsonText;

/**
 * The language model through Spring AI's Ollama client ({@code POST /api/chat}, streamed; {@code GET
 * /api/tags}). Every request carries the same options: thinking off, temperature 0.6, at most 400 tokens
 * (the persona keeps spoken answers short; the cap only stops runaways), a context of 16384 tokens (set
 * explicitly: a change reloads the
 * model), and the model kept in memory for 30 minutes. The tool loop is the conversation service's, not
 * Spring AI's: tool calls are passed on as they stream.
 */
public final class OllamaLanguageModel implements LanguageModel {
    private static final Logger log = LoggerFactory.getLogger("marvin.voice.llm");
    public static final String KEEP_ALIVE = "30m";

    private final Map<String, OllamaApi> clients = new ConcurrentHashMap<>();

    /** The options of every request, in the Python host's order. */
    static Map<String, Object> options() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("temperature", 0.6);
        o.put("num_predict", 400);
        o.put("num_ctx", 16384);
        return o;
    }

    private OllamaApi api(String host) {
        return clients.computeIfAbsent(host.replaceAll("/+$", ""), h -> OllamaApi.builder().baseUrl(h).build());
    }

    private static String hint(String model) {
        return "Install Ollama (https://ollama.com/download or `brew install ollama`), start it "
                + "(`ollama serve` or the Ollama app), then `ollama pull " + model + "`.";
    }

    /** The request body: warm-up and questions use exactly the same shape. */
    static OllamaApi.ChatRequest request(String model, List<ChatMessage> messages, List<Map<String, Object>> tools) {
        OllamaApi.ChatRequest.Builder b = OllamaApi.ChatRequest.builder(model)
                .messages(messages.stream().map(OllamaLanguageModel::message).toList())
                .stream(true)
                .options(options())
                .keepAlive(KEEP_ALIVE)
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
        OllamaApi.ChatRequest req = request(model, messages, tools);
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
