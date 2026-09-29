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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.shared.JsonText;

/**
 * The memory jobs through Ollama's {@code /api/chat} with a JSON schema as {@code format} (structured output),
 * streamed so that a call is abandoned as soon as the voice needs the model (closing the connection makes Ollama stop).
 * The prompts are resources ({@code marvin/memory/prompts/v1/}); the options keep the voice's context size, so that
 * using the voice's model for memory does not reload it.
 */
public final class OllamaMemoryModel implements MemoryModel {
    private static final Logger log = LoggerFactory.getLogger("marvin.memory.model");
    public static final String VERSION = "memory-prompts/1";
    static final String PROMPTS = "/marvin/memory/prompts/v1/";
    static final Duration TIMEOUT = Duration.ofMinutes(5);
    /** How often a call in flight checks whether the voice needs the model (also before the first chunk). */
    static final Duration CANCEL_POLL = Duration.ofMillis(100);
    private static final DateTimeFormatter LINE = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.ROOT);

    private final Map<String, String> prompts = new ConcurrentHashMap<>();

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
        Answer a = call(target, system, user, SUMMARY_SCHEMA, r.maxWords() * 3 + 200, cancelled);
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
        if (!r.remove().isEmpty()) {
            user.append("\nForgotten by the owner (remove every line that states or implies any of these, even in other words):\n");
            r.remove().forEach(l -> user.append("- ").append(l).append('\n'));
        }
        // room for the whole JSON: a cut answer is unreadable, and at temperature 0 it would be cut again every night
        Answer a = call(target, system, user.toString(), PROFILE_SCHEMA, r.maxTokens() * 3 + 200, cancelled);
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

    /** Memory's calls, on their own client: closing a response's stream must close its connection (see {@link #call}). */
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** The body of {@code /api/chat}, as {@link #request} builds it for Spring AI's client. */
    static Map<String, Object> body(String model, String system, String user, Map<String, Object> schema, int numPredict) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("model", model);
        b.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        b.put("stream", true);
        b.put("format", schema);
        b.put("keep_alive", OllamaLanguageModel.KEEP_ALIVE);
        b.put("options", options(numPredict));
        b.put("think", false);
        return b;
    }

    /**
     * One streamed call. Ollama sends nothing while it loads the model and reads the prompt (the longest part with a
     * large night model), so {@code cancelled} is not only checked between chunks: a watcher polls it and closes the
     * response, which closes the connection, and Ollama stops at once. (Spring AI's reactive client does not close the
     * connection when its stream is cancelled, so memory's calls use the JDK's client directly.)
     */
    @SuppressWarnings("unchecked")
    private Answer call(Target target, String system, String user, Map<String, Object> schema, int numPredict,
                        BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new Cancelled();
        }
        String host = target.host().replaceAll("/+$", "");
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(host + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        JsonText.write(body(target.model(), system, user, schema, numPredict))))
                .build();
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<InputStream>> sent =
                HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        java.util.concurrent.atomic.AtomicReference<InputStream> body = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean cut = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean over = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        Thread watcher = Thread.ofVirtual().name("memory-model-watch").start(() -> {
            while (!over.get()) {
                if (cancelled.getAsBoolean() || System.nanoTime() > deadline) {
                    cut.set(true);
                    sent.cancel(true);
                    closeQuietly(body.get());
                    return;
                }
                try {
                    Thread.sleep(CANCEL_POLL.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        StringBuilder content = new StringBuilder();
        Map<String, Object> last = null;
        try {
            java.net.http.HttpResponse<InputStream> resp = sent.get();
            body.set(resp.body());
            if (cut.get()) {
                closeQuietly(resp.body());
            }
            if (resp.statusCode() != 200) {
                String err;
                try (InputStream in = resp.body()) {
                    err = OllamaErrors.errorOf(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                if (resp.statusCode() == 404) {
                    throw new Unavailable("Ollama has no model '" + target.model() + "'", "Run `ollama pull " + target.model() + "`.");
                }
                throw new Unavailable("Ollama error " + resp.statusCode() + ": " + (err.isEmpty() ? "failed" : err), "");
            }
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    if (cancelled.getAsBoolean()) {
                        throw new Cancelled();              // closing the stream ends the request: Ollama stops
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    if (!(JsonText.parse(line) instanceof Map<?, ?> chunk)) {
                        continue;
                    }
                    if (chunk.get("error") instanceof String e) {
                        throw new Unavailable("Ollama error: " + e, "");
                    }
                    if (chunk.get("message") instanceof Map<?, ?> msg && msg.get("content") instanceof String piece) {
                        content.append(piece);
                    }
                    if (Boolean.TRUE.equals(chunk.get("done"))) {
                        last = (Map<String, Object>) chunk;
                        break;
                    }
                }
            }
        } catch (Cancelled | Unavailable e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Cancelled();
        } catch (java.util.concurrent.CancellationException e) {
            throw cancelledOrTimedOut(cut, cancelled, target);
        } catch (java.util.concurrent.ExecutionException | IOException | IllegalArgumentException e) {
            if (cut.get()) {
                throw cancelledOrTimedOut(cut, cancelled, target);
            }
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IllegalArgumentException bad) {
                throw new BadOutput("not JSON: " + bad.getMessage());
            }
            throw new Unavailable("cannot reach Ollama at " + target.host() + ": " + NetErrors.reason(cause),
                    OllamaErrors.install(target.model()));
        } finally {
            over.set(true);
            watcher.interrupt();
            closeQuietly(body.get());
        }
        if (last == null && cancelled.getAsBoolean()) {
            throw new Cancelled();
        }
        Usage usage = last == null ? Usage.NONE : new Usage(count(last.get("prompt_eval_count")), nanos(last.get("prompt_eval_duration")),
                count(last.get("eval_count")), nanos(last.get("total_duration")));
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

    private static RuntimeException cancelledOrTimedOut(java.util.concurrent.atomic.AtomicBoolean cut, BooleanSupplier cancelled,
                                                        Target target) {
        if (cancelled.getAsBoolean()) {
            return new Cancelled();
        }
        return new Unavailable("Ollama did not answer within " + TIMEOUT.toMinutes() + " minutes (" + target.model() + ")", "");
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                // closing is all we wanted
            }
        }
    }

    private static int count(Object o) {
        return o instanceof Number x ? x.intValue() : 0;
    }

    private static double nanos(Object o) {
        return o instanceof Number x ? x.doubleValue() / 1e9 : 0;
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
