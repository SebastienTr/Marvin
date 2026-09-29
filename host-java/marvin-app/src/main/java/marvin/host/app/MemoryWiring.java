// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import marvin.host.adapter.llm.OllamaEmbedder;
import marvin.host.adapter.llm.OllamaMemoryModel;
import marvin.host.adapter.persistence.JdbcConversationStore;
import marvin.host.adapter.persistence.JdbcEpisodeStore;
import marvin.host.adapter.persistence.JdbcEventLog;
import marvin.host.adapter.persistence.JdbcFactStore;
import marvin.host.adapter.persistence.JdbcMemoryState;
import marvin.host.adapter.persistence.JdbcProfileStore;
import marvin.host.adapter.persistence.VoiceSettingsFile;
import marvin.host.adapter.web.EventHub;
import marvin.host.application.conversation.ConversationService;
import marvin.host.application.conversation.VoiceService;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.memory.CachedProfiles;
import marvin.host.application.memory.Consolidator;
import marvin.host.application.memory.Embeddings;
import marvin.host.application.memory.ForgetConfirmations;
import marvin.host.application.memory.MemoryAdminService;
import marvin.host.application.memory.MemoryConfig;
import marvin.host.application.memory.MemoryExportService;
import marvin.host.application.memory.MemoryGuard;
import marvin.host.application.memory.MemoryLogService;
import marvin.host.application.memory.MemoryRecallService;
import marvin.host.application.memory.MemorySettingsService;
import marvin.host.application.memory.MemoryWorker;
import marvin.host.application.memory.NightlyPass;
import marvin.host.application.memory.port.in.MemoryHealth;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.VoiceModel;
import marvin.host.application.presence.PresenceHistoryService;
import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.memory.RetrievalScoring;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.system.ComponentHealth;

/**
 * Memory (docs/design.md 5): the event log fed by the conversation and the presence history, the memory worker, the
 * owner's control of it, and its line in the health report.
 */
@Configuration(proxyBeanMethods = false)
public class MemoryWiring {
    private static final Logger log = LoggerFactory.getLogger("marvin.memory");

    /** The voice's model server and model, read from voice.json when asked (the owner may change them). */
    @Bean
    public MemorySettingsService memorySettings(JdbcMemoryState state, VoiceSettingsFile voiceSettings) {
        return new MemorySettingsService(state, new VoiceModel() {
            @Override
            public String host() {
                return VoiceSettings.config(voiceSettings.load()).ollamaHost();
            }

            @Override
            public String model() {
                return VoiceSettings.config(voiceSettings.load()).llmModel();
            }
        });
    }

    /** The profile versions, with the active one cached for the voice (every memory service shares this one). */
    @Bean
    public CachedProfiles memoryProfiles(JdbcProfileStore profiles) {
        return new CachedProfiles(profiles);
    }

    /** Orders the owner's changes and the memory worker's writes (every memory service shares this one). */
    @Bean
    public MemoryGuard memoryGuard() {
        return new MemoryGuard();
    }

    @Bean
    public Embeddings memoryEmbeddings(OllamaEmbedder embedder, MemorySettingsService settings, JdbcFactStore facts) {
        return new Embeddings(embedder, settings, facts.dimensions());
    }

    @Bean(destroyMethod = "close")
    public MemoryLogService memoryLog(JdbcEventLog events, JdbcMemoryState state, MemorySettingsService settings, Clocks clocks) {
        return new MemoryLogService(events, state, settings, clocks, false);
    }

    /** The conversation store every user of it gets: it also feeds memory. */
    @Bean
    @Primary
    public ConversationStore rememberedConversation(JdbcConversationStore store, MemoryLogService memory) {
        return new MemoryFeeds.RememberedConversation(store, memory);
    }

    @Bean
    public MemoryAdminService memoryAdmin(JdbcEventLog events, JdbcFactStore facts, JdbcEpisodeStore episodes,
                                          CachedProfiles profiles, Embeddings embeddings, LocalDays days, Clocks clocks,
                                          MemoryGuard guard, JdbcMemoryState state) {
        return new MemoryAdminService(events, facts, episodes, profiles, embeddings, MemoryConfig.DEFAULTS, days, clocks,
                UUID::randomUUID, guard, state);
    }

    @Bean
    public MemoryExportService memoryExport(JdbcEventLog events, JdbcFactStore facts, JdbcEpisodeStore episodes,
                                            CachedProfiles profiles, MemorySettingsService settings, Clocks clocks) {
        return new MemoryExportService(events, facts, episodes, profiles, settings, clocks);
    }

    @Bean
    public VoiceActivityTracker voiceActivity(Clocks clocks, VoiceService voice) {
        VoiceActivityTracker tracker = new VoiceActivityTracker(clocks, voice::snapshot);
        voice.addListener(tracker);
        return tracker;
    }

    @Bean(destroyMethod = "")
    public MemoryWorker memoryWorker(JdbcEventLog events, JdbcFactStore facts, JdbcEpisodeStore episodes,
                                     CachedProfiles profiles, Embeddings embeddings, OllamaMemoryModel model,
                                     MemorySettingsService settings, VoiceActivityTracker activity, VoiceService voice,
                                     JdbcMemoryState state, LocalDays days, Clocks clocks, MemoryGuard guard,
                                     org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer,
                                     @Value("${marvin.memory.tick-s:30}") double tickS) {
        MemoryConfig config = new MemoryConfig(MemoryConfig.DEFAULTS.batchGap(), MemoryConfig.DEFAULTS.maxBatchEvents(),
                MemoryConfig.DEFAULTS.pageSize(), MemoryConfig.DEFAULTS.similarK(), MemoryConfig.DEFAULTS.similarityFloor(),
                MemoryConfig.DEFAULTS.maxDaysPerNight(), tickS, MemoryConfig.DEFAULTS.tokens(), MemoryConfig.DEFAULTS.decay());
        Consolidator consolidator = new Consolidator(events, facts, profiles, embeddings, model, days.zone(), config, clocks,
                UUID::randomUUID, episodes, guard);
        NightlyPass nightly = new NightlyPass(events, facts, episodes, profiles, embeddings, model, days, config, settings, clocks,
                guard, state);
        MemoryWorker worker = new MemoryWorker(consolidator, nightly, events, settings, activity, voice::rewarm, state, days,
                clocks, config, guard, embeddings);
        tracer.ifAvailable(t -> worker.setTracing(new MicrometerTracing(t)));
        return worker;
    }

    /** The read path: the profile, the question's memory sections, the recall tool; a profile edit warms the voice up. */
    @Bean(destroyMethod = "close")
    public MemoryRecallService memoryRecall(JdbcFactStore facts, JdbcEventLog events, JdbcEpisodeStore episodes,
                                            CachedProfiles profiles, Embeddings embeddings, LocalDays days, Clocks clocks,
                                            VoiceService voice, MemoryAdminService admin) {
        MemoryRecallService recall = new MemoryRecallService(facts, events, episodes, profiles, embeddings, days, clocks,
                RetrievalScoring.DEFAULT, voice::rewarm);
        admin.addListener(recall);
        return recall;
    }

    @Bean
    public ForgetConfirmations memoryForgetting(JdbcFactStore facts, MemoryAdminService admin, Embeddings embeddings,
                                                Clocks clocks) {
        return new ForgetConfirmations(facts, admin, embeddings, clocks, UUID::randomUUID);
    }

    /**
     * Memory for the conversation, through its port: the voice gets the profile, the memory sections and the memory
     * tools ({@code marvin.memory.read=false}: none of them, the voice as before memory).
     */
    @Bean
    public MemoryContextBinding memoryForConversation(MemoryRecallService recall, MemoryAdminService admin,
                                                      ForgetConfirmations forgetting, VoiceService voice,
                                                      @Value("${marvin.memory.read:true}") boolean read,
                                                      @Value("${marvin.memory.volatile-budget:250}") int budget) {
        if (read) {
            voice.setMemory(new MemoryForConversation(recall, admin, forgetting));
            voice.setMemoryBudget(budget);
            // what was forgotten may still be in the conversation's history (memory sections, tool results)
            admin.addForgetListener(voice::clearHistory);
        }
        return new MemoryContextBinding(read);
    }

    /** Whether the voice was given memory (for the log and tests). */
    public record MemoryContextBinding(boolean read) {
    }

    /** Memory's changes reach the app live, on the event stream ({@code memory} messages). */
    @Bean
    public MemoryStreamBinding memoryStream(EventHub hub, MemoryAdminService admin, MemoryWorker worker,
                                            ForgetConfirmations forgetting) {
        admin.addListener(hub);
        worker.addListener(hub);
        forgetting.addListener(hub);
        return new MemoryStreamBinding();
    }

    /** Marks that the event stream carries memory's changes. */
    public record MemoryStreamBinding() {
    }

    /**
     * After the start (the import done, the stores ready): the catch-up of the conversation and presence histories
     * (their whole past the first time, then every ten minutes what the live feed missed), a first embedding to know the model is there, then the worker's schedule. At stop: the worker, then
     * what the log still has queued.
     */
    @Bean
    public SmartLifecycle memoryLifecycle(MemoryLogService memoryLog, MemoryWorker worker, Embeddings embeddings,
                                          ConversationService conversation, PresenceHistoryService presence,
                                          @Value("${marvin.memory.worker:true}") boolean enabled) {
        return new SmartLifecycle() {
            private volatile boolean running;
            private final java.util.concurrent.ScheduledExecutorService feeds = java.util.concurrent.Executors
                    .newSingleThreadScheduledExecutor(Thread.ofVirtual().name("memory-feeds").factory());

            /** The conversation's and presence's records the live feed missed (all of them at the first start). */
            private void catchUp() {
                try {
                    memoryLog.catchUp(MemoryFeeds.conversation(conversation));
                    memoryLog.catchUp(MemoryFeeds.presence(presence));
                } catch (RuntimeException e) {
                    log.warn("memory catch-up failed (it is tried again in a few minutes): {}", e.getMessage());
                }
            }

            @Override
            public void start() {
                running = true;
                Thread.ofVirtual().name("memory-start").start(() -> {
                    catchUp();
                    feeds.scheduleWithFixedDelay(this::catchUp, 10, 10, java.util.concurrent.TimeUnit.MINUTES);
                    try {
                        embeddings.embed(List.of("ready"));
                    } catch (Embedder.Unavailable e) {
                        // the state and its fix are in the health report
                    }
                    if (enabled) {
                        worker.start();
                    }
                });
            }

            @Override
            public void stop() {
                running = false;
                feeds.shutdownNow();
                worker.close();
                memoryLog.close();
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE / 2 + 100;          // stops after the voice, before the web server and the database
            }
        };
    }

    @Bean
    public ComponentProbe memoryProbe(MemoryHealth memory) {
        return new ComponentProbe() {
            @Override
            public String name() {
                return "memory";
            }

            @Override
            public ComponentHealth check() {
                MemoryHealth.Report r;
                try {
                    r = memory.health();
                } catch (RuntimeException e) {
                    return ComponentHealth.down(e.getMessage());
                }
                String detail = r.facts() + " facts, " + r.events() + " events (" + r.pending() + " not consolidated yet), "
                        + r.searchMode() + ", embeddings " + r.embedModel();
                if ("unavailable".equals(r.embedder())) {
                    return ComponentHealth.disabled(detail + " unavailable: " + r.embedderError()
                            + (r.fix().isEmpty() ? "" : ". " + r.fix()));
                }
                return ComponentHealth.up(detail + ("ready".equals(r.embedder()) ? " ready" : ""));
            }
        };
    }
}
