// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;

import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.shared.JsonText;

/**
 * The memory jobs through Ollama's {@code /api/chat} with a JSON schema as {@code format} (structured output),
 * streamed so that a call is abandoned as soon as the voice needs the model (closing the stream makes Ollama stop).
 * The prompts are resources ({@code marvin/memory/prompts/v1/}); the options keep the voice's context size, so that
 * using the voice's model for memory does not reload it.
 */
public final class OllamaMemoryModel implements MemoryModel {
    private static final Logger log = LoggerFactory.getLogger("marvin.memory.model");
    public static final String VERSION = "memory-prompts/1";
    static final String PROMPTS = "/marvin/memory/prompts/v1/";
    static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final DateTimeFormatter LINE = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.ROOT);

    private final Map<String, OllamaApi> clients = new ConcurrentHashMap<>();
    private final Map<String, String> prompts = new ConcurrentHashMap<>();

    private OllamaApi api(String host) {
        return clients.computeIfAbsent(host.replaceAll("/+$", ""), h -> OllamaApi.builder().baseUrl(h).build());
    }

    String prompt(String name) {
        return prompts.computeIfAbsent(name, n -> {
            try (InputStream in = OllamaMemoryModel.class.getResourceAsStream(PROMPTS + n + ".txt")) {
                if (in == null) {
                    throw new IllegalStateException("missing prompt " + n);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public String version() {
        return VERSION;
    }

    // ------------------------------------------------------------------ schemas

    static Map<String, Object> obj(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        return m;
    }

    static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    static Map<String, Object> enumOf(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    static final Map<String, Object> EXTRACT_SCHEMA;
    static final Map<String, Object> RECONCILE_SCHEMA;
    static final Map<String, Object> SUMMARY_SCHEMA;
    static final Map<String, Object> PROFILE_SCHEMA;

    static {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("subject", str());
        fact.put("statement", str());
        fact.put("kind", enumOf(java.util.Arrays.stream(FactKind.values()).map(FactKind::wire).toList()));
        fact.put("valid_from", str());
        fact.put("valid_to", str());
        fact.put("importance", Map.of("type", "integer", "minimum", 1, "maximum", 10));
        fact.put("sensitivity", enumOf(List.of("normal", "personal", "sensitive", "secret")));
        fact.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1));
        EXTRACT_SCHEMA = obj(Map.of("facts", Map.of("type", "array", "items", obj(fact, List.copyOf(fact.keySet())))),
                List.of("facts"));
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("operation", enumOf(Operation.NAMES));
        decision.put("target", Map.of("type", "integer", "minimum", 0));
        decision.put("statement", str());
        decision.put("valid_to", str());
        RECONCILE_SCHEMA = obj(decision, List.copyOf(decision.keySet()));
        SUMMARY_SCHEMA = obj(Map.of("summary", str()), List.of("summary"));
        PROFILE_SCHEMA = obj(Map.of("lines", Map.of("type", "array", "items", str())), List.of("lines"));
    }

    // ------------------------------------------------------------------ jobs

    @Override
    public Extraction extract(Target target, ExtractRequest r, BooleanSupplier cancelled) {
        String system = prompt("extract").replace("{{profile}}", r.profile().isBlank() ? "(nothing yet)" : r.profile().strip());
        StringBuilder user = new StringBuilder("Conversation (the owner's local time, ")
                .append(r.now().getZone().getId()).append("):\n");
        for (Line l : r.lines()) {
            user.append(LINE.format(l.at())).append(' ').append(l.who()).append(": ").append(l.text().replace('\n', ' ')).append('\n');
        }
        user.append("\nNow: ").append(LINE.format(r.now())).append('.');
        Answer a = call(target, system, user.toString(), EXTRACT_SCHEMA, 1024, cancelled);
        List<FactCandidate.Raw> facts = new ArrayList<>();
        if (!(a.json().get("facts") instanceof List<?> list)) {
            throw new BadOutput("no \"facts\" list");
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                facts.add(new FactCandidate.Raw(text(m.get("subject")), text(m.get("statement")), text(m.get("kind")),
                        text(m.get("valid_from")), text(m.get("valid_to")), number(m.get("importance")),
                        text(m.get("sensitivity")), number(m.get("confidence"))));
            }
        }
        return new Extraction(facts, a.usage());
    }

    @Override
    public Decision reconcile(Target target, ReconcileRequest r, BooleanSupplier cancelled) {
        FactCandidate c = r.candidate();
        StringBuilder user = new StringBuilder("Candidate, said on ").append(r.eventTime().toLocalDate()).append(":\n[")
                .append(c.subject()).append("] ").append(c.statement());
        if (c.validFrom() != null) {
            user.append(" (from ").append(LocalDate.ofInstant(c.validFrom(), r.eventTime().getZone())).append(')');
        }
        user.append("\n\nExisting facts:\n");
        int n = 1;
        for (Fact f : r.similar()) {
            user.append(n++).append(". [").append(f.subject()).append("] ").append(f.statement());
            if (f.validFrom() != null) {
                user.append(" (since ").append(LocalDate.ofInstant(f.validFrom(), r.eventTime().getZone())).append(')');
            }
            user.append('\n');
        }
        Answer a = call(target, prompt("reconcile"), user.toString(), RECONCILE_SCHEMA, 200, cancelled);
        Object op = a.json().get("operation");
        if (!(op instanceof String s)) {
            throw new BadOutput("no \"operation\"");
        }
        Number t = number(a.json().get("target"));
        return new Decision(s, t == null || t.intValue() <= 0 ? null : t.intValue(), text(a.json().get("statement")),
                text(a.json().get("valid_to")), a.usage());
    }

    @Override
    public Text summarize(Target target, SummaryRequest r, BooleanSupplier cancelled) {
        String period = switch (r.level()) {
            case DAY -> "the day of " + r.first().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH))
                    + " from its events";
            case WEEK -> "the week from " + r.first() + " to " + r.last() + " from its days";
            case MONTH -> "the month of " + r.first().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
                    + " from its weeks";
        };
        String system = prompt("summary").replace("{{period}}", period).replace("{{max_words}}", Integer.toString(r.maxWords()));
        String user = (r.level() == EpisodeLevel.DAY ? "Events:\n" : "Summaries:\n") + String.join("\n", r.items());
        Answer a = call(target, system, user, SUMMARY_SCHEMA, r.maxWords() * 2 + 100, cancelled);
        if (!(a.json().get("summary") instanceof String s)) {
            throw new BadOutput("no \"summary\"");
        }
        return new Text(s.strip(), a.usage());
    }

    @Override
    public Text rewriteProfile(Target target, ProfileRequest r, BooleanSupplier cancelled) {
        String system = prompt("profile").replace("{{max_tokens}}", Integer.toString(r.maxTokens()))
                .replace("{{max_words}}", Integer.toString(r.maxTokens() * 3 / 4));
        StringBuilder user = new StringBuilder("Previous profile:\n");
        if (r.previous().isBlank()) {
            user.append("(empty)\n");
        } else {
            for (String l : r.previous().split("\\R")) {
                if (!l.isBlank()) {
                    user.append(r.kept().contains(l.strip()) ? "KEEP " : "").append(l.strip()).append('\n');
                }
            }
        }
        for (String k : r.kept()) {
            if (!r.previous().contains(k)) {
                user.append("KEEP ").append(k).append('\n');
            }
        }
        user.append("\nLearned since:\n");
        r.learned().forEach(l -> user.append("- ").append(l).append('\n'));
        if (r.learned().isEmpty()) {
            user.append("(nothing)\n");
        }
        user.append("\nNo longer true:\n");
        r.ended().forEach(l -> user.append("- ").append(l).append('\n'));
        if (r.ended().isEmpty()) {
            user.append("(nothing)\n");
        }
        Answer a = call(target, system, user.toString(), PROFILE_SCHEMA, r.maxTokens() * 2, cancelled);
        if (!(a.json().get("lines") instanceof List<?> lines)) {
            throw new BadOutput("no \"lines\"");
        }
        List<String> out = new ArrayList<>();
        for (Object o : lines) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s.strip().replaceFirst("^KEEP\\s+", ""));
            }
        }
        return new Text(String.join("\n", out), a.usage());
    }

    // ------------------------------------------------------------------ the call

    record Answer(Map<String, Object> json, Usage usage) {
    }

    static Map<String, Object> options(int numPredict) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("temperature", 0.0);
        o.put("num_predict", numPredict);
        o.put("num_ctx", OllamaLanguageModel.options().get("num_ctx"));
        return o;
    }

    static OllamaApi.ChatRequest request(String model, String system, String user, Map<String, Object> schema, int numPredict) {
        return OllamaApi.ChatRequest.builder(model)
                .messages(List.of(OllamaApi.Message.builder(OllamaApi.Message.Role.SYSTEM).content(system).build(),
                        OllamaApi.Message.builder(OllamaApi.Message.Role.USER).content(user).build()))
                .stream(true)
                .format(schema)
                .options(options(numPredict))
                .keepAlive(OllamaLanguageModel.KEEP_ALIVE)
                .disableThinking()
                .build();
    }

    @SuppressWarnings("unchecked")
    private Answer call(Target target, String system, String user, Map<String, Object> schema, int numPredict,
                        BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new Cancelled();
        }
        OllamaApi.ChatRequest req = request(target.model(), system, user, schema, numPredict);
        StringBuilder content = new StringBuilder();
        OllamaApi.ChatResponse last = null;
        try (java.util.stream.Stream<OllamaApi.ChatResponse> s = api(target.host()).streamingChat(req).timeout(TIMEOUT).toStream(1)) {
            Iterator<OllamaApi.ChatResponse> it = s.iterator();
            while (it.hasNext()) {
                if (cancelled.getAsBoolean()) {
                    throw new Cancelled();                  // closing the stream ends the request: Ollama stops
                }
                OllamaApi.ChatResponse r = it.next();
                if (r.message() != null && r.message().content() != null) {
                    content.append(r.message().content());
                }
                if (Boolean.TRUE.equals(r.done())) {
                    last = r;
                    break;
                }
            }
        } catch (Cancelled e) {
            throw e;
        } catch (RuntimeException e) {
            OllamaErrors.Failure f = OllamaErrors.of(e);
            if (f.status() == 404) {
                throw new Unavailable("Ollama has no model '" + target.model() + "'", "Run `ollama pull " + target.model() + "`.");
            }
            if (f.status() != 0) {
                throw new Unavailable("Ollama error " + f.status() + ": " + (f.message().isEmpty() ? "failed" : f.message()), "");
            }
            throw new Unavailable("cannot reach Ollama at " + target.host() + ": " + f.message(), OllamaErrors.install(target.model()));
        }
        Usage usage = last == null ? Usage.NONE : new Usage(n(last.promptEvalCount()), seconds(last.promptEvalDuration()),
                n(last.evalCount()), seconds(last.totalDuration()));
        if (log.isDebugEnabled()) {
            log.debug("memory model {}: prompt {} tokens in {} s, {} tokens out", target.model(), usage.promptTokens(),
                    usage.promptSeconds(), usage.outputTokens());
        }
        String text = content.toString().strip();
        Object parsed;
        try {
            parsed = JsonText.parse(text);
        } catch (IllegalArgumentException e) {
            throw new BadOutput("not JSON: " + (text.length() > 120 ? text.substring(0, 120) + "…" : text));
        }
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new BadOutput("not a JSON object");
        }
        return new Answer((Map<String, Object>) m, usage);
    }

    private static int n(Integer i) {
        return i == null ? 0 : i;
    }

    private static double seconds(Long nanos) {
        return nanos == null ? 0 : nanos / 1e9;
    }

    private static String text(Object o) {
        return o == null ? null : o instanceof String s ? s : String.valueOf(o);
    }

    private static Number number(Object o) {
        if (o instanceof Number n) {
            return n;
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
