// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.MemoryListener;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.port.out.MemoryStateStore;
import marvin.host.application.memory.port.out.ModelWarmUp;
import marvin.host.application.memory.port.out.VoiceActivity;
import marvin.host.domain.memory.Batches;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * The memory worker (docs/design.md 5.2). An <b>idle pass</b> (nobody has talked to Marvin for
 * {@code idle_minutes} and the voice is not busy) extracts facts from the new events; a <b>nightly pass</b> (from
 * {@code night_hour} local time, at the first idle moment after it) does that, then the episodes, the profile,
 * decay and retention. Both give the model back to the voice as soon as it becomes busy: the model call in flight
 * is abandoned, and the batch or step it belonged to is done again by the next pass. After a pass that used the
 * voice's own model (its cached prompt is gone) or changed the profile, the voice's model is warmed up again.
 */
public final class MemoryWorker implements ConsolidateMemory, AutoCloseable {
    private static final Logger log = Logger.getLogger("marvin.memory");

    private final Consolidator consolidator;
    private final NightlyPass nightly;
    private final EventLog events;
    private final MemorySettingsService settings;
    private final VoiceActivity voice;
    private final ModelWarmUp warmUp;
    private final MemoryStateStore state;
    private final LocalDays days;
    private final Clocks clocks;
    private final MemoryConfig config;
    private final List<MemoryListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("memory-worker").factory());
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("memory-worker-tick").factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean closing;
    private volatile Pass pass;
    private volatile String step = "";
    private volatile Report last;
    /** After failed passes, scheduled passes wait (1 min, doubling to 30 min): Ollama may simply not be running. */
    private volatile double retryAt;
    private volatile int failures;

    public MemoryWorker(Consolidator consolidator, NightlyPass nightly, EventLog events, MemorySettingsService settings,
                        VoiceActivity voice, ModelWarmUp warmUp, MemoryStateStore state, LocalDays days, Clocks clocks,
                        MemoryConfig config) {
        this.consolidator = consolidator;
        this.nightly = nightly;
        this.events = events;
        this.settings = settings;
        this.voice = voice;
        this.warmUp = warmUp;
        this.state = state;
        this.days = days;
        this.clocks = clocks;
        this.config = config;
        this.last = lastReport();
    }

    public void addListener(MemoryListener l) {
        listeners.add(l);
    }

    /** Checks every {@code tickSeconds} whether a pass is due. */
    public void start() {
        long ms = (long) (config.tickSeconds() * 1000);
        ticker.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "memory worker tick failed", e);
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** Starts a pass when one is due and Marvin is idle; returns it, or {@code null}. */
    CompletableFuture<Report> tick() {
        if (closing || running.get() || !settings.settings().worker() || !idle()) {
            return null;
        }
        double now = clocks.wallSeconds();
        if (now < retryAt) {
            return null;
        }
        if (now >= nextNight()) {
            return submit(Pass.NIGHTLY);
        }
        if (events.unconsolidatedCount() > 0) {
            return submit(Pass.IDLE);
        }
        return null;
    }

    /** Nobody is talking to Marvin, and nobody has for {@code idle_minutes}. */
    boolean idle() {
        if (voice.busy()) {
            return false;
        }
        double quiet = clocks.wallSeconds() - voice.lastActivity();
        return quiet >= settings.settings().idleMinutes() * 60.0;
    }

    /** When the next nightly pass is due: the first {@code night_hour} after the last one (Unix seconds). */
    double nextNight() {
        double lastNight = number(state.get("worker").get("last_night_at"));
        ZonedDateTime base = EventFeeds.instant(Math.max(lastNight, 0)).atZone(days.zone());
        int hour = settings.settings().nightHour();
        LocalDate d = base.toLocalDate();
        ZonedDateTime due = d.atTime(hour, 0).atZone(days.zone());
        if (!due.toInstant().isAfter(base.toInstant())) {
            due = d.plusDays(1).atTime(hour, 0).atZone(days.zone());
        }
        if (lastNight <= 0) {
            // never ran: the most recent night hour (so a new install runs its first night at the first idle moment)
            ZonedDateTime now = EventFeeds.instant(clocks.wallSeconds()).atZone(days.zone());
            due = now.toLocalDate().atTime(hour, 0).atZone(days.zone());
            if (due.isAfter(now)) {
                due = due.minusDays(1);
            }
        }
        return due.toEpochSecond();
    }

    @Override
    public CompletableFuture<Report> consolidateNow(Pass p) {
        CompletableFuture<Report> f = submit(p);
        return f != null ? f : CompletableFuture.completedFuture(new Report(p, "skipped", clocks.wallSeconds(), 0, "",
                Map.of(), List.of(), "a pass is already running", ""));
    }

    private CompletableFuture<Report> submit(Pass p) {
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        pass = p;
        publishStatus();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return run(p);
            } finally {
                pass = null;
                step = "";
                running.set(false);
                publishStatus();
            }
        }, runner);
    }

    // ------------------------------------------------------------------ a pass

    private final class Run {
        final double started = clocks.wallSeconds();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<Map<String, Object>> steps = new ArrayList<>();
        final BooleanSupplier cancelled = () -> closing || voice.busy();
        boolean usedModel;
        boolean profileChanged;
        double stepStart;

        void begin(String name) {
            if (cancelled.getAsBoolean()) {
                throw new MemoryModel.Cancelled();
            }
            step = name;
            stepStart = clocks.wallSeconds();
            publishStatus();
        }

        void end(String name) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("step", name);
            s.put("seconds", Math.round((clocks.wallSeconds() - stepStart) * 1000) / 1000.0);
            steps.add(s);
        }
    }

    Report run(Pass p) {
        Run r = new Run();
        MemoryModel.Target target = p == Pass.NIGHTLY ? settings.nightModel() : settings.idleModel();
        String outcome = "done";
        String error = "";
        String fix = "";
        try {
            extract(r, target);
            if (p == Pass.NIGHTLY) {
                r.begin("embeddings");
                r.counts.put("embedded", nightly.embedMissing(r.cancelled));
                r.end("embeddings");
                r.begin("days");
                int d = nightly.dayEpisodes(target, r.cancelled);
                r.usedModel |= d > 0;
                r.counts.put("days", d);
                r.end("days");
                r.begin("roll-ups");
                int w = nightly.rollUps(target, r.cancelled);
                r.usedModel |= w > 0;
                r.counts.put("roll_ups", w);
                r.end("roll-ups");
                r.begin("profile");
                r.profileChanged = nightly.profile(target, r.cancelled);
                r.usedModel |= r.profileChanged;
                r.counts.put("profile", r.profileChanged ? 1 : 0);
                r.end("profile");
                r.begin("decay");
                r.counts.put("archived", nightly.decay());
                r.end("decay");
                r.begin("retention");
                r.counts.put("retention_deleted", nightly.retention());
                r.counts.put("orphans_deleted", nightly.orphans());
                r.end("retention");
            }
        } catch (MemoryModel.Cancelled e) {
            outcome = "yielded";
        } catch (MemoryModel.Unavailable e) {
            outcome = "failed";
            error = e.getMessage();
            fix = e.fix();
        } catch (Embedder.Unavailable e) {
            outcome = "failed";
            error = e.getMessage();
            fix = e.fix();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "memory pass failed", e);
            outcome = "failed";
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        double now = clocks.wallSeconds();
        Report report = new Report(p, outcome, r.started, Math.round((now - r.started) * 1000) / 1000.0, target.model(),
                r.counts, r.steps, error, fix);
        save(report);
        last = report;
        if ("failed".equals(outcome)) {
            failures++;
            retryAt = now + Math.min(1800, 60 * Math.pow(2, failures - 1));
        } else {
            failures = 0;
            retryAt = 0;
        }
        if ("failed".equals(outcome) && failures > 1) {
            log.fine("memory pass failed again: " + error);
        } else if (!r.counts.isEmpty() || !"done".equals(outcome)) {
            log.info("memory: " + p.name().toLowerCase(java.util.Locale.ROOT) + " pass " + outcome + " in " + report.seconds()
                    + " s " + r.counts + (error.isEmpty() ? "" : ": " + error + (fix.isEmpty() ? "" : ". " + fix)));
        }
        if ((r.usedModel && settings.isVoiceModel(target)) || r.profileChanged) {
            try {
                warmUp.rewarm();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "warming the voice model up failed", e);
            }
        }
        for (MemoryListener l : listeners) {
            try {
                l.onMemory("changed", Map.of("pass", p.name().toLowerCase(java.util.Locale.ROOT), "outcome", outcome));
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "memory listener failed", e);
            }
        }
        return report;
    }

    /** Extraction and reconciliation of every new event, batch by batch (docs/design.md 5.2, steps 1 to 3). */
    private void extract(Run r, MemoryModel.Target target) {
        r.begin("extract");
        while (true) {
            List<MemoryEvent> page = events.unconsolidated(config.pageSize());
            if (page.isEmpty()) {
                break;
            }
            r.counts.merge("events", page.size(), Integer::sum);
            List<Long> plain = page.stream().filter(e -> !MemorySources.extractable(e)).map(MemoryEvent::id).toList();
            if (!plain.isEmpty()) {
                events.markConsolidated(plain, EventFeeds.instant(clocks.wallSeconds()));
            }
            List<List<MemoryEvent>> batches = Batches.conversations(page, config.batchGap(), config.maxBatchEvents());
            if (page.size() == config.pageSize() && batches.size() > 1) {
                batches = batches.subList(0, batches.size() - 1);   // the last may go on in the next page
            }
            for (List<MemoryEvent> batch : batches) {
                if (r.cancelled.getAsBoolean()) {
                    throw new MemoryModel.Cancelled();
                }
                r.usedModel = true;
                Consolidator.Result res = consolidator.process(batch, target, r.cancelled);
                res.into(r.counts);
                r.counts.merge("model_calls", res.modelCalls(), Integer::sum);
            }
            if (batches.isEmpty() && plain.isEmpty()) {
                break;
            }
        }
        r.end("extract");
    }

    // ------------------------------------------------------------------ state

    private void save(Report report) {
        Map<String, Object> w = new LinkedHashMap<>(state.get("worker"));
        double at = report.startedAt();
        if (report.pass() == Pass.IDLE || "done".equals(report.outcome())) {
            w.put("last_idle_at", at);
        }
        if (report.pass() == Pass.NIGHTLY && "done".equals(report.outcome())) {
            w.put("last_night_at", at);
        }
        w.put("last", toMap(report));
        state.put("worker", w);
    }

    static Map<String, Object> toMap(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pass", r.pass().name().toLowerCase(java.util.Locale.ROOT));
        m.put("outcome", r.outcome());
        m.put("started_at", r.startedAt());
        m.put("seconds", r.seconds());
        m.put("model", r.model());
        m.put("counts", r.counts());
        m.put("steps", r.steps());
        m.put("error", r.error());
        m.put("fix", r.fix());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Report lastReport() {
        Object o = state.get("worker").get("last");
        if (!(o instanceof Map<?, ?> m)) {
            return null;
        }
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            if (m.get("counts") instanceof Map<?, ?> c) {
                c.forEach((k, v) -> counts.put(String.valueOf(k), v instanceof Number n ? n.intValue() : 0));
            }
            return new Report(Pass.valueOf(String.valueOf(m.get("pass")).toUpperCase(java.util.Locale.ROOT)),
                    String.valueOf(m.get("outcome")), number(m.get("started_at")), number(m.get("seconds")),
                    String.valueOf(m.get("model")), counts,
                    m.get("steps") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of(),
                    String.valueOf(m.get("error")), String.valueOf(m.get("fix")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    @Override
    public Status status() {
        Map<String, Object> w = state.get("worker");
        String s = !settings.settings().worker() ? "off" : running.get() ? "running" : "waiting";
        return new Status(s, pass, step, events.unconsolidatedCount(), number(w.get("last_idle_at")),
                number(w.get("last_night_at")), nextNight(), last);
    }

    private void publishStatus() {
        if (listeners.isEmpty()) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", running.get() ? "running" : "waiting");
        m.put("pass", pass == null ? null : pass.name().toLowerCase(java.util.Locale.ROOT));
        m.put("step", step);
        for (MemoryListener l : listeners) {
            try {
                l.onMemory("worker", m);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "memory listener failed", e);
            }
        }
    }

    @Override
    public void close() {
        closing = true;
        ticker.shutdownNow();
        runner.shutdown();
        try {
            runner.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Instant nowInstant() {
        return EventFeeds.instant(clocks.wallSeconds());
    }
}
