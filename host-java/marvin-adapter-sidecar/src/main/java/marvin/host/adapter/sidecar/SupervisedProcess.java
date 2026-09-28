// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A child process the host keeps running (docs/design.md 4.2): started, watched, restarted with an
 * exponential backoff (1 s, doubling, at most 60 s; back to 1 s after a minute of good health), stopped
 * with {@code SIGTERM} then {@code SIGKILL} after 5 s. Its output lines go to the host's log under
 * {@code marvin.sidecar.<name>} at the level the line says (Python's logging format), so warnings and
 * errors reach the app's Log panel; the last lines are kept for the error shown when it keeps failing.
 * A JVM shutdown hook makes sure no child outlives the host.
 */
public final class SupervisedProcess {
    private static final Logger log = LoggerFactory.getLogger(SupervisedProcess.class);
    static final long FIRST_BACKOFF_MS = 1000;
    static final long MAX_BACKOFF_MS = 60_000;
    static final long HEALTHY_AFTER_MS = 60_000;
    private static final Pattern PY_LOG = Pattern.compile("^\\S+ \\S+ (DEBUG|INFO|WARNING|ERROR|CRITICAL) (\\S+?): (.*)$");

    /** What the process is doing. */
    public enum State { STOPPED, STARTING, RUNNING, BACKING_OFF }

    private final String name;
    private final Supplier<ProcessBuilder> command;
    private final Consumer<String> stdoutLine;
    private volatile java.util.function.Predicate<String> reportedElsewhere = text -> false;
    private final Logger out;
    private final Deque<String> lastLines = new ArrayDeque<>();
    private final Thread hook;
    private volatile boolean wanted;
    private volatile Process process;
    private volatile State state = State.STOPPED;
    private volatile int lastExit;
    private volatile long backoffMs = FIRST_BACKOFF_MS;
    private volatile int restarts;
    private Thread supervisor;
    private final List<Consumer<Process>> onStart = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Consumer<Integer>> onExit = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * @param stdoutLine each line on standard output (for a readiness line such as {@code READY port=N});
     *                   standard error lines are logs
     */
    public SupervisedProcess(String name, Supplier<ProcessBuilder> command, Consumer<String> stdoutLine) {
        this.name = name;
        this.command = command;
        this.stdoutLine = stdoutLine;
        this.out = LoggerFactory.getLogger("marvin.sidecar." + name);
        this.hook = new Thread(this::reap, "sidecar-" + name + "-reaper");
    }

    public String name() {
        return name;
    }

    public State state() {
        return state;
    }

    public int lastExit() {
        return lastExit;
    }

    public int restarts() {
        return restarts;
    }

    /** The last lines it wrote, oldest first. */
    public List<String> lastLines() {
        synchronized (lastLines) {
            return List.copyOf(lastLines);
        }
    }

    /** The running process's id, or -1. */
    public long pid() {
        Process p = process;
        return p != null && p.isAlive() ? p.pid() : -1;
    }

    public boolean alive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Warnings whose text this says the host reports itself are logged at info (no double line in the Log panel). */
    public void reportedElsewhere(java.util.function.Predicate<String> p) {
        reportedElsewhere = p;
    }

    /** Called with the process each time it starts. */
    public void onStart(Consumer<Process> fn) {
        onStart.add(fn);
    }

    /** Called with the exit code each time it stops. */
    public void onExit(Consumer<Integer> fn) {
        onExit.add(fn);
    }

    public synchronized void start() {
        if (wanted) {
            return;
        }
        wanted = true;
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // shutting down already, or registered
        }
        // platform threads: reading a child's pipes blocks in native code, which would pin (and could starve)
        // the few carriers of the virtual threads the web server runs on
        supervisor = Thread.ofPlatform().daemon().name("sidecar-" + name).start(this::supervise);
    }

    private void supervise() {
        while (wanted) {
            long started = System.currentTimeMillis();
            try {
                state = State.STARTING;
                ProcessBuilder pb = command.get();
                Process p = pb.start();
                process = p;
                log.info("{} sidecar started (pid {})", name, p.pid());
                onStart.forEach(fn -> fn.accept(p));
                Thread err = Thread.ofPlatform().daemon().name("sidecar-" + name + "-stderr")
                        .start(() -> pump(p.getErrorStream(), false));
                pump(p.getInputStream(), true);
                int code = p.waitFor();
                err.join(1000);
                lastExit = code;
                onExit.forEach(fn -> fn.accept(code));
                if (wanted) {
                    log.warn("the {} sidecar stopped (exit {}){}", name, code, lastLine());
                }
            } catch (IOException e) {
                lastExit = -1;
                remember("could not start: " + e.getMessage());
                log.warn("could not start the {} sidecar: {}", name, e.getMessage());
                onExit.forEach(fn -> fn.accept(-1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!wanted) {
                break;
            }
            if (System.currentTimeMillis() - started > HEALTHY_AFTER_MS) {
                backoffMs = FIRST_BACKOFF_MS;
            }
            state = State.BACKING_OFF;
            restarts++;
            log.info("restarting the {} sidecar in {} s", name, backoffMs / 1000.0);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
        }
        state = State.STOPPED;
    }

    private String lastLine() {
        List<String> l = lastLines();
        return l.isEmpty() ? "" : ": " + l.get(l.size() - 1);
    }

    private void remember(String line) {
        synchronized (lastLines) {
            lastLines.addLast(line);
            while (lastLines.size() > 20) {
                lastLines.removeFirst();
            }
        }
    }

    private void pump(InputStream in, boolean stdout) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null;) {
                if (line.isBlank()) {
                    continue;
                }
                if (stdout) {
                    state = state == State.STARTING ? State.RUNNING : state;
                    try {
                        stdoutLine.accept(line);
                    } catch (RuntimeException e) {
                        log.warn("{} sidecar: bad line {}", name, line, e);
                    }
                }
                logLine(line);
            }
        } catch (IOException e) {
            // the process ended
        }
    }

    private void logLine(String line) {
        remember(line);
        Matcher m = PY_LOG.matcher(line);
        if (!m.matches()) {
            out.debug("{}", line);
            return;
        }
        String text = m.group(3);
        switch (m.group(1)) {
            case "DEBUG" -> out.debug("{}", text);
            case "INFO" -> out.info("{}", text);
            case "WARNING" -> {
                if (reportedElsewhere.test(text)) {
                    out.info("{}", text);
                } else {
                    out.warn("{} sidecar: {}", name, text);
                }
            }
            default -> out.error("{} sidecar: {}", name, text);
        }
    }

    /** Marks it running (a readiness check passed). */
    public void markRunning() {
        state = State.RUNNING;
    }

    public void stop() {
        Thread s;
        synchronized (this) {
            wanted = false;
            s = supervisor;
            supervisor = null;
        }
        kill();
        if (s != null) {
            s.interrupt();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // shutting down
        }
        state = State.STOPPED;
    }

    /** The JVM is quitting: no restart, the child goes too. */
    private void reap() {
        wanted = false;
        kill();
    }

    /** SIGTERM, then SIGKILL after 5 s. */
    private void kill() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("the {} sidecar did not stop in 5 s: killing it", name);
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }
}
