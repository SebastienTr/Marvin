// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import marvin.host.application.conversation.port.out.ToolSource;
import marvin.host.domain.conversation.tool.ToolAccess;
import marvin.host.domain.conversation.tool.ToolArguments;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.ToolPolicy;
import marvin.host.domain.conversation.tool.ToolPreview;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.conversation.tool.ToolSpec;
import marvin.host.domain.conversation.tool.ToolSurface;
import marvin.host.domain.shared.JsonText;

/**
 * The tools Marvin has, which of them the model is offered, and the safe way to run them (the Python host's
 * {@code tools.ToolRegistry}).
 *
 * <p>{@code enabled} false: no tools at all. {@code internet} false: online tools are not offered. A tool that
 * is not offered does not exist for the model: it is not in the request, and a call to it is answered as an
 * unknown tool. Running a call never throws: unknown tools, bad arguments, exceptions and timeouts all become
 * an error result the model can talk about.
 *
 * <p>Each tool says what it does to the world ({@link ToolAccess}) and where it is offered ({@link ToolSurface}): the
 * conversation's registry has the conversation's tools, {@link #forTasks(List, boolean)} a background task's: a tool that
 * writes outside this computer only when the task can ask the owner's approval for each call. Tools from outside the
 * conversation (a connector) come through {@link ToolSource}s ({@link #fromSources}).
 *
 * <p>The tool filter ({@link ToolPolicy}) is applied here, to every tool whatever its source: a tool that would send,
 * delete, pay or share is never registered, and a tool that writes outside this computer is never offered in the
 * conversation.
 */
public final class ToolRegistry {
    private static final Logger log = Logger.getLogger("marvin.voice.tools");

    /**
     * A tool: what the model is told, what runs, what it does to the world, where it is offered, and, for a tool that
     * writes outside this computer, what the owner is shown before approving a call ({@code null}: its arguments).
     */
    public record Tool(ToolSpec spec, ToolFunction fn, ToolAccess access, Set<ToolSurface> surfaces, ToolPreview preview) {
        public Tool {
            access = access == null ? ToolAccess.READ : access;
            surfaces = surfaces == null || surfaces.isEmpty() ? ToolSurface.ALL : Set.copyOf(surfaces);
        }

        public Tool(ToolSpec spec, ToolFunction fn, ToolAccess access, Set<ToolSurface> surfaces) {
            this(spec, fn, access, surfaces, null);
        }

        /** A tool that only reads, offered everywhere. */
        public Tool(ToolSpec spec, ToolFunction fn) {
            this(spec, fn, ToolAccess.READ, ToolSurface.ALL);
        }

        public Tool with(ToolAccess a, Set<ToolSurface> s) {
            return new Tool(spec, fn, a, s, preview);
        }

        public boolean offeredIn(ToolSurface s) {
            return surfaces.contains(s);
        }
    }

    /** The tools of {@code sources} offered in {@code surface}, as registry tools. A failing source offers none. */
    public static List<Tool> fromSources(List<ToolSource> sources, ToolSurface surface) {
        List<Tool> out = new ArrayList<>();
        for (ToolSource src : sources) {
            try {
                for (ToolSource.SourcedTool t : src.tools()) {
                    if (t.surfaces().contains(surface)) {
                        out.add(new Tool(t.spec(), t.handler()::call, t.access(), t.surfaces(),
                                t.handler() instanceof ToolPreview p ? p : null));
                    }
                }
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "a tool source failed: its tools are not offered", e);
            }
        }
        return out;
    }

    private final Map<String, Tool> tools = new TreeMap<>();
    private final boolean enabled;
    private final boolean internet;
    private final DoubleSupplier clock;
    private final List<Map<String, Object>> schemas;
    private final List<Tool> all;
    private final ToolSurface surface;
    private final boolean approvals;
    private static final ExecutorService RUNNER = Executors.newVirtualThreadPerTaskExecutor();

    public ToolRegistry(List<Tool> tools, boolean enabled, boolean internet, DoubleSupplier monotonicSeconds) {
        this(tools, enabled, internet, monotonicSeconds, ToolSurface.CONVERSATION);
    }

    /** The same, offering the tools of {@code surface} only (the others are kept for {@link #forTasks(List)}). */
    public ToolRegistry(List<Tool> tools, boolean enabled, boolean internet, DoubleSupplier monotonicSeconds,
                        ToolSurface surface) {
        this(tools, enabled, internet, monotonicSeconds, surface, false);
    }

    /**
     * The same; {@code approvals}: the owner's approval is asked for each call of a tool that writes outside this
     * computer (a background task's registry), so such tools may be offered in {@link ToolSurface#TASK}.
     */
    public ToolRegistry(List<Tool> tools, boolean enabled, boolean internet, DoubleSupplier monotonicSeconds,
                        ToolSurface surface, boolean approvals) {
        this.surface = surface;
        this.approvals = approvals;
        for (Tool t : tools) {
            String refused = ToolPolicy.refusal(t.spec().name(), t.access());
            if (refused != null) {
                log.severe("tool refused by the tool filter: " + refused);
                continue;
            }
            this.tools.put(t.spec().name(), t);
        }
        this.all = List.copyOf(this.tools.values());
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

    /**
     * A background task's registry without approvals: the tools offered in tasks that do not write outside this
     * computer, with the same switches, and {@code extra} (the task's own, such as its final answer).
     */
    public ToolRegistry forTasks(List<Tool> extra) {
        return forTasks(extra, false);
    }

    /**
     * A background task's registry: the tools offered in tasks, with the same switches, and {@code extra}; those that
     * write outside this computer only when {@code approvals} (each call then waits for the owner's approval).
     */
    public ToolRegistry forTasks(List<Tool> extra, boolean approvals) {
        List<Tool> kept = new ArrayList<>();
        for (Tool t : all) {
            if (t.offeredIn(ToolSurface.TASK) && (approvals || t.access() != ToolAccess.EXTERNAL_WRITE)) {
                kept.add(t);
            }
        }
        kept.addAll(extra);
        return new ToolRegistry(kept, enabled, internet, clock, ToolSurface.TASK, approvals);
    }

    /** Whether the owner's approval is asked for each call of a tool that writes outside this computer. */
    public boolean approvals() {
        return approvals;
    }

    /** Whether the tools are switched on at all (the {@code tools} setting). */
    public boolean enabled() {
        return enabled;
    }

    /** Whether online tools are offered (the {@code internet} setting). */
    public boolean internet() {
        return internet;
    }

    public boolean isOffered(Tool t) {
        return enabled && (internet || !t.spec().online()) && t.offeredIn(surface)
                && ToolPolicy.offerable(t.access(), surface, approvals);
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
