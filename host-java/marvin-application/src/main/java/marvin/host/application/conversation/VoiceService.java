// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.conversation.port.out.JsonFetcher;
import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.port.out.MemoryContext;
import marvin.host.application.conversation.port.out.VoiceListener;
import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.tools.MemoryTools;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.application.conversation.tools.WeatherTool;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.system.port.out.Tracing;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ContextAssembler;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.ConversationMemory;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.ProactiveSpeech;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.VoiceSettings.VoiceConfig;
import marvin.host.domain.conversation.VoiceSnapshot;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LanguageTokens;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.shared.PyNumbers;
import marvin.host.domain.shared.TokenEstimator;

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
 */
public final class VoiceService implements VoiceControl {
    private static final Logger log = Logger.getLogger("marvin.voice");

    public static final String OFF = "off";
    public static final String STARTING = "starting";
    public static final String ON = "on";
    public static final String STOPPING = "stopping";
    public static final String ERROR = "error";
    /** Live signals passed straight to the app. */
    public static final Set<String> LIVE_KINDS = Set.of("level", "utterance", "partial", "say");
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

    private final VoiceSidecar voice;
    private final LanguageModel model;
    private final VoiceSettingsStore settingsStore;
    private final JsonFetcher fetch;
    private final ConversationStore store;
    private final PresenceQuery presence;
    private final Clocks clocks;
    private final LocalDays days;
    private final String platform;
    private final BooleanSupplier robotAudio;
    private final AnswerLoop loop;
    private final List<VoiceListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService control = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("marvin-voice-control").factory());
    private final ExecutorService answers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("marvin-voice-answer-", 0).factory());

    private final Object lock = new Object();
    private final Deque<ConversationEntry> transcript = new ArrayDeque<>();
    private final AtomicLong ids;
    private final AtomicLong replyIds = new AtomicLong(1);
    private final Map<Long, Turn> turns = new ConcurrentHashMap<>();
    /** The last turns by utterance uid, finished or not: what a question that continues them needs to know. */
    private final Map<Long, Turn> recentTurns = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Turn> eldest) {
            return size() > 32;
        }
    });
    /** The last turn heard: each turn builds its messages only once the one before is over (Python's single worker). */
    private Turn lastTurn;
    private final Map<Long, String> proactiveReplies = new ConcurrentHashMap<>();
    private final Map<String, Boolean> toolSupport = new ConcurrentHashMap<>();
    private final ConversationMemory memory;

    private volatile String state = OFF;
    private volatile String error = "";
    private volatile String fix = "";
    private volatile boolean muted;
    private volatile VoiceSidecar.Status status;
    /** When {@link #status} came (monotonic seconds): {@code listen_s} counts down from there. */
    private volatile double statusAt;
    private volatile boolean opened;
    private volatile boolean closed;
    private volatile String language;
    private volatile VoiceConfig config;
    private volatile ToolRegistry tools;
    private volatile ProactiveSpeech proactive;
    private volatile Tracing tracing = Tracing.NONE;
    private volatile MemoryContext memoryContext = MemoryContext.NONE;
    /** The memory sections' shared budget (tokens), {@link ContextAssembler#MEMORY_BUDGET} unless the host sets it. */
    private volatile int memoryBudget = ContextAssembler.MEMORY_BUDGET;
    /** Characters per token by language, calibrated from the model server's counts. */
    private volatile LanguageTokens tokenEstimates = LanguageTokens.initial();
    /** The messages of the last request that filled the model server's cache (to know what it evaluated again). */
    private final Object promptLock = new Object();
    private List<ChatMessage> lastPrompt = List.of();
    /** Questions heard so far (a {@code forget} is confirmed in a later turn than the one that proposed it). */
    private final AtomicLong turnSeq = new AtomicLong();
    /** A warm-up asked for while the owner was talking: it runs once the voice is idle again. */
    private final java.util.concurrent.atomic.AtomicBoolean rewarmPending = new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * Moves on when the history must go (a guest came in, something was forgotten): a turn that began before does not
     * add its messages, which may hold what must not be sent again.
     */
    private final AtomicLong historyEpoch = new AtomicLong();
    /** Whether the last question was asked with someone else in the room. */
    private volatile boolean othersBefore;
    /** Memory searches started on a speculative transcript, by utterance. */
    private final Map<Long, Prefetch> prefetches = new ConcurrentHashMap<>();
    private volatile CompletableFuture<VoiceSidecar.Status> ready;
    private volatile long statusSeq;
    /** Each start, stop or restart supersedes the ones before it (they run one at a time, in order). */
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param platform   {@code darwin}, {@code linux} ... (the settings panel offers {@code say} voices on a Mac)
     * @param robotAudio whether a robot with a microphone and a speaker is connected (for the {@code auto} route)
     */
    public VoiceService(VoiceSidecar voice, LanguageModel model, VoiceSettingsStore settingsStore, JsonFetcher fetch,
                        ConversationStore store, PresenceQuery presence, Clocks clocks, LocalDays days,
                        String platform, BooleanSupplier robotAudio) {
        this.voice = Objects.requireNonNull(voice, "voice");
        this.model = Objects.requireNonNull(model, "model");
        this.settingsStore = settingsStore;
        this.fetch = fetch;
        this.store = store;
        this.presence = presence;
        this.clocks = clocks;
        this.days = days;
        this.platform = platform;
        this.robotAudio = robotAudio;
        this.loop = new AnswerLoop(model, clocks::monotonicSeconds);
        VoiceConfig c = VoiceSettings.config(settings());
        this.memory = new ConversationMemory(c.memoryTurns(), c.memoryResetS(), HISTORY_TOKENS,
                text -> tokenEstimates.estimate(language, text));
        this.language = c.language() != null ? c.language() : c.defaultLanguage();
        // ids keep growing across restarts, above everything already stored, so an open page never mistakes a
        // new entry for one it already shows
        this.ids = new AtomicLong(Math.max((long) (clocks.wallSeconds() * 1000), store.maxId() + 1));
        restoreToday();
    }

    private void restoreToday() {
        double now = clocks.wallSeconds();
        double[] b = days.bounds(days.dayOf(now));
        try {
            List<ConversationEntry> past = store.between(b[0], now + 86400, HISTORY);
            synchronized (lock) {
                transcript.addAll(past);
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the conversation history", e);
        }
    }

    public void addListener(VoiceListener l) {
        listeners.add(l);
    }

    /**
     * Memory (docs/design.md 5.3): the profile joins the system prompt, the question's memory sections the last
     * message, and the memory tools the others. Set before the voice starts.
     */
    public void setMemory(MemoryContext m) {
        memoryContext = Objects.requireNonNull(m, "memory");
    }

    /** The memory sections' shared budget in tokens (what memory may add to each question's prompt). */
    public void setMemoryBudget(int tokens) {
        memoryBudget = Math.max(0, tokens);
    }

    /** Characters per token by language, as calibrated so far. */
    public Map<String, Double> tokenRatios() {
        return tokenEstimates.ratios();
    }

    /** Where each question's trace goes (a span from the end of speech to the last word said). */
    public void setTracing(Tracing t) {
        tracing = Objects.requireNonNull(t, "tracing");
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
            boolean running = ON.equals(state) || STARTING.equals(state);
            note("Voice settings saved" + (running ? ": restarting" : ""));
            if (running) {
                long gen = generation.incrementAndGet();
                setState(STARTING);
                control.execute(() -> doStart(gen));
            }
        }
        return appSettings();
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
                note("Voice off");
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
    private boolean conversing() {
        VoiceSidecar.Status s = status;
        return !turns.isEmpty() || s != null && ("listening".equals(s.state()) || "thinking".equals(s.state())
                || "speaking".equals(s.state()));
    }

    private void maybeRewarm() {
        VoiceConfig c = config;
        if (!rewarmPending.get() || conversing() || closed || c == null || !ON.equals(state)) {
            return;
        }
        answers.execute(() -> {
            if (ON.equals(state) && !conversing() && rewarmPending.compareAndSet(true, false)) {
                warmUp(c);
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
        proactive = null;
        turns.values().forEach(t -> t.cancel("off"));
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
            voice.open(sidecarSettings(c), this::onSignal);
            opened = true;
            voice.send(new VoiceSidecar.Mute(muted));
        } catch (RuntimeException e) {
            ready = null;
            log.log(Level.WARNING, "the voice failed to start", e);
            failed(gen, "The voice failed to start: " + e.getMessage(), "See the Log panel for details.");
            return;
        }
        String[] modelProblem = checkModel(c);
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
        tools = registry(c.tools(), c.internet(), c.homePlace());
        proactive = new ProactiveSpeech(c.reminders(), c.welcomeBack());
        CompletableFuture<Void> warm = CompletableFuture.runAsync(() -> warmUp(c), answers);
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
        note("Voice on: " + (c.wake() ? "say “Marvin, …”" : "just talk"));
    }

    /** What is wrong with the model server for these settings ({error, fix}), or {@code null}. */
    private String[] checkModel(VoiceConfig c) {
        try {
            List<String> models = model.models(c.ollamaHost());
            if (!models.contains(c.llmModel()) && !models.contains(c.llmModel() + ":latest")) {
                return new String[] {"Ollama has no model " + c.llmModel(), "ollama pull " + c.llmModel()};
            }
            return null;
        } catch (LanguageModel.Unavailable e) {
            return new String[] {"Ollama is not running at " + c.ollamaHost() + " (" + e.getMessage() + ")",
                    "Install Ollama (https://ollama.com/download or `brew install ollama`), then start it: "
                            + "`ollama serve` or the Ollama app."};
        }
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

    VoiceSidecar.Settings sidecarSettings(VoiceConfig c) {
        boolean robot = "robot".equals(c.audioRoute()) || "auto".equals(c.audioRoute()) && robotAudio.getAsBoolean();
        return new VoiceSidecar.Settings(c.stt(), c.sttModel(), c.tts(), c.ttsVoice(), c.language(),
                c.defaultLanguage(), c.wake(), c.duplex(), c.echoTailS(), c.followUpS(), c.listenWindowS(),
                c.speculativeStt(), c.endSilenceMs(), c.chime(), c.inputDevice(), c.outputDevice(), robot,
                c.continueGraceS(), c.endSilenceLongMs());
    }

    /** A robot with audio connected or left: with the {@code auto} route, the voice moves to it or back. */
    public void robotAudioChanged() {
        VoiceConfig c = config;
        if (opened && c != null && "auto".equals(c.audioRoute())) {
            control.execute(() -> {
                if (opened) {
                    voice.open(sidecarSettings(c), this::onSignal);
                }
            });
        }
    }

    /** The built-in tools, and memory's when it is there, switched on or off by the settings. */
    private ToolRegistry registry(boolean enabled, boolean internet, String homePlace) {
        List<ToolRegistry.Tool> all = new ArrayList<>();
        all.add(WeatherTool.tool(homePlace, fetch, clocks::monotonicSeconds));
        MemoryContext m = memoryContext;
        if (m.available()) {
            all.addAll(MemoryTools.tools(m));
        }
        return new ToolRegistry(all, enabled, internet, clocks::monotonicSeconds);
    }

    /**
     * The system prompt: the persona, the memory tools' rules when they are offered, the profile. Byte-identical from
     * one question to the next until the tools or the profile's version change. With memory it does not name the
     * language either (the question's message does): a French question after an English one keeps the model server's
     * cached prompt. Without memory it is the Python host's, which names it.
     */
    private String systemPrompt(String lang, boolean offer, ToolRegistry reg, MemoryContext.Profile profile) {
        boolean memoryTools = offer && reg != null && reg.get("remember") != null;
        return Persona.systemPrompt(memoryContext.available() ? null : lang, offer, memoryTools, profile.text());
    }

    private MemoryContext.Profile profile() {
        try {
            return memoryContext.profile();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the profile", e);
            return MemoryContext.Profile.NONE;
        }
    }

    /** Loads the model and fills the model server's prompt cache by rehearsing a real first question, twice. */
    private void warmUp(VoiceConfig c) {
        String key = c.ollamaHost() + " " + c.llmModel();
        ToolRegistry reg = tools;
        boolean offer = reg != null && reg.ollamaTools() != null && toolSupport.getOrDefault(key, true);
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli((long) (clocks.wallSeconds() * 1000)), days.zone());
        String user = Persona.userMessage("Bonjour.", Persona.contextBlock(null, List.of(), now, ""), null);
        long asked = turnSeq.get();
        java.util.function.BooleanSupplier question = () -> turnSeq.get() != asked;     // a question came: give way
        for (int attempt = 0; attempt < 2; attempt++) {
            List<ChatMessage> messages = List.of(ChatMessage.system(systemPrompt(c.defaultLanguage(), offer, reg, profile())),
                    ChatMessage.user(user));
            List<Map<String, Object>> schemas = offer ? reg.ollamaTools() : null;
            try {
                double t = clocks.monotonicSeconds();
                firstToken(c, messages, schemas, question);
                double loaded = clocks.monotonicSeconds() - t;
                double t2 = clocks.monotonicSeconds();
                firstToken(c, messages, schemas, question);
                double again = clocks.monotonicSeconds() - t2;
                if (question.getAsBoolean()) {
                    return;                 // the question's own request fills the cache; its prompt stays the last one
                }
                synchronized (promptLock) {
                    lastPrompt = messages;
                }
                log.info("model " + c.llmModel() + " ready (prompt cached) in " + PyNumbers.fixed(loaded, 1)
                        + " s; first token now " + PyNumbers.fixed(again, 2) + " s");
                if (again > 1.5 && again > 0.5 * loaded) {
                    log.warning("Ollama did not reuse the cached prompt (first token still " + PyNumbers.fixed(again, 1)
                            + " s): the first question will be slow");
                }
                return;
            } catch (LanguageModel.ToolsUnsupported e) {
                log.warning(e.getMessage() + ": answering without tools");
                toolSupport.put(key, false);
                offer = false;
            } catch (LanguageModel.Unavailable e) {
                log.warning(e.getMessage() + ". " + e.hint());
                return;
            }
        }
    }

    private void firstToken(VoiceConfig c, List<ChatMessage> messages, List<Map<String, Object>> schemas,
                            java.util.function.BooleanSupplier abandon) {
        if (abandon.getAsBoolean()) {
            return;
        }
        boolean[] got = {false};
        model.streamChat(c.ollamaHost(), c.llmModel(), messages, schemas, READY_TIMEOUT_S, new LanguageModel.Stream() {
            @Override
            public void text(String piece) {
                got[0] = true;
            }

            @Override
            public void toolCall(marvin.host.domain.conversation.ToolCall call) {
                got[0] = true;
            }

            @Override
            public boolean cancelled() {
                return got[0] || abandon.getAsBoolean();
            }
        });
    }

    // ------------------------------------------------------------------ commands

    private void running() {
        if (!opened || !ON.equals(state)) {
            throw new VoiceOff("Marvin's voice is off");
        }
    }

    @Override
    public void ask(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("nothing to ask");
        }
        if (t.codePointCount(0, t.length()) > 500) {
            throw new IllegalArgumentException("a question is at most 500 characters");
        }
        running();
        long seq = statusSeq;
        voice.send(new VoiceSidecar.Ask(t, ""));
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

    @Override
    public void mute(boolean m) {
        muted = m;
        if (opened) {
            long seq = statusSeq;
            voice.send(new VoiceSidecar.Mute(m));
            awaitStatus(seq, 0.3);
        } else {
            publish("voice", snapshot().toMap());
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
        return new VoiceSnapshot(state, on && st != null && RUNNING.contains(st.state()) ? st.state() : "off", muted,
                error, fix, model, !Boolean.FALSE.equals(s.getOrDefault("wake", true)),
                !Boolean.FALSE.equals(s.getOrDefault("chime", true)), on ? listenLeft(st) : null,
                on && st != null && st.hearing() && "listening".equals(st.state()));
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
            publish("voice", snapshot().toMap());
        }
    }

    @Override
    public List<ConversationEntry> recent(long since) {
        synchronized (lock) {
            return transcript.stream().filter(e -> e.id() > since).sorted(Comparator.comparingLong(ConversationEntry::id)).toList();
        }
    }

    private ConversationEntry entry(String kind, double t, String text, Map<String, Object> data) {
        ConversationEntry e = new ConversationEntry(ids.getAndIncrement(), t, kind, text, data);
        synchronized (lock) {
            transcript.addLast(e);
            while (transcript.size() > HISTORY) {
                transcript.removeFirst();
            }
        }
        try {
            store.add(e);
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "could not save a conversation entry", ex);
        }
        publish("transcript", e.toLiveMap());
        return e;
    }

    private void note(String text) {
        entry("note", clocks.wallSeconds(), text, Map.of());
    }

    private void publish(String kind, Map<String, Object> payload) {
        for (VoiceListener l : listeners) {
            try {
                l.onVoice(kind, payload);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "voice listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ what the voice reports

    void onSignal(VoiceSidecar.Signal signal) {
        switch (signal) {
            case VoiceSidecar.Status s -> onStatus(s);
            case VoiceSidecar.Heard h -> onHeard(h);
            case VoiceSidecar.Ignored i -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("reason", i.reason());
                if (i.dbfs() != null) {
                    d.put("dbfs", i.dbfs());
                }
                entry("ignored", clocks.wallSeconds(), i.text(), d);
            }
            case VoiceSidecar.Level l -> live("level", "mic", l.mic(), "speech", l.speech(), "gated", l.gated());
            case VoiceSidecar.Utterance u -> live("utterance", "state", u.state(), "uid", u.uid());
            case VoiceSidecar.Partial p -> {
                prefetch(p.uid(), p.text());
                live("partial", "uid", p.uid(), "text", p.text());
            }
            case VoiceSidecar.SayProgress p -> live("say", "text", p.text(), "seconds", p.seconds(), "envelope", p.envelope());
            case VoiceSidecar.Interrupted i -> onInterrupted(i);
            case VoiceSidecar.ReplySpoken r -> onSpoken(r);
        }
    }

    private void live(String kind, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        m.put("t", clocks.wallSeconds());
        publish(kind, m);
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
            publish("voice", snapshot().toMap());
        }
        maybeRewarm();
    }

    // ------------------------------------------------------------------ turns

    /** One question and its answer. */
    private final class Turn {
        final VoiceSidecar.Heard heard;
        /** The conversation entry of what was heard (written late for a question that continues others). */
        volatile long said;
        final long replyId;
        final long number = turnSeq.incrementAndGet();
        /** The memory candidates for this question, searched while it was still being recognised when possible. */
        volatile CompletableFuture<MemoryContext.Recollection> recollection;
        volatile boolean othersPresent;
        final CompletableFuture<VoiceSidecar.ReplySpoken> spoken = new CompletableFuture<>();
        /** Complete once this turn is over: said (or dropped) and remembered. */
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile boolean superseded;
        volatile boolean cancelled;
        volatile double cancelledAt;
        /** Why it was cancelled first ({@code merged}: a question that continues it follows). */
        volatile String cancelReason;
        /** The user message sent to the model, once built. */
        volatile String userMessage;
        /** It is in the history (and was the last question there when it was put). */
        volatile boolean remembered;
        /** A reply entry was written for it. */
        volatile boolean replied;

        Turn(VoiceSidecar.Heard heard, long replyId) {
            this.heard = heard;
            this.replyId = replyId;
        }

        void cancel(String reason) {
            if (!cancelled) {
                cancelledAt = clocks.monotonicSeconds();
                cancelReason = reason;
            }
            cancelled = true;
        }

        boolean merged() {
            return cancelled && "merged".equals(cancelReason);
        }
    }

    private void onHeard(VoiceSidecar.Heard h) {
        language = h.language() == null || h.language().isEmpty() ? language : h.language();
        boolean continues = !h.continues().isEmpty();
        Turn turn = new Turn(h, replyIds.getAndIncrement());
        if (!continues) {
            turn.said = heardEntry(h).id();
        }
        recentTurns.put(h.uid(), turn);
        startRecollection(turn);
        Turn previous;
        synchronized (lock) {
            previous = lastTurn;
            lastTurn = turn;
        }
        if (previous != null) {
            previous.superseded = true;
        }
        turns.put(h.uid(), turn);
        answers.execute(() -> {
            try {
                if (previous != null) {
                    previous.done.join();       // its answer (even cut short) is in the history first
                }
                if (continues) {
                    // after what the questions it continues left (a cut answer): one bubble, in order
                    turn.said = heardEntry(h).id();
                }
                if (turn.cancelled) {
                    return;                     // dropped by the voice before its turn came: nothing to answer
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("utterance", Long.toString(h.uid()));
                attributes.put("source", h.source().isEmpty() ? "voice" : h.source());
                VoiceConfig c = config;
                attributes.put("audio_route", c == null ? "" : sidecarSettings(c).robot() ? "robot" : "computer");
                try (Tracing.Span span = tracing.start("marvin.question", attributes)) {
                    answer(turn, span);
                }
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "the answer failed", e);
            } finally {
                turns.remove(h.uid());
                turn.done.complete(null);
                maybeRewarm();
            }
        });
    }

    /** The conversation entry of a question heard; with what it continues, when it continues earlier ones. */
    private ConversationEntry heardEntry(VoiceSidecar.Heard h) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("language", h.language());
        d.put("source", h.source().isEmpty() ? "voice" : h.source());
        if (h.raw() != null && !h.raw().isEmpty()) {
            d.put("raw", h.raw());
        }
        if (!h.continues().isEmpty()) {
            List<Long> replaces = new ArrayList<>();
            List<Long> goesOn = new ArrayList<>();
            for (long uid : h.continues()) {
                Turn t = recentTurns.get(uid);
                if (t != null && t.said != 0) {
                    (t.replied ? goesOn : replaces).add(t.said);
                }
            }
            d.put("joined", h.continues().size() + 1);
            d.put("replaces", replaces);        // their bubbles give way to this one: nothing was answered in between
            d.put("continues", goesOn);         // this one goes on after their (cut) answers
            log.info("joined " + (h.continues().size() + 1) + " utterances: " + h.text());
        }
        return entry("heard", h.wallTime() > 0 ? h.wallTime() : clocks.wallSeconds(), h.text(), d);
    }

    private void onInterrupted(VoiceSidecar.Interrupted i) {
        if (i.utteranceUid() != 0) {
            Turn t = turns.get(i.utteranceUid());
            if (t != null) {
                t.cancel(i.reason());
            }
        } else if (proactiveReplies.remove(i.replyId()) != null) {
            log.fine("proactive speech skipped: " + i.reason());
        }
    }

    private void onSpoken(VoiceSidecar.ReplySpoken r) {
        if (r.utteranceUid() != 0) {
            Turn t = turns.get(r.utteranceUid());
            if (t != null) {
                t.spoken.complete(r);
            }
            return;
        }
        String lang = proactiveReplies.remove(r.replyId());
        if (lang != null && !r.interrupted()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("language", lang);
            d.put("latency", Map.of());
            d.put("first_word_s", null);
            d.put("interrupted", false);
            d.put("proactive", true);
            d.put("error", null);
            d.put("hint", "");
            entry("reply", clocks.wallSeconds(), r.text(), d);
        }
    }

    private void answer(Turn turn, Tracing.Span span) {
        VoiceSidecar.Heard h = turn.heard;
        VoiceConfig c = config != null ? config : VoiceSettings.config(settings());
        ToolRegistry reg = tools;
        String key = c.ollamaHost() + " " + c.llmModel();
        boolean offer = reg != null && reg.ollamaTools() != null && toolSupport.getOrDefault(key, true);
        String lang = h.language() == null || h.language().isEmpty() ? language : h.language();
        double now = clocks.monotonicSeconds();
        if (turn.othersPresent && !othersBefore) {
            // a guest came in: the earlier questions' memory sections may hold what the guest must not hear
            clearHistory();
        }
        othersBefore = turn.othersPresent;
        long epoch = historyEpoch.get();
        List<ChatMessage> history;
        synchronized (memory) {
            for (long uid : h.continues()) {
                // a question whose answer this one cut: this one says it all again, the cut turn leaves the history
                Turn t = recentTurns.get(uid);
                if (t != null && t.remembered && t.userMessage != null) {
                    t.remembered = !memory.forgetLast(t.userMessage);
                }
            }
            history = memory.messages(now);
        }
        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli((long) (clocks.wallSeconds() * 1000)), days.zone());
        MemoryContext.Profile profile = profile();
        Assembled asm = assemble(turn, lang, local, offer ? c.homePlace() : "");
        if (asm.recollection() != null) {
            // what memory cost this question, on its trace (docs/design.md 10.4)
            asm.recollection().timings().forEach((k, v) -> span.attribute("marvin.memory." + k + "_s", v));
            span.attribute("marvin.memory.waited_s", asm.waitedS());
            span.attribute("marvin.memory.timed_out", Boolean.toString(asm.timedOut()));
            span.attribute("marvin.memory.tokens", asm.estimatedTokens());
        }
        String context = asm.context();
        String user = Persona.userMessage(h.text(), context, lang);
        turn.userMessage = user;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(lang, offer, reg, profile)));
        messages.addAll(history);
        messages.add(ChatMessage.user(user));
        List<ChatMessage> previous;
        synchronized (promptLock) {
            previous = lastPrompt;
            lastPrompt = List.copyOf(messages);
        }
        if (!asm.usedFacts().isEmpty()) {
            memoryContext.used(asm.usedFacts());
        }
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(MemoryTools.TURN, turn.number);
        toolContext.put(MemoryTools.SAID, turn.said);
        toolContext.put(MemoryTools.OTHERS_PRESENT, turn.othersPresent);

        voice.send(new VoiceSidecar.ReplyStart(turn.replyId, lang, false, h.uid()));
        AnswerLoop.Outcome out = loop.answer(c.ollamaHost(), c.llmModel(), messages, reg, offer, lang,
                c.maxToolRounds(), MODEL_TIMEOUT_S, () -> turn.cancelled, new AnswerLoop.Speaker() {
                    @Override
                    public void say(String sentence) {
                        if (!SpeechText.cleanForSpeech(sentence).isEmpty()) {     // the voice would not say it
                            voice.send(new VoiceSidecar.Text(turn.replyId, sentence + "\n"));
                        }
                    }

                    @Override
                    public void filler(String text) {
                        voice.send(new VoiceSidecar.Filler(turn.replyId, text, lang));
                    }
                }, () -> toolSupport.put(key, false), toolContext);
        Map<String, Object> usage = usage(out, previous, messages, lang, span);
        voice.send(new VoiceSidecar.ReplyEnd(turn.replyId, out.failure() == null ? "" : out.failure()));

        VoiceSidecar.ReplySpoken spoken = awaitSpoken(turn);
        if (spoken == null) {
            return;                             // dropped before it was said: nothing to record
        }
        boolean interrupted = turn.cancelled || spoken.interrupted();
        int joined = h.continues().isEmpty() ? 0 : h.continues().size() + 1;
        if (turn.merged()) {
            // the owner went on talking: the question that continues this one is answered instead, this one is not
            // remembered, and shows only if Marvin had started saying something
            if (!SpeechText.cleanForSpeech(spoken.text()).isEmpty()) {
                reply(spoken.text(), lang, latency(h.latency(), out.latency(), spoken.latency()), true, out, context, user,
                        c, null, "", asm.report(profile, usage), joined);
                turn.replied = true;
            }
            return;
        }
        String said = out.saidAnswer();
        Map<String, Double> lat = latency(h.latency(), out.latency(), spoken.latency());
        lat.forEach((k, v) -> span.attribute("marvin.latency." + k, v));
        span.attribute("marvin.language", lang);
        span.attribute("marvin.interrupted", Boolean.toString(turn.cancelled || spoken.interrupted()));
        if (out.failure() != null) {
            span.error(out.failure());
            reply(spoken.text(), lang, lat, false, out, context, user, c, out.failure(), out.hint(), asm.report(profile, usage),
                    joined);
            turn.replied = true;
            return;
        }
        synchronized (memory) {
            // cleared during this turn (something was forgotten, maybe by this very turn): its messages stay out
            boolean keep = historyEpoch.get() == epoch;
            if (keep && interrupted) {
                if (!said.isEmpty() || !out.exchange().isEmpty()) {
                    memory.remember(user, (said + " …").strip(), out.exchange(), clocks.monotonicSeconds());
                    turn.remembered = true;
                }
            } else if (keep) {
                memory.remember(user, said, out.exchange(), clocks.monotonicSeconds());
                turn.remembered = true;
            }
        }
        reply(spoken.text(), lang, lat, interrupted, out, context, user, c, null, "", asm.report(profile, usage), joined);
        turn.replied = true;
    }

    private VoiceSidecar.ReplySpoken awaitSpoken(Turn turn) {
        while (true) {
            try {
                return turn.spoken.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                double grace = turn.superseded ? SUPERSEDED_GRACE_S : SPOKEN_GRACE_S;
                if (turn.cancelled && clocks.monotonicSeconds() - turn.cancelledAt > grace) {
                    return null;
                }
                if (closed || !opened) {
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null;
            }
        }
    }

    /** The latency breakdown: listening stages, then the model's, then the speaking stages (seconds, 3 decimals). */
    static Map<String, Double> latency(Map<String, Double> heard, Map<String, Double> core, Map<String, Double> spoken) {
        Map<String, Double> lat = new LinkedHashMap<>();
        List<String> order = List.of("endpoint", "queue", "stt", "speculative", "wake");
        for (String k : order) {
            if (heard.containsKey(k)) {
                lat.put(k, heard.get(k));
            }
        }
        heard.forEach(lat::putIfAbsent);
        core.forEach(lat::put);
        spoken.forEach((k, v) -> {
            if (!"reply_start".equals(k) && !("first_chunk".equals(k) && lat.containsKey(k))) {
                lat.put(k, v);
            }
        });
        Map<String, Double> out = new LinkedHashMap<>();
        lat.forEach((k, v) -> out.put(k, PyNumbers.round(v, 3)));
        return out;
    }

    private void reply(String text, String lang, Map<String, Double> lat, boolean interrupted, AnswerLoop.Outcome out,
                       String context, String prompt, VoiceConfig c, String failure, String hint, Map<String, Object> memoryReport,
                       int joined) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("language", lang);
        d.put("latency", lat);
        d.put("first_word_s", lat.containsKey("audio_start")
                ? PyNumbers.round(lat.getOrDefault("endpoint", 0.0) + lat.get("audio_start"), 2) : null);
        d.put("interrupted", interrupted);
        d.put("proactive", false);
        d.put("error", failure);
        d.put("hint", hint);
        d.put("context", context);
        d.put("prompt", prompt);
        d.put("model", c.llmModel());
        if (!out.calls().isEmpty()) {
            d.put("tools", out.calls());
        }
        if (memoryReport != null) {
            d.put("memory", memoryReport);
        }
        if (joined > 1) {
            d.put("joined", joined);            // the question was said in this many breaths (the inspector says so)
        }
        entry("reply", clocks.wallSeconds(), text, d);
    }

    // ------------------------------------------------------------------ memory in the prompt

    /** A memory search started on a speculative transcript. */
    private record Prefetch(String text, boolean othersPresent, CompletableFuture<MemoryContext.Recollection> result) {
    }

    private static String normal(String text) {
        return text == null ? "" : String.join(" ", text.strip().toLowerCase(java.util.Locale.ROOT).split("\\s+"));
    }

    private boolean othersPresent() {
        try {
            PresenceState st = presence.state();
            return st != null && st.targets() > 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private CompletableFuture<MemoryContext.Recollection> search(String text, boolean others) {
        MemoryContext m = memoryContext;
        return CompletableFuture.supplyAsync(() -> m.recollect(text, new MemoryContext.Audience(others, false)), answers);
    }

    /**
     * The speculative transcript of an utterance: its memory search starts now, overlapping the end of recognition;
     * the final transcript reuses it when the words are the same.
     */
    private void prefetch(long uid, String text) {
        if (!memoryContext.available() || text == null || text.isBlank()) {
            return;
        }
        Prefetch p = prefetches.get(uid);
        if (p != null && p.text().equals(normal(text))) {
            return;
        }
        boolean others = othersPresent();
        prefetches.put(uid, new Prefetch(normal(text), others, search(text, others)));
        if (prefetches.size() > 16) {                   // utterances that were never heard (dropped, noise)
            prefetches.keySet().stream().sorted().limit(prefetches.size() - 8L).toList().forEach(prefetches::remove);
        }
    }

    /** The question's memory search: the speculative one when it searched the same words, else a new one. */
    private void startRecollection(Turn turn) {
        Prefetch p = prefetches.remove(turn.heard.uid());
        if (!memoryContext.available()) {
            return;
        }
        boolean others = othersPresent();
        if (p != null && p.text().equals(normal(turn.heard.text())) && p.othersPresent() == others) {
            turn.othersPresent = p.othersPresent();
            turn.recollection = p.result();
            return;
        }
        turn.othersPresent = others;
        turn.recollection = search(turn.heard.text(), others);
    }

    /**
     * The question's context, assembled within its budgets (docs/design.md 5.3).
     *
     * @param usedFacts the facts sent (their keys)
     */
    private record Assembled(String context, List<ContextAssembler.Cut> sections, MemoryContext.Recollection recollection,
                             double waitedS, boolean timedOut, List<String> usedFacts, int estimatedTokens) {

        /** What the reply inspector shows; {@code null} without memory. */
        Map<String, Object> report(MemoryContext.Profile profile, Map<String, Object> usage) {
            if (recollection == null) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("version", profile.version());
            p.put("chars", profile.text().length());
            m.put("profile", p);
            m.put("sections", sections.stream().map(ContextAssembler.Cut::report).toList());
            m.put("tokens", estimatedTokens);
            Map<String, Object> t = new LinkedHashMap<>();
            recollection.timings().forEach((k, v) -> t.put(k, PyNumbers.round(v, 4)));
            t.put("waited", PyNumbers.round(waitedS, 4));
            m.put("timings", t);
            String problem = timedOut ? "memory took longer than " + (int) (MEMORY_WAIT_S * 1000) + " ms: answered without it"
                    : recollection.problem();
            m.put("problem", problem);
            if (usage != null) {
                m.put("usage", usage);
            }
            return m;
        }
    }

    private Assembled assemble(Turn turn, String lang, LocalDateTime local, String home) {
        TokenEstimator est = tokenEstimates.of(lang);
        CompletableFuture<MemoryContext.Recollection> f = turn.recollection;
        if (f == null) {
            // no memory: the "now" section as it always was (no budget without the memory sections beside it)
            return new Assembled(Persona.contextBlock(presence.state(), presence.recentEvents(), local, home), List.of(), null, 0,
                    false, List.of(), 0);
        }
        double t0 = clocks.monotonicSeconds();
        MemoryContext.Recollection r;
        boolean timedOut = false;
        try {
            r = f.get((long) (MEMORY_WAIT_S * 1000), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            r = MemoryContext.Recollection.EMPTY;
            timedOut = true;
            log.warning("memory took longer than " + (int) (MEMORY_WAIT_S * 1000) + " ms: answering without it");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r = MemoryContext.Recollection.EMPTY;
        } catch (ExecutionException e) {
            log.log(Level.WARNING, "memory failed", e.getCause());
            r = MemoryContext.Recollection.EMPTY;
        }
        double waited = clocks.monotonicSeconds() - t0;
        List<ContextAssembler.Item> nowItems = Persona.contextItems(presence.state(), presence.recentEvents(), local, home);
        ContextAssembler.Cut now = ContextAssembler.cut(new ContextAssembler.Section("now", Persona.NOW_HEADING,
                ContextAssembler.NOW_BUDGET, nowItems), est);
        // the memory sections share one budget: it bounds what memory adds to every question's prompt evaluation
        List<ContextAssembler.Cut> mem = ContextAssembler.cutTogether(List.of(
                new ContextAssembler.Section("today", Persona.GIST_HEADING, ContextAssembler.GIST_BUDGET, r.gist()),
                new ContextAssembler.Section("facts", Persona.FACTS_HEADING, ContextAssembler.FACTS_BUDGET, r.facts())),
                memoryBudget, est);
        ContextAssembler.Cut gist = mem.get(0);
        ContextAssembler.Cut facts = mem.get(1);
        String context = Persona.context(List.of(ContextAssembler.render(now), ContextAssembler.render(gist),
                ContextAssembler.render(facts)));
        List<String> used = facts.kept().stream().map(ContextAssembler.Item::key).toList();
        return new Assembled(context, List.of(now, gist, facts), r, waited, timedOut, used,
                now.tokens() + gist.tokens() + facts.tokens());
    }

    /**
     * The model server's counts for the question's first request: logged, traced, shown in the reply inspector, and
     * used to calibrate the token estimate of the language. With the prompt cache, the count covers only what was
     * evaluated again: the messages after those the previous request shared with this one (each message also costs
     * the chat template's few tokens).
     */
    private Map<String, Object> usage(AnswerLoop.Outcome out, List<ChatMessage> previous, List<ChatMessage> messages, String lang,
                                      Tracing.Span span) {
        if (out.usage().isEmpty()) {
            return null;
        }
        LanguageModel.Usage u = out.usage().getFirst();
        int shared = 0;
        while (shared < previous.size() && shared < messages.size() - 1 && previous.get(shared).equals(messages.get(shared))) {
            shared++;
        }
        int chars = 0;
        for (int i = shared; i < messages.size(); i++) {
            chars += messages.get(i).content().length();
        }
        int templateTokens = TEMPLATE_TOKENS_PER_MESSAGE * (messages.size() - shared) + TEMPLATE_TOKENS_REPLY;
        if (shared > 0 && chars >= CALIBRATION_MIN_CHARS && u.promptEvalCount() > templateTokens) {
            tokenEstimates = tokenEstimates.calibrated(lang, chars, u.promptEvalCount() - templateTokens);
        }
        double rate = u.promptEvalSeconds() > 0 ? u.promptEvalCount() / u.promptEvalSeconds() : 0;
        int reused = shared;
        log.info(() -> "prompt: " + u.promptEvalCount() + " tokens evaluated in " + PyNumbers.fixed(u.promptEvalSeconds(), 3)
                + " s (" + PyNumbers.fixed(rate, 0) + " tokens/s), " + messages.size() + " messages, " + (messages.size()
                - reused) + " new");
        span.attribute("marvin.prompt_eval_count", u.promptEvalCount());
        span.attribute("marvin.prompt_eval_s", u.promptEvalSeconds());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prompt_eval_count", u.promptEvalCount());
        m.put("prompt_eval_s", PyNumbers.round(u.promptEvalSeconds(), 4));
        m.put("eval_count", u.evalCount());
        m.put("eval_s", PyNumbers.round(u.evalSeconds(), 4));
        m.put("load_s", PyNumbers.round(u.loadSeconds(), 4));
        m.put("messages_reused", shared);
        m.put("chars_evaluated", chars);
        return m;
    }

    /** Qwen's chat template: {@code <|im_start|>role\n ... <|im_end|>\n} per message, then the reply's opening. */
    static final int TEMPLATE_TOKENS_PER_MESSAGE = 5;
    static final int TEMPLATE_TOKENS_REPLY = 3;
    /** Shorter prompts than this give a ratio too noisy to learn from. */
    static final int CALIBRATION_MIN_CHARS = 200;

    // ------------------------------------------------------------------ proactive speech

    /** A brain event: maybe a break reminder or "welcome back" (never during a conversation). */
    public void onPresenceEvent(PresenceEvent event) {
        ProactiveSpeech p = proactive;
        if (p == null || !ON.equals(state) || !opened) {
            return;
        }
        String lang = language;
        double now = clocks.monotonicSeconds();
        String text = p.onEvent(event, lang, now).orElse(null);
        if (text == null) {
            return;
        }
        VoiceSidecar.Status s = status;
        if (!turns.isEmpty() || s != null && ("thinking".equals(s.state()) || "speaking".equals(s.state()))) {
            log.fine("proactive speech skipped during a conversation: " + text);
            return;
        }
        long id = replyIds.getAndIncrement();
        proactiveReplies.put(id, lang);
        voice.send(new VoiceSidecar.Say(id, text, lang, false, true));
        p.spoken(now);
    }

    // ------------------------------------------------------------------ options

    @Override
    public Map<String, Object> options() {
        Map<String, Object> s = settings();
        VoiceConfig c = VoiceSettings.config(s);
        Map<String, Object> ollama = new LinkedHashMap<>();
        List<String> models;
        try {
            models = model.models(c.ollamaHost()).stream().sorted().toList();
            ollama.put("ok", true);
            ollama.put("error", "");
            ollama.put("fix", "");
        } catch (LanguageModel.Unavailable e) {
            models = List.of();
            ollama.put("ok", false);
            ollama.put("error", "Ollama is not running at " + c.ollamaHost() + " (" + e.getMessage() + ")");
            ollama.put("fix", "Install Ollama (https://ollama.com/download or `brew install ollama`), then start it: "
                    + "`ollama serve` or the Ollama app.");
        }
        ollama.put("host", c.ollamaHost());
        VoiceSidecar.Options o = voice.options().orElse(null);
        List<Map<String, Object>> tts = new ArrayList<>();
        List<Map<String, Object>> piper = new ArrayList<>();
        List<Map<String, Object>> say = new ArrayList<>();
        boolean piperOk = false;
        if (o != null) {
            for (VoiceSidecar.Backend b : o.tts()) {
                tts.add(ordered("name", b.name(), "available", b.installed()));
                piperOk |= "piper".equals(b.name()) && b.installed();
            }
            for (VoiceSidecar.Voice v : o.voices()) {
                if ("piper".equals(v.engine())) {
                    piper.add(ordered("name", v.id(), "installed", v.installed()));
                } else if ("say".equals(v.engine())) {
                    say.add(ordered("name", v.id(), "locale", v.locale().isEmpty() ? v.language() : v.locale()));
                }
            }
        } else {
            for (String name : VoiceSettings.TTS_CHOICES) {
                tts.add(ordered("name", name, "available", "auto".equals(name)));
            }
        }
        Map<String, Object> voices = new LinkedHashMap<>();
        voices.put("piper", piperOk ? piper : List.of());
        voices.put("say", say);
        List<String> sttModels = new ArrayList<>(List.of("auto"));
        sttModels.addAll(VoiceSettings.STT_MODELS);
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (ToolRegistry.Tool t : registry(true, true, "").all()) {
            catalog.add(ordered("name", t.spec().name(), "description", t.spec().description(), "online", t.spec().online()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("llm_models", models);
        out.put("ollama", ollama);
        out.put("stt_backends", VoiceSettings.STT_CHOICES);
        out.put("stt_models", sttModels);
        out.put("tts_backends", tts);
        out.put("voices", voices);
        out.put("languages", List.of("auto", "fr", "en"));
        out.put("platform", platform);
        out.put("tools", catalog);
        return out;
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
