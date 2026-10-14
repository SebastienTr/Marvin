// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.out.JsonFetcher;
import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.port.out.MemoryContext;
import marvin.host.application.conversation.port.out.ToolSource;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.application.conversation.tools.LookTool;
import marvin.host.application.conversation.tools.MemoryTools;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.application.conversation.tools.WeatherTool;
import marvin.host.application.vision.port.in.Camera;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ImageAttachment;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.ReasoningSettings;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.VoiceSettings.VoiceConfig;
import marvin.host.domain.conversation.tool.Look;
import marvin.host.domain.conversation.tool.TaskTools;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.conversation.tool.ToolSurface;
import marvin.host.domain.shared.PyNumbers;

/**
 * Where a question goes (a collaborator of {@link VoiceService}): which model and which tools (the built-in ones,
 * memory's, {@code look} while a camera can take a picture, the tasks', a connector's), whether the model can take
 * tools or see images (asked once per model), the system prompt they make, the warm-up that loads the model and
 * fills its prompt cache, and the options the settings panel offers.
 */
final class VoiceRouting {
    private static final Logger log = Logger.getLogger("marvin.voice");

    private final VoiceService v;
    private final LanguageModel model;
    private final JsonFetcher fetch;
    private final String platform;
    /** Whether a model takes tools, by host and model: assumed until it refuses them. */
    private final Map<String, Boolean> toolSupport = new ConcurrentHashMap<>();
    /** What each model can do, by host and model (Ollama's capabilities): asked once, again after a settings change. */
    private final Map<String, Set<String>> capabilities = new ConcurrentHashMap<>();
    private final List<ToolSource> toolSources = new CopyOnWriteArrayList<>();
    private volatile ToolRegistry tools;
    /** The camera ({@code null}: none on this host), and whether {@link #tools} offers {@code look}. */
    private volatile Camera camera;
    private volatile boolean toolsLook;
    /** Whether {@link #tools} offers the task tools, and which tools the sources gave it. */
    private volatile boolean toolsTasks;
    private volatile String toolsSources = "";
    /** The messages of the last request that filled the model server's cache (to know what it evaluated again). */
    private final Object promptLock = new Object();
    private List<ChatMessage> lastPrompt = List.of();

    VoiceRouting(VoiceService v, LanguageModel model, JsonFetcher fetch, String platform) {
        this.v = v;
        this.model = model;
        this.fetch = fetch;
        this.platform = platform;
    }

    void setCamera(Camera c) {
        camera = c;
    }

    boolean hasCamera() {
        return camera != null;
    }

    void addToolSource(ToolSource s) {
        toolSources.add(s);
    }

    /** A setting changed: the models may have been pulled again, or others chosen. */
    void forgetCapabilities() {
        capabilities.clear();
    }

    /** Whether the model under {@code key} ({@code host model}) is offered tools. */
    boolean takesTools(String key) {
        return toolSupport.getOrDefault(key, true);
    }

    /** The model under {@code key} refused tools: it answers without them from now on. */
    void noTools(String key) {
        toolSupport.put(key, false);
    }

    /** The last prompt the model server cached becomes {@code messages}; returns the one before. */
    List<ChatMessage> replacePrompt(List<ChatMessage> messages) {
        synchronized (promptLock) {
            List<ChatMessage> previous = lastPrompt;
            lastPrompt = List.copyOf(messages);
            return previous;
        }
    }

    // ------------------------------------------------------------------ tools

    /** The tools when the voice starts, with or without {@code look}, the tasks' and the sources' as they are now. */
    void makeTools(VoiceConfig c) {
        toolsLook = cameraAvailable();
        toolsTasks = v.tasks != null && tasksOn();
        toolsSources = sourcesSignature();
        tools = registry(c.tools(), c.internet(), c.homePlace(), toolsLook);
    }

    /**
     * The built-in tools, memory's when it is there, and {@code look} when {@code look} (a camera can take a picture),
     * switched on or off by the settings.
     */
    ToolRegistry registry(boolean enabled, boolean internet, String homePlace, boolean look) {
        List<ToolRegistry.Tool> all = new ArrayList<>();
        all.add(WeatherTool.tool(homePlace, fetch, v.clocks::monotonicSeconds));
        MemoryContext m = v.memoryContext;
        if (m.available()) {
            all.addAll(MemoryTools.tools(m));
        }
        Camera cam = camera;
        if (look && cam != null) {
            all.add(LookTool.tool(cam, this::describe));
        }
        all.addAll(ToolRegistry.fromSources(toolSources, ToolSurface.CONVERSATION));
        all.addAll(ToolRegistry.fromSources(toolSources, ToolSurface.TASK).stream()
                .filter(t -> !t.offeredIn(ToolSurface.CONVERSATION)).toList());       // a task's registry keeps them
        TaskService ts = v.tasks;
        if (ts != null && tasksOn()) {
            all.addAll(ts.conversationTools());
        }
        return new ToolRegistry(all, enabled, internet, v.clocks::monotonicSeconds);
    }

    private boolean tasksOn() {
        try {
            return ReasoningSettings.of(v.settings()).tasksOn();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String sourcesSignature() {
        if (toolSources.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (ToolSurface surface : ToolSurface.values()) {
            ToolRegistry.fromSources(toolSources, surface).forEach(t -> b.append(surface).append(':').append(t.spec().name())
                    .append(' '));
        }
        return b.toString();
    }

    boolean cameraAvailable() {
        Camera cam = camera;
        try {
            return cam != null && cam.available();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not tell whether a camera is there", e);
            return false;
        }
    }

    /**
     * The tools for the next question: when a camera came or went since they were made (a robot with a camera
     * connected, the computer's camera chosen), they are made again with or without {@code look}, and the system prompt
     * changes with them.
     */
    ToolRegistry currentTools() {
        VoiceConfig c = v.config;
        ToolRegistry reg = tools;
        if (c == null || reg == null) {
            return reg;
        }
        boolean look = camera != null ? cameraAvailable() : toolsLook;
        boolean withTasks = v.tasks != null && tasksOn();
        String sources = sourcesSignature();
        if (look != toolsLook || withTasks != toolsTasks || !sources.equals(toolsSources)) {
            synchronized (this) {
                if (look != toolsLook || withTasks != toolsTasks || !sources.equals(toolsSources)) {
                    if (look != toolsLook) {
                        log.info(look ? "a camera can take pictures: Marvin may look" : "no camera now: Marvin cannot look");
                    }
                    if (withTasks != toolsTasks) {
                        log.info(withTasks ? "background tasks on: Marvin may start them" : "background tasks off");
                    }
                    toolsLook = look;
                    toolsTasks = withTasks;
                    toolsSources = sources;
                    tools = registry(c.tools(), c.internet(), c.homePlace(), look);
                }
                reg = tools;
            }
        }
        return reg;
    }

    // ------------------------------------------------------------------ images

    /**
     * The {@code look} tool's eyes: the model for images describes the picture, answering the question when there is
     * one. The picture goes in that one request, never in the conversation.
     */
    String describe(ImageAttachment picture, String question) {
        VoiceConfig c = v.config != null ? v.config : VoiceSettings.config(v.settings());
        String m = c.imageModel();
        try {
            if (!canSee(c.ollamaHost(), m)) {
                throw new ToolError(m + " cannot see pictures: the owner can choose a vision model in Marvin > Voice & models");
            }
        } catch (LanguageModel.Unavailable e) {
            throw new ToolError("the model for pictures cannot be reached (" + e.getMessage() + ")");
        }
        List<ChatMessage> messages = List.of(ChatMessage.system(Look.SYSTEM_PROMPT),
                ChatMessage.user(Look.visionMessage(question), List.of(picture.base64())));
        StringBuilder text = new StringBuilder();
        try {
            model.streamChat(c.ollamaHost(), m, messages, null, Look.DESCRIBE_S, new LanguageModel.Stream() {
                @Override
                public void text(String piece) {
                    text.append(piece);
                }

                @Override
                public void toolCall(marvin.host.domain.conversation.ToolCall call) {
                    // no tools were offered: nothing to do
                }

                @Override
                public boolean cancelled() {
                    return Thread.currentThread().isInterrupted();
                }
            });
        } catch (LanguageModel.Unavailable e) {
            throw new ToolError("the model for pictures failed (" + e.getMessage() + ")");
        }
        String seen = SpeechText.stripThinking(text.toString()).strip();
        log.info("looked with " + m + ": " + ToolResult.shorten(seen, 160));
        return seen;
    }

    /** Whether {@code model} can look at images (the model server's capabilities, asked once per model). */
    boolean canSee(String host, String model) {
        String key = host + " " + model;
        Set<String> caps = capabilities.get(key);
        if (caps == null) {
            caps = Set.copyOf(this.model.capabilities(host, model));
            capabilities.put(key, caps);
        }
        return caps.contains(LanguageModel.VISION);
    }

    // ------------------------------------------------------------------ the prompt

    /**
     * The system prompt: the persona, the memory tools' rules when they are offered, the profile. Byte-identical from
     * one question to the next until the tools or the profile's version change. With memory it does not name the
     * language either (the question's message does): a French question after an English one keeps the model server's
     * cached prompt. Without memory it is the Python host's, which names it.
     */
    String systemPrompt(String lang, boolean offer, ToolRegistry reg, MemoryContext.Profile profile) {
        boolean memoryTools = offer && reg != null && reg.get("remember") != null;
        boolean look = offer && reg != null && reg.get(Look.NAME) != null;
        boolean withTasks = offer && reg != null && reg.get(TaskTools.START) != null;
        return Persona.systemPrompt(v.memoryContext.available() ? null : lang, offer, memoryTools, look, withTasks,
                profile.text());
    }

    MemoryContext.Profile profile() {
        try {
            return v.memoryContext.profile();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the profile", e);
            return MemoryContext.Profile.NONE;
        }
    }

    // ------------------------------------------------------------------ the model

    /** What is wrong with the model server for these settings ({error, fix}), or {@code null}. */
    String[] checkModel(VoiceConfig c) {
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

    /** Loads the model and fills the model server's prompt cache by rehearsing a real first question, twice. */
    void warmUp(VoiceConfig c) {
        String key = c.ollamaHost() + " " + c.llmModel();
        ToolRegistry reg = currentTools();
        boolean offer = reg != null && reg.ollamaTools() != null && toolSupport.getOrDefault(key, true);
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli((long) (v.clocks.wallSeconds() * 1000)),
                v.days.zone());
        String user = Persona.userMessage("Bonjour.", Persona.contextBlock(null, List.of(), now, ""), null);
        long asked = v.turnSeq.get();
        BooleanSupplier question = () -> v.turnSeq.get() != asked;     // a question came: give way
        for (int attempt = 0; attempt < 2; attempt++) {
            List<ChatMessage> messages = List.of(ChatMessage.system(systemPrompt(c.defaultLanguage(), offer, reg, profile())),
                    ChatMessage.user(user));
            List<Map<String, Object>> schemas = offer ? reg.ollamaTools() : null;
            try {
                double t = v.clocks.monotonicSeconds();
                firstToken(c, messages, schemas, question);
                double loaded = v.clocks.monotonicSeconds() - t;
                double t2 = v.clocks.monotonicSeconds();
                firstToken(c, messages, schemas, question);
                double again = v.clocks.monotonicSeconds() - t2;
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
                            BooleanSupplier abandon) {
        if (abandon.getAsBoolean()) {
            return;
        }
        boolean[] got = {false};
        model.streamChat(c.ollamaHost(), c.llmModel(), messages, schemas, VoiceService.READY_TIMEOUT_S, new LanguageModel.Stream() {
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

    // ------------------------------------------------------------------ options

    Map<String, Object> options(VoiceSidecar voice) {
        Map<String, Object> s = v.settings();
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
        List<Map<String, Object>> qwen = new ArrayList<>();
        boolean piperOk = false;
        if (o != null) {
            for (VoiceSidecar.Backend b : o.tts()) {
                tts.add(ordered("name", b.name(), "available", b.installed()));
                piperOk |= "piper".equals(b.name()) && b.installed();
            }
            for (VoiceSidecar.Voice vo : o.voices()) {
                if ("piper".equals(vo.engine())) {
                    piper.add(ordered("name", vo.id(), "installed", vo.installed()));
                } else if ("say".equals(vo.engine())) {
                    say.add(ordered("name", vo.id(), "locale", vo.locale().isEmpty() ? vo.language() : vo.locale()));
                } else if ("qwen".equals(vo.engine())) {
                    qwen.add(ordered("name", vo.id(), "installed", vo.installed()));
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
        voices.put("qwen", qwen);
        List<String> sttModels = new ArrayList<>(List.of("auto"));
        sttModels.addAll(VoiceSettings.STT_MODELS);
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (ToolRegistry.Tool t : registry(true, true, "", camera != null).all()) {
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
        out.put("vision", vision(c, Boolean.TRUE.equals(ollama.get("ok"))));
        out.put("ears", o == null ? List.of() : o.ears().stream()
                .map(b -> ordered("name", b.name(), "installed", b.installed(), "why", b.why())).toList());
        return out;
    }

    /** Whether the model for images can see them, for the settings panel ({@code sees} {@code null}: unknown). */
    private Map<String, Object> vision(VoiceConfig c, boolean ollamaOk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", c.imageModel());
        m.put("own_model", !c.visionModel().isBlank());
        Boolean sees = null;
        String problem = "";
        if (ollamaOk) {
            try {
                sees = canSee(c.ollamaHost(), c.imageModel());
            } catch (LanguageModel.Unavailable e) {
                problem = e.getMessage();
            }
        }
        m.put("sees", sees);
        m.put("error", sees == null ? problem : sees ? "" : VoiceService.refusal(c));
        return m;
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
