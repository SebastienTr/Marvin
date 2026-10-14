// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.conversation.port.out.JsonFetcher;
import marvin.host.application.conversation.port.out.LatencyLog;
import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.port.out.MemoryContext;
import marvin.host.application.conversation.port.out.ToolSource;
import marvin.host.application.conversation.port.out.VoiceListener;
import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.task.ModelLane;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.system.port.out.Tracing;
import marvin.host.application.vision.port.in.Camera;
import marvin.host.domain.conversation.AudioDeviceProfile;
import marvin.host.domain.conversation.ContextAssembler;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.ConversationMemory;
import marvin.host.domain.conversation.Greeting;
import marvin.host.domain.conversation.ImageAttachment;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.VoiceSettings.VoiceConfig;
import marvin.host.domain.conversation.VoiceSnapshot;
import marvin.host.domain.conversation.Voiceprint;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.robot.event.RoomState;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.shared.PyNumbers;

/**
 * Marvin's voice and conversation (the Python host's {@code VoiceController} and the conversation half of
 * {@code VoiceAssistant}), with the audio loop in the voice sidecar.
 *
 * <p>Life: {@link #start()} checks that the model server has the model (and says how to fix what is
 * missing), rehearses a first question so the model is loaded and the prompt cached, and opens a voice
 * session with the owner's settings ({@code voice.json}); the state is {@code on} once the voice listens.
 * Changing the settings restarts it; {@link #stop()} closes the session.
 *
 * <p>A turn: the voice hears a question ({@code Heard}); the answer is written here (persona, context from
 * the brain, history, the model streamed, tools) and streamed to the voice sentence by sentence; the voice
 * says it and reports when it is done ({@code ReplySpoken}) or interrupted. The transcript entries (heard,
 * reply with its latency breakdown and what the model was given, ignored, notes) are kept in the
 * conversation store and published live, with the voice's live signals.
 *
 * <p>One thought, one question: a {@code Heard} that {@code continues} earlier questions (the owner went on talking
 * after them) is answered alone. The questions it continues were cancelled by the voice: one cancelled before its
 * answer was heard ({@code merged}) leaves nothing (no reply entry, nothing in the history), one whose answer was cut
 * leaves what was said but leaves the history, and the heard entry of the joined question, written after them,
 * says which entries it {@code replaces} (the app shows one bubble) or {@code continues}.
 *
 * <p>An image the owner shows (docs/voice.md "Showing Marvin an image") waits in memory for the next question, typed
 * or spoken, and goes to the model with that question's message only: the history keeps the question's text with a
 * note, so later questions neither send the image again nor lose the prompt cache for it. The model that looks at it
 * is the vision model of the settings, or the voice's; one that cannot see images (Ollama's capabilities, asked once
 * per model) is refused when the image is attached. The conversation entry records what the image was (size, bytes,
 * SHA-256), never the image.
 *
 * <p>With a camera (docs/vision.md), the {@code look} tool is offered while the vision context says a camera can take a
 * picture, and the persona says Marvin can look; otherwise neither, so Marvin never claims a sight it does not have. A
 * look takes one picture, the model for images describes it, and the conversation's model reads the description.
 *
 * <p>Background tasks (docs/intelligence.md, "Thinking and background tasks"): with a {@link TaskService}, the model is
 * offered {@code start_task} (the turn then ends with "I'm on it") and {@code task_result}, and the persona says what tasks
 * can and cannot do. "Do it in the background" starts one without asking the model; words that usually announce work in
 * several steps add a hint to the question. A finished task the owner has not heard of is said aloud when they are there
 * and not talking with Marvin, or mentioned at the start of the next answer. The voice tells the {@link ModelLane} when a
 * conversation is going on, so a task's request gives way to it. Turns think as the owner's reasoning setting says (off
 * by default); their thinking is never spoken, and is kept in the reply for the inspector.
 *
 * <p>The work is shared with collaborators: {@link VoiceTranscript} (the entries and live signals),
 * {@link VoiceRouting} (models, tools, the system prompt, the warm-up), {@link VoiceTurns} (the questions and their
 * answers), {@link TurnContext} (memory and the "now" section in the question's message) and {@link VoiceProactive}
 * (Marvin speaking first). This class keeps the voice's life, its settings, its commands and its state.
 */
public final class VoiceService implements VoiceControl {
    private static final Logger log = Logger.getLogger("marvin.voice");

    public static final String OFF = "off";
    public static final String STARTING = "starting";
    public static final String ON = "on";
    public static final String STOPPING = "stopping";
    public static final String ERROR = "error";
    /** Live signals passed straight to the app. */
    public static final Set<String> LIVE_KINDS = Set.of("level", "utterance", "partial", "say", "hearing");
    /** The ears' diagnostics kept for the app's Senses screen (in memory only). */
    static final int HEARINGS = 20;
    private static final Set<String> RUNNING = Set.of("idle", "listening", "thinking", "speaking");
    /** How long the model may take for its first word, and between words. */
    static final double MODEL_TIMEOUT_S = 60.0;
    /** How long loading the voice (speech recognition, synthesis) may take. */
    static final double READY_TIMEOUT_S = 300.0;
    /** After an interruption, how long to wait for the voice to say what it said. */
    static final double SPOKEN_GRACE_S = 3.0;
    /**
     * The same, when a newer question waits for this turn: the voice reports what it said right after the
     * interruption, and only a question it dropped unanswered gets no report at all.
     */
    static final double SUPERSEDED_GRACE_S = 1.0;
    static final int HISTORY = 200;
    /** When the model server is missing, how long to wait for the voice to report an audio problem first. */
    static final double AUDIO_CHECK_S = 2.0;
    /**
     * The hard time budget of memory on the voice's path: a question waits at most this long for its facts (their
     * search starts on the speculative transcript, so it usually waits nothing), then goes without them.
     */
    static final double MEMORY_WAIT_S = 0.3;
    /** The history's token budget (docs/design.md 5.3). */
    static final int HISTORY_TOKENS = 4000;
    /** How long an image waits for the question it goes with, then it is dropped. */
    static final double IMAGE_WAIT_S = 600.0;

    final VoiceSidecar voice;
    private final VoiceSettingsStore settingsStore;
    final PresenceQuery presence;
    volatile Supplier<RoomState> room = () -> null;
    /** The people whose voice Marvin knows, sent with the voice's settings (voice calibration). */
    private volatile Supplier<List<Voiceprint>> voiceprints = List::of;
    /** Where the voice calibration's progress goes ({@link VoiceEnrollmentService}). */
    private volatile Consumer<VoiceSidecar.EnrollProgress> enrollment = p -> { };
    final Clocks clocks;
    final LocalDays days;
    private final Supplier<Optional<String>> audioRobot;
    private final ExecutorService control = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("marvin-voice-control").factory());
    final ExecutorService answers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("marvin-voice-answer-", 0).factory());

    final Object lock = new Object();
    private volatile Map<String, Object> lastLevel;
    final AtomicLong replyIds = new AtomicLong(1);
    /** The image waiting for the next question. */
    private final AtomicReference<PendingImage> pendingImage = new AtomicReference<>();
    final ConversationMemory memory;

    final VoiceTranscript transcript;
    final VoiceRouting routing;
    final TurnContext context;
    final VoiceTurns turns;
    final VoiceProactive proactive;

    volatile String state = OFF;
    private volatile String error = "";
    private volatile String fix = "";
    private volatile boolean muted;
    volatile VoiceSidecar.Status status;
    /** When {@link #status} came (monotonic seconds): {@code listen_s} counts down from there. */
    private volatile double statusAt;
    volatile boolean opened;
    /** The settings the voice session was last opened with. */
    private volatile VoiceSidecar.Settings openedWith;
    volatile boolean closed;
    volatile String language;
    volatile VoiceConfig config;
    volatile Tracing tracing = Tracing.NONE;
    /** Where each answered turn's latency line goes (C6). */
    volatile LatencyLog latencyLog = LatencyLog.NONE;
    volatile MemoryContext memoryContext = MemoryContext.NONE;
    /** The memory sections' shared budget (tokens), {@link ContextAssembler#MEMORY_BUDGET} unless the host sets it. */
    volatile int memoryBudget = ContextAssembler.MEMORY_BUDGET;
    /** Questions heard so far (a {@code forget} is confirmed in a later turn than the one that proposed it). */
    final AtomicLong turnSeq = new AtomicLong();
    /** A warm-up asked for while the owner was talking: it runs once the voice is idle again. */
    private final AtomicBoolean rewarmPending = new AtomicBoolean();
    /**
     * Moves on when the history must go (a guest came in, something was forgotten): a turn that began before does not
     * add its messages, which may hold what must not be sent again.
     */
    final AtomicLong historyEpoch = new AtomicLong();
    /** Background tasks, and the lane the voice shares the model with them on. */
    volatile TaskService tasks;
    private volatile ModelLane lane;
    private volatile boolean laneBusy;
    private volatile CompletableFuture<VoiceSidecar.Status> ready;
    private volatile long statusSeq;
    /** Each start, stop or restart supersedes the ones before it (they run one at a time, in order). */
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param platform   {@code darwin}, {@code linux} ... (the settings panel offers {@code say} voices on a Mac)
     * @param audioRobot the connected robot with a microphone and a speaker the robot route uses, if any
     */
    public VoiceService(VoiceSidecar voice, LanguageModel model, VoiceSettingsStore settingsStore, JsonFetcher fetch,
                        ConversationStore store, PresenceQuery presence, Clocks clocks, LocalDays days,
                        String platform, Supplier<Optional<String>> audioRobot) {
        this.voice = Objects.requireNonNull(voice, "voice");
        Objects.requireNonNull(model, "model");
        this.settingsStore = settingsStore;
        this.presence = presence;
        this.clocks = clocks;
        this.days = days;
        this.audioRobot = audioRobot;
        this.routing = new VoiceRouting(this, model, fetch, platform);
        this.context = new TurnContext(this);
        this.turns = new VoiceTurns(this, new AnswerLoop(model, clocks::monotonicSeconds));
        this.proactive = new VoiceProactive(this);
        VoiceConfig c = VoiceSettings.config(settings());
        this.memory = new ConversationMemory(c.memoryTurns(), c.memoryResetS(), HISTORY_TOKENS,
                text -> context.estimate(language, text));
        this.language = c.language() != null ? c.language() : c.defaultLanguage();
        this.transcript = new VoiceTranscript(store, clocks, lock);
        transcript.restoreToday(days);
    }

    public void addListener(VoiceListener l) {
        transcript.addListener(l);
    }

    /**
     * Memory (docs/design.md 5.3): the profile joins the system prompt, the question's memory sections the last
     * message, and the memory tools the others. Set before the voice starts.
     */
    public void setMemory(MemoryContext m) {
        memoryContext = Objects.requireNonNull(m, "memory");
    }

    /**
     * The room sensors' latest readings (the robot context's), for the question's context while they are fresh. Set
     * before the voice starts; none by default.
     */
    public void setRoom(Supplier<RoomState> room) {
        this.room = Objects.requireNonNull(room, "room");
    }

    /**
     * The camera (the vision context's): the {@code look} tool is offered while it can take a picture. Set before the
     * voice starts; none by default.
     */
    public void setCamera(Camera c) {
        routing.setCamera(Objects.requireNonNull(c, "camera"));
    }

    /**
     * Background tasks: the conversation offers {@code start_task} and {@code task_result} while tasks are switched on,
     * tells the tasks' {@link ModelLane} when a conversation is going on, and says finished tasks. Set before the voice
     * starts; none by default.
     */
    public void setTasks(TaskService t) {
        tasks = Objects.requireNonNull(t, "tasks");
        lane = t.lane();
        t.setHost(proactive.new TaskHost());
    }

    /** Tools from outside the conversation (a connector): offered where they say, from the next question on. */
    public void addToolSource(ToolSource s) {
        routing.addToolSource(Objects.requireNonNull(s, "source"));
    }

    /** Quiet hours: Marvin says nothing about tasks on its own then (the app's setting). */
    public void setQuiet(BooleanSupplier q) {
        proactive.setQuiet(Objects.requireNonNull(q, "quiet"));
    }

    /**
     * Who Marvin lives with (the app's {@code owner} setting, C5): the greeting on sitting down names them. None by
     * default (a neutral greeting).
     */
    public void setOwner(Supplier<Greeting.Owner> owner) {
        proactive.setOwner(Objects.requireNonNull(owner, "owner"));
    }

    /** The memory sections' shared budget in tokens (what memory may add to each question's prompt). */
    public void setMemoryBudget(int tokens) {
        memoryBudget = Math.max(0, tokens);
    }

    /** Characters per token by language, as calibrated so far. */
    public Map<String, Double> tokenRatios() {
        return context.tokenRatios();
    }

    /** Where each question's trace goes (a span from the end of speech to the last word said). */
    public void setTracing(Tracing t) {
        tracing = Objects.requireNonNull(t, "tracing");
    }

    /** Where each answered turn's latency line goes (v1 contract C6: one JSON object per turn). */
    public void setLatencyLog(LatencyLog l) {
        latencyLog = Objects.requireNonNull(l, "latency log");
    }

    /** The voiceprints the voice is given with its settings (the voice calibration's store). */
    public void setVoiceprints(Supplier<List<Voiceprint>> v) {
        voiceprints = Objects.requireNonNull(v, "voiceprints");
    }

    /** Receives the voice calibration's progress. */
    public void setEnrollment(Consumer<VoiceSidecar.EnrollProgress> e) {
        enrollment = Objects.requireNonNull(e, "enrollment");
    }

    /** Sends a calibration command to the running voice; false when it does not run. */
    public boolean enroll(VoiceSidecar.Enroll command) {
        if (!ON.equals(state) || !opened) {
            return false;
        }
        voice.send(command);
        return true;
    }

    // ------------------------------------------------------------------ settings

    /** voice.json. */
    public Map<String, Object> settings() {
        return settingsStore.load();
    }

    @Override
    public Map<String, Object> appSettings() {
        return VoiceSettings.appSettings(settings());
    }

    @Override
    public Map<String, Object> updateSettings(Map<String, ?> update) {
        Map<String, Object> values = VoiceSettings.validate(update);
        settingsStore.save(values);
        if (!values.isEmpty()) {
            routing.forgetCapabilities();       // a model may have been pulled again, or another chosen
            reload("Voice settings saved");
        }
        return appSettings();
    }

    /**
     * Applies what changed in {@code voice.json} or the voiceprints (the voice calibration): the running voice is
     * given its settings again (it reloads only what changed).
     */
    public void reload(String why) {
        boolean running = ON.equals(state) || STARTING.equals(state);
        transcript.note(why + (running ? ": restarting" : ""));
        if (running) {
            long gen = generation.incrementAndGet();
            setState(STARTING);
            control.execute(() -> doStart(gen));
        }
    }

    // ------------------------------------------------------------------ life

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return "";
    }

    @Override
    public String unavailableFix() {
        return "";
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (ON.equals(state) || STARTING.equals(state) || closed) {
                return;
            }
        }
        long gen = generation.incrementAndGet();
        setState(STARTING);
        control.execute(() -> doStart(gen));
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (OFF.equals(state) || STOPPING.equals(state)) {
                return;
            }
            if (ERROR.equals(state)) {
                generation.incrementAndGet();
                error = "";
                fix = "";
                voice.close();
                opened = false;
                setState(OFF);
                return;
            }
        }
        long gen = generation.incrementAndGet();
        setState(STOPPING);
        control.execute(() -> {
            boolean was = opened;
            teardown();
            if (gen == generation.get()) {
                setState(OFF);
            }
            if (was) {
                transcript.note("Voice off");
            }
        });
    }

    /**
     * Never while the owner is talking: the warm-up would compete with the answer for the model and replace the
     * cached prompt with a rehearsal. It waits until the voice is idle; a question asked meanwhile abandons it.
     */
    @Override
    public void rewarm() {
        VoiceConfig c = config;
        if (closed || c == null || !ON.equals(state)) {
            return;
        }
        rewarmPending.set(true);
        maybeRewarm();
    }

    /** Someone is talking with Marvin: a turn in progress, or the voice listening, thinking or speaking. */
    boolean conversing() {
        VoiceSidecar.Status s = status;
        return turns.active() || s != null && ("listening".equals(s.state()) || "thinking".equals(s.state())
                || "speaking".equals(s.state()));
    }

    void maybeRewarm() {
        VoiceConfig c = config;
        if (!rewarmPending.get() || conversing() || closed || c == null || !ON.equals(state)) {
            return;
        }
        answers.execute(() -> {
            if (ON.equals(state) && !conversing() && rewarmPending.compareAndSet(true, false)) {
                routing.warmUp(c);
            }
        });
    }

    /**
     * Drops the conversation's history (the next question costs one full prompt evaluation). Used when something was
     * forgotten: the earlier questions' memory sections and tool results may state it.
     */
    @Override
    public void clearHistory() {
        historyEpoch.incrementAndGet();
        synchronized (memory) {
            memory.clear();
        }
    }

    /** Stops the voice for good (the host is quitting). */
    public void close() {
        closed = true;
        control.shutdown();
        try {
            control.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        teardown();
        answers.shutdownNow();
        state = OFF;
    }

    private void teardown() {
        proactive.stop();
        pendingImage.set(null);
        turns.cancelAll("off");
        if (opened) {
            voice.close();
        }
        opened = false;
    }

    private void doStart(long gen) {
        if (closed || gen != generation.get()) {
            return;
        }
        Map<String, Object> settings = settings();
        VoiceConfig c = VoiceSettings.config(settings);
        // the audio first (the voice's packages, the microphone, the robot), then the model, as
        // control.preflight: the owner fixes what the voice needs before what the conversation needs
        CompletableFuture<VoiceSidecar.Status> r = new CompletableFuture<>();
        ready = r;
        try {
            VoiceSidecar.Settings wanted = sidecarSettings(c);
            // a restart for a setting the voice does not use (the model, the tools, the model for images): the voice
            // keeps running as it is and reports nothing new, so it is ready as it was
            boolean unchanged = opened && wanted.equals(openedWith);
            voice.open(wanted, this::onSignal);
            opened = true;
            openedWith = wanted;
            VoiceSidecar.Status now = status;
            if (unchanged && now != null && RUNNING.contains(now.state())) {
                r.complete(now);
            }
            voice.send(new VoiceSidecar.Mute(muted));
        } catch (RuntimeException e) {
            ready = null;
            log.log(Level.WARNING, "the voice failed to start", e);
            failed(gen, "The voice failed to start: " + e.getMessage(), "See the Log panel for details.");
            return;
        }
        String[] modelProblem = routing.checkModel(c);
        if (modelProblem != null) {
            VoiceSidecar.Status first = null;
            try {
                first = r.get((long) (AUDIO_CHECK_S * 1000), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // still loading: nothing wrong with the audio so far
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            voice.close();
            opened = false;
            ready = null;
            if (first != null && "error".equals(first.state())) {
                failed(gen, first.error(), first.fix());
            } else {
                failed(gen, modelProblem[0], modelProblem[1]);
            }
            return;
        }
        config = c;
        if (c.language() != null) {
            language = c.language();
        }
        routing.makeTools(c);
        proactive.start(c);
        CompletableFuture<Void> warm = CompletableFuture.runAsync(() -> routing.warmUp(c), answers);
        VoiceSidecar.Status s;
        try {
            s = r.get((long) (READY_TIMEOUT_S * 1000), TimeUnit.MILLISECONDS);
            warm.get((long) (READY_TIMEOUT_S * 1000), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            failed(gen, "The voice did not start in " + (long) READY_TIMEOUT_S + " s", "See the Log panel for details.");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException e) {
            failed(gen, "The voice failed to start: " + e.getCause().getMessage(), "See the Log panel for details.");
            return;
        } finally {
            ready = null;
        }
        if (closed || gen != generation.get()) {
            return;
        }
        if ("error".equals(s.state())) {
            failed(gen, s.error(), s.fix());
            return;
        }
        error = "";
        fix = "";
        setState(ON);
        transcript.note("Voice on: " + (c.wake() ? "say “Marvin, …”" : "just talk"));
    }

    private void failed(long gen, String message, String how) {
        if (gen == generation.get()) {
            failed(message, how);
        }
    }

    private void failed(String message, String how) {
        log.warning("voice: " + message + ". " + how);
        error = message;
        fix = how;
        setState(ERROR);
    }

    /**
     * What the voice is built from, and where it hears and speaks: decided here, once (the voice sidecar is given the
     * device). {@code robot}: the robot's microphone and speaker (none chosen yet when no robot with audio is there:
     * the voice then says so); {@code auto}: the robot's when one is connected, else the computer's.
     */
    VoiceSidecar.Settings sidecarSettings(VoiceConfig c) {
        Optional<String> robot = audioRobot.get();
        AudioDeviceProfile device = "robot".equals(c.audioRoute()) || "auto".equals(c.audioRoute()) && robot.isPresent()
                ? c.robot().forRobot(robot.orElse("")) : c.computer();
        return VoiceSidecar.Settings.of(c, device, voiceprints.get());
    }

    /**
     * A robot with audio connected, or the one in use left: on the {@code auto} and {@code robot} routes the voice moves
     * to it, or back (only its device changes: the voice keeps running).
     */
    public void robotAudioChanged() {
        VoiceConfig c = config;
        if (opened && c != null && !"computer".equals(c.audioRoute())) {
            control.execute(() -> {
                if (opened) {
                    VoiceSidecar.Settings wanted = sidecarSettings(c);
                    if (!wanted.equals(openedWith)) {
                        log.info("voice: now on " + wanted.device().name());
                        voice.open(wanted, this::onSignal);
                        openedWith = wanted;
                    }
                }
            });
        }
    }

    /**
     * Plays an earcon ({@code wake}, {@code done}, {@code error}) on the voice's device, when it runs and has some (see
     * {@link VoiceSnapshot#earcons()}); false when it cannot.
     */
    public boolean earcon(String sound) {
        if (!opened || !ON.equals(state) || !"device".equals(earcons())) {
            return false;
        }
        voice.send(new VoiceSidecar.Earcon(sound));
        return true;
    }

    /** Where the voice's earcons play: {@code device} (its device has some), {@code buzzer} (it has none), {@code none} (off). */
    private String earcons() {
        Map<String, Object> s = settings();
        if (Boolean.FALSE.equals(s.getOrDefault("chime", true))) {
            return "none";
        }
        VoiceSidecar.Settings w = openedWith;
        VoiceSidecar.Status st = status;
        boolean running = ON.equals(state) && st != null && RUNNING.contains(st.state());
        return running && w != null && w.device().hasEarcons() ? "device" : "buzzer";
    }

    // ------------------------------------------------------------------ commands

    private void running() {
        if (!opened || !ON.equals(state)) {
            throw new VoiceOff("Marvin's voice is off");
        }
    }

    @Override
    public void ask(String text) {
        ask(text, null);
    }

    @Override
    public void ask(String text, byte[] image) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("nothing to ask");
        }
        if (t.codePointCount(0, t.length()) > 500) {
            throw new IllegalArgumentException("a question is at most 500 characters");
        }
        running();
        if (image != null) {
            attachImage(image);
        }
        long seq = statusSeq;
        VoiceConfig c = config;
        String lang = c != null && c.language() != null ? c.language()
                : SpeechText.guessLanguage(t, c != null ? c.languages() : List.of("fr", "en"));
        voice.send(new VoiceSidecar.Ask(t, lang == null ? "" : lang));
        awaitStatus(seq, 0.3);
    }

    @Override
    public void listenNow(boolean on) {
        running();
        long seq = statusSeq;
        voice.send(new VoiceSidecar.ListenNow(on));
        awaitStatus(seq, 0.5);
    }

    @Override
    public void stopSpeaking() {
        running();
        voice.send(new VoiceSidecar.StopSpeaking());
    }

    // ------------------------------------------------------------------ an image for the next question

    /** An image waiting for its question, and the model that will look at it. */
    record PendingImage(ImageAttachment image, String model, double at) {

        /** What the app and the conversation entry show of it. */
        Map<String, Object> info() {
            Map<String, Object> m = new LinkedHashMap<>(image.describe());
            m.put("model", model);
            return m;
        }
    }

    @Override
    public Map<String, Object> attachImage(byte[] bytes) {
        running();
        ImageAttachment image = ImageAttachment.of(bytes);
        VoiceConfig c = config != null ? config : VoiceSettings.config(settings());
        String m = c.imageModel();
        boolean sees;
        try {
            sees = routing.canSee(c.ollamaHost(), m);
        } catch (LanguageModel.Unavailable e) {
            throw new ImageRefused("Cannot tell whether " + m + " can see images: " + e.getMessage());
        }
        if (!sees) {
            throw new ImageRefused(refusal(c));
        }
        PendingImage p = new PendingImage(image, m, clocks.monotonicSeconds());
        pendingImage.set(p);
        log.info("an image waits for the next question: " + image.width() + "x" + image.height() + ", " + image.bytes()
                + " bytes, for " + m);
        transcript.publish("voice", snapshot().toMap());
        return p.info();
    }

    /** Why the model cannot be shown an image, and what to do (the app shows it as is). */
    static String refusal(VoiceConfig c) {
        return (c.visionModel().isBlank() ? "This model" : c.imageModel())
                + " cannot see images; choose a vision model in Marvin > Voice";
    }

    @Override
    public void removeImage() {
        if (pendingImage.getAndSet(null) != null) {
            transcript.publish("voice", snapshot().toMap());
        }
    }

    /** The image waiting for a question, unless it waited too long. */
    private PendingImage waitingImage() {
        PendingImage p = pendingImage.get();
        if (p != null && clocks.monotonicSeconds() - p.at() > IMAGE_WAIT_S) {
            pendingImage.compareAndSet(p, null);
            log.info("the image waited " + (int) IMAGE_WAIT_S + " s for a question: dropped");
            return null;
        }
        return p;
    }

    /** The question heard now takes the waiting image. */
    PendingImage takeImage() {
        PendingImage p = waitingImage();
        if (p != null && pendingImage.compareAndSet(p, null)) {
            transcript.publish("voice", snapshot().toMap());
            return p;
        }
        return null;
    }

    @Override
    public void mute(boolean m) {
        muted = m;
        if (opened) {
            long seq = statusSeq;
            voice.send(new VoiceSidecar.Mute(m));
            awaitStatus(seq, 0.3);
        } else {
            transcript.publish("voice", snapshot().toMap());
        }
    }

    /** Waits a little for the voice to report its new state, so the answer to a command shows it. */
    private void awaitStatus(long seq, double seconds) {
        synchronized (lock) {
            if (statusSeq == seq) {
                try {
                    lock.wait((long) (seconds * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ------------------------------------------------------------------ state

    @Override
    public VoiceSnapshot snapshot() {
        Map<String, Object> s = settings();
        VoiceSidecar.Status st = status;
        boolean on = ON.equals(state);
        String model = s.get("llm_model") instanceof String m && !m.isEmpty() ? m : VoiceSettings.DEFAULT_MODEL;
        PendingImage image = on ? waitingImage() : null;
        boolean running = on && st != null && RUNNING.contains(st.state());
        return new VoiceSnapshot(state, running ? st.state() : "off", muted,
                error, fix, model, !Boolean.FALSE.equals(s.getOrDefault("wake", true)),
                !Boolean.FALSE.equals(s.getOrDefault("chime", true)), on ? listenLeft(st) : null,
                on && st != null && st.hearing() && "listening".equals(st.state()), image == null ? null : image.info(),
                running && st.asked() && "listening".equals(st.state()), running ? st.device() : "", earcons());
    }

    /**
     * Seconds left in the listening window now (the voice reports it when its state changes, and when someone starts
     * or stops talking in the window: it does not count down while they talk).
     */
    private Double listenLeft(VoiceSidecar.Status st) {
        if (st == null || st.listenS() == null || st.hearing() || !"listening".equals(st.state())) {
            return null;
        }
        double left = st.listenS() - (clocks.monotonicSeconds() - statusAt);
        return PyNumbers.round(Math.max(0.0, left), 3);
    }

    private void setState(String s) {
        boolean changed;
        synchronized (lock) {
            changed = !s.equals(state);
            state = s;
        }
        if (changed) {
            transcript.publish("voice", snapshot().toMap());
        }
    }

    @Override
    public List<ConversationEntry> recent(long since) {
        return transcript.recent(since);
    }

    // ------------------------------------------------------------------ what the voice reports

    void onSignal(VoiceSidecar.Signal signal) {
        switch (signal) {
            case VoiceSidecar.Status s -> onStatus(s);
            case VoiceSidecar.Heard h -> turns.onHeard(h);
            case VoiceSidecar.Ignored i -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("reason", i.reason());
                if (i.dbfs() != null) {
                    d.put("dbfs", i.dbfs());
                }
                transcript.entry("ignored", clocks.wallSeconds(), i.text(), d);
            }
            case VoiceSidecar.Level l -> lastLevel = transcript.live("level", "mic", l.mic(), "speech", l.speech(),
                    "gated", l.gated(), "dbfs", l.dbfs());
            case VoiceSidecar.Hearing h -> transcript.onHearing(h);
            case VoiceSidecar.Utterance u -> transcript.live("utterance", "state", u.state(), "uid", u.uid());
            case VoiceSidecar.Partial p -> {
                context.prefetch(p.uid(), p.text());
                transcript.live("partial", "uid", p.uid(), "text", p.text());
            }
            case VoiceSidecar.SayProgress p -> transcript.live("say", "text", p.text(), "seconds", p.seconds(),
                    "envelope", p.envelope());
            case VoiceSidecar.Interrupted i -> {
                if (i.utteranceUid() != 0) {
                    turns.onInterrupted(i.utteranceUid(), i.reason());
                } else {
                    proactive.onInterrupted(i.replyId(), i.reason());
                }
            }
            case VoiceSidecar.ReplySpoken r -> {
                if (r.utteranceUid() != 0) {
                    turns.onSpoken(r);
                } else {
                    proactive.onSpoken(r);
                }
            }
            case VoiceSidecar.EnrollProgress p -> enrollment.accept(p);
        }
    }

    @Override
    public Map<String, Object> hearing() {
        VoiceSidecar.Status st = status;
        boolean running = ON.equals(state) && st != null && RUNNING.contains(st.state());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stt", running ? st.stt() : "");
        m.put("tts", running ? st.tts() : "");
        m.put("device", running ? st.device() : "");
        m.put("level", running ? lastLevel : null);
        m.put("recent", transcript.hearings());
        return m;
    }

    private void onStatus(VoiceSidecar.Status s) {
        VoiceSidecar.Status before = status;
        synchronized (lock) {
            status = s;
            statusAt = clocks.monotonicSeconds();
            statusSeq++;
            lock.notifyAll();
        }
        muted = s.muted();
        CompletableFuture<VoiceSidecar.Status> r = ready;
        if (r != null && (RUNNING.contains(s.state()) || "error".equals(s.state()))) {
            r.complete(s);
        }
        if (r == null) {
            if (ON.equals(state) && "error".equals(s.state())) {
                failed(s.error(), s.fix());
            } else if (ON.equals(state) && "starting".equals(s.state())) {
                setState(STARTING);
                return;
            } else if (STARTING.equals(state) && RUNNING.contains(s.state()) && opened) {
                setState(ON);
                return;
            } else if ((ON.equals(state) || STARTING.equals(state)) && "stopped".equals(s.state()) && opened) {
                failed("The voice stopped", "Turn it on again; see the Log panel if it keeps stopping.");
                return;
            }
        }
        if (before == null || !before.equals(s)) {
            transcript.publish("voice", snapshot().toMap());
        }
        maybeRewarm();
        laneUpdate();
    }

    /**
     * Tells the tasks' lane whether a conversation is going on (a task's request gives way to it); when one ends, a task
     * that finished meanwhile may be said.
     */
    void laneUpdate() {
        ModelLane l = lane;
        if (l == null) {
            return;
        }
        boolean busy = conversing();
        l.voice(busy);
        boolean was = laneBusy;
        laneBusy = busy;
        TaskService ts = tasks;
        if (was && !busy && ts != null && !closed) {
            answers.execute(ts::tryAnnounce);
        }
    }

    // ------------------------------------------------------------------ turns and proactive speech

    /** See {@link VoiceTurns#firstWord}. */
    static Double firstWord(Map<String, Double> lat) {
        return VoiceTurns.firstWord(lat);
    }

    /** See {@link VoiceTurns#latencyLine}. */
    static String latencyLine(Map<String, Double> lat) {
        return VoiceTurns.latencyLine(lat);
    }

    /** A brain event: maybe a break reminder or "welcome back" (never during a conversation). */
    public void onPresenceEvent(PresenceEvent event) {
        proactive.onPresenceEvent(event);
    }

    // ------------------------------------------------------------------ options

    @Override
    public Map<String, Object> options() {
        return routing.options(voice);
    }
}
