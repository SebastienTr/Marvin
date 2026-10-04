// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.in.TaskControl;
import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.port.out.TaskListener;
import marvin.host.application.conversation.port.out.TaskStore;
import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.ReasoningSettings;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.VoiceSettings.VoiceConfig;
import marvin.host.domain.conversation.task.AgentPrompt;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskAnnouncement;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskSpent;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.task.TaskStep;
import marvin.host.domain.conversation.task.TaskText;
import marvin.host.domain.conversation.tool.TaskTools;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * Background tasks (docs/intelligence.md, "Thinking and background tasks"): started by the conversation
 * ({@code start_task}, or "do it in the background"), or from the app; run one at a time by {@link TaskRunner} with the
 * conversation's model thinking hard and the conversation's tools; kept with every step ({@link TaskStore}); published
 * as they change ({@link TaskListener}); and told to the owner when they are done ({@link TaskAnnouncement}): said at
 * once when they are there and not talking with Marvin, else mentioned in the next answer.
 *
 * <p>After a restart, a task that was queued or running is {@code interrupted}: its steps so far stay readable.
 */
public final class TaskService implements TaskControl {
    private static final Logger log = Logger.getLogger("marvin.tasks");
    /** Tasks waiting or running at most: more are refused (the owner is told why). */
    public static final int MAX_OPEN = 4;
    /** A finished task older than this is not mentioned any more (the app still shows it). */
    public static final double MENTION_S = 12 * 3600;
    /** How long Ollama may stay silent in a task's request (a 27B model reading a long prompt, then thinking). */
    static final double REQUEST_TIMEOUT_S = 240;
    static final String INTERRUPTED = "Marvin's host stopped while it ran";

    /** What the conversation gives the tasks. */
    public interface Host {
        /** The voice's configuration (the model, its server, the tools' switches, the languages). */
        VoiceConfig config();

        /** The conversation's tools, switched on or off by the settings (a task keeps those it may use). */
        ToolRegistry tools();

        /** What memory knows of the owner (the profile), empty when nothing. */
        String profile();

        boolean voiceOn();

        /** Someone is talking with Marvin now. */
        boolean conversing();

        /** Says {@code text} on Marvin's own initiative now; false when it cannot (the voice off, a conversation). */
        boolean speak(String text, String language);

        /** The presence sensor's word: someone is there ({@code null}: no sensor says). */
        Boolean present();

        /** Seconds since the last conversation ({@link Double#POSITIVE_INFINITY}: none since the start). */
        double sinceTalkS();

        /** Quiet hours: Marvin says nothing on its own. */
        boolean quiet();
    }

    private final TaskStore store;
    private final LanguageModel model;
    private final ModelLane lane;
    private final VoiceSettingsStore settings;
    private final Clocks clocks;
    private final LocalDays days;
    private final TaskRunner runner;
    private final List<TaskListener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<>();
    private final Map<String, Task> open = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Object announcing = new Object();
    private volatile Host host;
    private volatile TaskBudget budget = TaskBudget.DEFAULT;
    private volatile boolean closed;
    private final Thread worker;

    public TaskService(TaskStore store, LanguageModel model, ModelLane lane, VoiceSettingsStore settings, Clocks clocks,
                       LocalDays days) {
        this.store = store;
        this.model = model;
        this.lane = lane;
        this.settings = settings;
        this.clocks = clocks;
        this.days = days;
        this.runner = new TaskRunner(model, lane, clocks::monotonicSeconds, clocks::wallSeconds);
        try {
            int n = store.interruptUnfinished(clocks.wallSeconds(), INTERRUPTED);
            if (n > 0) {
                log.info(n + " task(s) interrupted by the last stop");
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not mark the tasks the last stop interrupted", e);
        }
        this.worker = Thread.ofVirtual().name("marvin-tasks").start(this::work);
    }

    /** The conversation, which gives the model, the tools and the voice. Set before the first task. */
    public void setHost(Host h) {
        host = h;
    }

    public void addListener(TaskListener l) {
        listeners.add(l);
    }

    /** The budget of new tasks. */
    public void setBudget(TaskBudget b) {
        budget = b;
    }

    public ModelLane lane() {
        return lane;
    }

    /** Stops the worker; a running task ends as cancelled (its steps are kept). */
    public void close() {
        closed = true;
        open.keySet().forEach(cancelled::add);
        worker.interrupt();
        try {
            worker.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public ReasoningSettings reasoning() {
        try {
            return ReasoningSettings.of(settings.load());
        } catch (RuntimeException e) {
            return ReasoningSettings.DEFAULT;
        }
    }

    // ------------------------------------------------------------------ starting and cancelling

    @Override
    public Task start(String goal, String language) {
        return start(goal, language, Task.APP);
    }

    /**
     * Starts a task (from the conversation: {@code voice} or {@code typed}; from the app: {@code app}). The same goal
     * still waiting or running is that task, not a second one.
     *
     * @throws IllegalArgumentException an empty or too long goal
     * @throws Refused                  tasks are switched off, or {@link #MAX_OPEN} wait already
     */
    public Task start(String goal, String language, String origin) {
        String g = goal == null ? "" : goal.strip().replaceAll("\\s+", " ");
        if (g.isEmpty()) {
            throw new IllegalArgumentException("say what the task is");
        }
        if (g.codePointCount(0, g.length()) > TaskText.MAX_GOAL) {
            throw new IllegalArgumentException("a task is at most " + TaskText.MAX_GOAL + " characters");
        }
        if (!reasoning().tasksOn()) {
            throw new Refused("Background tasks are switched off (Marvin > Voice & models)");
        }
        if (host == null || closed) {
            throw new Refused("Background tasks are not available on this host");
        }
        synchronized (open) {
            for (Task t : open.values()) {
                if (normal(t.goal()).equals(normal(g))) {
                    return t;
                }
            }
            if (open.size() >= MAX_OPEN) {
                throw new Refused(MAX_OPEN + " tasks are already waiting or running: wait for one to finish, or cancel one");
            }
            String lang = language == null || language.isBlank() ? "en" : language;
            Task t = Task.queued(UUID.randomUUID().toString(), g, lang, origin, clocks.wallSeconds(), budget);
            open.put(t.id(), t);
            save(t);
            queue.add(t.id());
            log.info("task queued: " + t.title());
            return t;
        }
    }

    private static String normal(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s.?!]+", " ").strip();
    }

    @Override
    public Optional<Task> cancel(String id) {
        Task t = open.get(id);
        if (t == null) {
            return store.get(id);
        }
        cancelled.add(id);
        if (t.status() == TaskStatus.QUEUED && queue.remove(id)) {
            Task c = t.finished(TaskStatus.CANCELLED, clocks.wallSeconds(), "", "", "Cancelled before it started", null);
            open.remove(id);
            save(c);
            return Optional.of(c);
        }
        return Optional.of(open.getOrDefault(id, t));
    }

    // ------------------------------------------------------------------ the worker

    private void work() {
        while (!closed) {
            String id;
            try {
                id = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (id == null) {
                continue;
            }
            Task t = open.get(id);
            if (t == null) {
                continue;
            }
            try {
                run(t);
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "a task failed", e);
                Task f = open.getOrDefault(id, t).finished(TaskStatus.FAILED, clocks.wallSeconds(), "", "",
                        "Something went wrong: " + e.getMessage(), null);
                open.remove(id);
                save(f);
            } finally {
                cancelled.remove(id);
            }
        }
    }

    private void run(Task queued) {
        Host h = host;
        VoiceConfig c = h.config() != null ? h.config() : VoiceSettings.config(settings.load());
        ReasoningSettings r = reasoning();
        LanguageModel.Thinking thinking = new LanguageModel.Thinking(r.tasks(), LanguageModel.Thinking.TASK_TOKENS);
        String think = String.valueOf(r.tasks().thinkValue(model.describe(c.ollamaHost(), c.llmModel()).thinking()));
        Task t = queued.running(clocks.wallSeconds(), c.llmModel(), r.tasks().id(), think);
        open.put(t.id(), t);
        save(t);
        log.info("task running: " + t.title() + " (" + c.llmModel() + ", think " + think + ")");
        ToolRegistry tools = h.tools() != null ? h.tools().forTasks(List.of())
                : new ToolRegistry(List.of(), false, false, clocks::monotonicSeconds);
        List<marvin.host.domain.conversation.tool.ToolSpec> specs = new ArrayList<>();
        tools.offered().forEach(x -> specs.add(x.spec()));
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli((long) (clocks.wallSeconds() * 1000)), days.zone());
        List<ChatMessage> messages = List.of(
                ChatMessage.system(AgentPrompt.system(specs, t.budget().steps(), t.language(), profile(h))),
                ChatMessage.user(AgentPrompt.task(t.goal(), now, t.language())));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("language", t.language());
        TaskRunner.Setup setup = new TaskRunner.Setup(c.ollamaHost(), c.llmModel(), tools, thinking, messages, t.budget(), ctx,
                REQUEST_TIMEOUT_S);
        String id = t.id();
        TaskRunner.Outcome out = runner.run(setup, new TaskRunner.Progress() {
            @Override
            public void step(TaskStep step) {
                try {
                    store.addStep(id, step);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "could not save a task's step", e);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("step", step.toMap());
                publish("task_step", m);
            }

            @Override
            public void activity(String doing, TaskSpent spent) {
                Task cur = open.get(id);
                if (cur != null && cur.status() == TaskStatus.RUNNING) {
                    Task next = cur.progress(doing, spent);
                    open.put(id, next);
                    publish("task", next.toSummaryMap());
                }
            }
        }, () -> cancelled.contains(id) || closed);
        Task cur = open.getOrDefault(id, t);
        TaskStatus status = closed && out.status() == TaskStatus.CANCELLED ? TaskStatus.INTERRUPTED : out.status();
        String error = status == TaskStatus.INTERRUPTED ? INTERRUPTED : out.error();
        Task done = cur.finished(status, clocks.wallSeconds(), out.summary(), out.result(), error, out.spent());
        open.remove(id);
        save(done);
        log.info("task " + status.id() + ": " + done.title() + " (" + out.spent().steps() + " steps, "
                + out.spent().toolCalls() + " tools, " + out.spent().tokens() + " tokens, "
                + Math.round(out.spent().seconds()) + " s)" + (error.isEmpty() ? "" : ": " + error));
        tryAnnounce();
    }

    private static String profile(Host h) {
        try {
            String p = h.profile();
            return p == null ? "" : p;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void save(Task t) {
        try {
            store.save(t);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not save a task", e);
        }
        publish("task", t.toSummaryMap());
    }

    private void publish(String kind, Map<String, Object> payload) {
        for (TaskListener l : listeners) {
            try {
                l.onTask(kind, payload);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "a task listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ telling the owner

    /**
     * Tells the owner of finished tasks when it is a good moment (after a task, when a conversation ends, when someone
     * arrives): said aloud when they are there and not talking with Marvin; otherwise left for the next answer.
     */
    public void tryAnnounce() {
        Host h = host;
        if (h == null || closed) {
            return;
        }
        synchronized (announcing) {
            for (Task t : toMention()) {
                TaskAnnouncement.Decision d = TaskAnnouncement.decide(h.voiceOn(), h.conversing(), h.quiet(), h.present(),
                        h.sinceTalkS());
                if (d != TaskAnnouncement.Decision.SPEAK_NOW) {
                    return;
                }
                String text = t.status() == TaskStatus.DONE
                        ? Persona.phrase("task_done", t.language(), Map.of("title", t.title(), "summary", t.summary()))
                        : Persona.phrase("task_failed", t.language(), Map.of("title", t.title()));
                if (!h.speak(text, t.language())) {
                    return;
                }
                save(t.announced("spoken", clocks.wallSeconds()));
            }
        }
    }

    /** Finished tasks the owner has not heard of yet (the last {@link #MENTION_S}), oldest first. */
    public List<Task> toMention() {
        double now = clocks.wallSeconds();
        List<Task> out = new ArrayList<>();
        try {
            for (Task t : store.recent(20)) {
                if (t.toTell() && t.finishedAt() != null && now - t.finishedAt() < MENTION_S) {
                    out.add(0, t);
                }
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the tasks", e);
        }
        return out;
    }

    /** These tasks were mentioned in an answer. */
    public void mentioned(List<Task> tasks) {
        synchronized (announcing) {
            for (Task t : tasks) {
                store.get(t.id()).filter(Task::toTell).ifPresent(x -> save(x.announced("mentioned", clocks.wallSeconds())));
            }
        }
    }

    // ------------------------------------------------------------------ for the conversation's tools

    /**
     * {@code task_result}: the running tasks with what they are doing, and the latest finished ones with their summary and
     * the start of their result; {@code query} keeps those whose goal has its words.
     */
    public Map<String, Object> report(String query) {
        List<String> words = query == null ? List.of() : List.of(normal(query).split(" ")).stream()
                .filter(w -> w.length() > 2).toList();
        List<Map<String, Object>> running = new ArrayList<>();
        List<Map<String, Object>> finished = new ArrayList<>();
        double now = clocks.wallSeconds();
        for (Task t : tasks(20)) {
            String g = normal(t.goal());
            if (!words.isEmpty() && words.stream().noneMatch(g::contains)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", t.title());
            m.put("status", t.status().id());
            if (!t.status().finished()) {
                m.put("doing", t.activity());
                m.put("steps", t.spent().steps() + " of " + t.budget().steps());
                running.add(m);
            } else if (finished.size() < 3) {
                m.put("finished", ago(now - (t.finishedAt() == null ? now : t.finishedAt())));
                if (!t.summary().isEmpty()) {
                    m.put("summary", t.summary());
                }
                if (!t.result().isEmpty()) {
                    m.put("result", TaskText.cut(t.result(), 1500));
                }
                if (!t.error().isEmpty()) {
                    m.put("error", t.error());
                }
                finished.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", running);
        out.put("finished", finished);
        if (running.isEmpty() && finished.isEmpty()) {
            out.put("note", words.isEmpty() ? "no background task yet" : "no task matches");
        }
        return out;
    }

    static String ago(double s) {
        long m = Math.round(s / 60);
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : Math.round(m / 60.0) + " h ago";
    }

    /** The conversation's two tools: start a task (the turn ends with "I'm on it"), and ask about tasks. */
    public List<ToolRegistry.Tool> conversationTools() {
        return List.of(new ToolRegistry.Tool(TaskTools.start(), (args, ctx) -> {
                    String lang = ctx.get("language") instanceof String l && !l.isEmpty() ? l : "en";
                    String origin = "typed".equals(ctx.get("source")) ? Task.TYPED : Task.VOICE;
                    Task t;
                    try {
                        t = start(String.valueOf(args.getOrDefault("goal", "")), lang, origin);
                    } catch (IllegalArgumentException | Refused e) {
                        throw new ToolError(e.getMessage());
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("started", true);
                    m.put("task", t.title());
                    m.put(marvin.host.domain.conversation.tool.ToolResult.FINAL_SAY, Persona.phrase("task_started", lang));
                    return m;
                }, marvin.host.domain.conversation.tool.ToolAccess.LOCAL_WRITE,
                        Set.of(marvin.host.domain.conversation.tool.ToolSurface.CONVERSATION)),
                new ToolRegistry.Tool(TaskTools.result(), (args, ctx) ->
                        report(args.get("query") instanceof String q ? q : ""),
                        marvin.host.domain.conversation.tool.ToolAccess.READ,
                        Set.of(marvin.host.domain.conversation.tool.ToolSurface.CONVERSATION)));
    }

    // ------------------------------------------------------------------ the app

    @Override
    public List<Task> tasks(int limit) {
        List<Task> out = new ArrayList<>();
        List<Task> stored;
        try {
            stored = store.recent(Math.max(1, limit));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the tasks", e);
            stored = List.of();
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Task t : stored) {
            Task live = open.get(t.id());
            out.add(live != null ? live : t);
            seen.add(t.id());
        }
        for (Task t : open.values()) {
            if (!seen.contains(t.id())) {
                out.add(t);
            }
        }
        out.sort((a, b) -> Double.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    @Override
    public Optional<Task> task(String id) {
        Task live = open.get(id);
        return live != null ? Optional.of(live) : store.get(id);
    }

    @Override
    public List<TaskStep> steps(String id) {
        return store.steps(id);
    }

    @Override
    public Map<String, Object> settings() {
        Map<String, Object> s = settings.load();
        VoiceConfig c = VoiceSettings.config(s);
        ReasoningSettings r = ReasoningSettings.of(s);
        LanguageModel.ModelInfo info = model.describe(c.ollamaHost(), c.llmModel());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", r.toMap());
        m.put("levels", marvin.host.domain.conversation.Reasoning.NAMES);
        Map<String, Object> mi = new LinkedHashMap<>();
        mi.put("name", c.llmModel());
        mi.put("size_bytes", info.sizeBytes() < 0 ? null : info.sizeBytes());
        mi.put("capabilities", info.capabilities().stream().sorted().toList());
        mi.put("thinking", info.thinking().toMap());
        mi.put("think_chat", r.chat().thinkValue(info.thinking()));
        mi.put("think_tasks", r.tasks().thinkValue(info.thinking()));
        m.put("model", mi);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("model", ReasoningSettings.RECOMMENDED_MODEL);
        rec.put("current", c.llmModel().equals(ReasoningSettings.RECOMMENDED_MODEL));
        boolean installed;
        try {
            List<String> models = model.models(c.ollamaHost());
            installed = models.contains(ReasoningSettings.RECOMMENDED_MODEL);
        } catch (LanguageModel.Unavailable e) {
            installed = false;
        }
        rec.put("installed", installed);
        m.put("recommended", rec);
        m.put("budget", budget.toMap());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("parallel", lane.parallel());
        p.put("yields_to_voice", lane.yieldsToVoice());
        m.put("server", p);
        return m;
    }

    @Override
    public Map<String, Object> updateSettings(Map<String, ?> update) {
        Map<String, Object> values = ReasoningSettings.validate(update);
        if (!values.isEmpty()) {
            settings.save(values);
            log.info("reasoning and tasks settings saved: " + values);
        }
        return settings();
    }
}
