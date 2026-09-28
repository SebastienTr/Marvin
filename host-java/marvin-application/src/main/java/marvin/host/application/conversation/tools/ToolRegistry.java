// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.domain.conversation.tool.ToolArguments;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.conversation.tool.ToolSpec;
import marvin.host.domain.shared.JsonText;

/**
 * The tools Marvin has, which of them the model is offered, and the safe way to run them (the Python host's
 * {@code tools.ToolRegistry}).
 *
 * <p>{@code enabled} false: no tools at all. {@code internet} false: online tools are not offered. A tool that
 * is not offered does not exist for the model: it is not in the request, and a call to it is answered as an
 * unknown tool. Running a call never throws: unknown tools, bad arguments, exceptions and timeouts all become
 * an error result the model can talk about.
 */
public final class ToolRegistry {
    private static final Logger log = Logger.getLogger("marvin.voice.tools");

    /** A tool: what the model is told, and what runs. */
    public record Tool(ToolSpec spec, ToolFunction fn) {
    }

    private final Map<String, Tool> tools = new TreeMap<>();
    private final boolean enabled;
    private final boolean internet;
    private final DoubleSupplier clock;
    private final List<Map<String, Object>> schemas;
    private static final ExecutorService RUNNER = Executors.newVirtualThreadPerTaskExecutor();

    public ToolRegistry(List<Tool> tools, boolean enabled, boolean internet, DoubleSupplier monotonicSeconds) {
        for (Tool t : tools) {
            this.tools.put(t.spec().name(), t);
        }
        this.enabled = enabled;
        this.internet = internet;
        this.clock = monotonicSeconds;
        List<Map<String, Object>> s = new ArrayList<>();
        for (Tool t : offered()) {
            s.add(t.spec().schema());
        }
        this.schemas = s.isEmpty() ? null : List.copyOf(s);
    }

    /** Every tool, offered or not, by name. */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public boolean isOffered(Tool t) {
        return enabled && (internet || !t.spec().online());
    }

    /** The tools the model is given, by name (a fixed order: the request never changes). */
    public List<Tool> offered() {
        return tools.values().stream().filter(this::isOffered).toList();
    }

    public Tool get(String name) {
        Tool t = tools.get(name);
        return t != null && isOffered(t) ? t : null;
    }

    /** Ollama's {@code tools} list for the offered tools, {@code null} when there are none. The same object every time. */
    public List<Map<String, Object>> ollamaTools() {
        return schemas;
    }

    /** Runs one call; never throws. */
    public ToolResult call(String name, Object arguments, Map<String, Object> context) {
        double t0 = clock.getAsDouble();
        Map<String, Object> shown = arguments instanceof Map<?, ?> m ? copy(m)
                : arguments == null || "".equals(arguments) ? Map.of()
                : Map.of("raw", truncate(String.valueOf(arguments), 200));
        Tool tool = get(name);
        if (tool == null) {
            List<String> names = offered().stream().map(t -> t.spec().name()).toList();
            return done(name, shown, t0, null, "unknown tool '" + name + "'"
                    + (names.isEmpty() ? "; no tools are available" : "; the tools are: " + String.join(", ", names)));
        }
        Map<String, Object> args;
        try {
            args = ToolArguments.validate(tool.spec().parameters(), arguments);
        } catch (ToolError e) {
            return done(name, shown, t0, null, e.getMessage());
        }
        Map<String, Object> ctx = tool.spec().wantsContext() ? (context == null ? Map.of() : context) : Map.of();
        Future<Object> f = RUNNER.submit(() -> tool.fn().call(args, ctx));
        Object value;
        try {
            value = f.get((long) (tool.spec().timeoutS() * 1000), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return done(name, args, t0, null, "no answer within " + fmt(tool.spec().timeoutS()) + " s");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof ToolError te) {
                return done(name, args, t0, null, te.getMessage());
            }
            log.log(Level.WARNING, "tool " + name + " failed", c);
            return done(name, args, t0, null, "the tool failed (" + c.getClass().getSimpleName() + ": " + c.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return done(name, args, t0, null, "interrupted");
        }
        String content = value instanceof String s ? s : JsonText.write(value);
        return done(name, args, t0, content, null);
    }

    private ToolResult done(String name, Map<String, Object> args, double t0, String content, String error) {
        double seconds = clock.getAsDouble() - t0;
        if (error != null) {
            log.info(() -> "tool " + name + " failed: " + error);
            return ToolResult.failure(name, args, error, seconds);
        }
        log.info(() -> "tool " + name + " answered: " + ToolResult.shorten(content, 160));
        return ToolResult.success(name, args, content, seconds);
    }

    private static Map<String, Object> copy(Map<?, ?> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** Python's {@code f"{x:g}"} for the timeouts used here. */
    static String fmt(double x) {
        return x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x);
    }
}
