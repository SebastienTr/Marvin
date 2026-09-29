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
import marvin.host.application.conversation.ConversationService;
import marvin.host.application.conversation.VoiceService;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.memory.Consolidator;
import marvin.host.application.memory.Embeddings;
import marvin.host.application.memory.MemoryAdminService;
import marvin.host.application.memory.MemoryConfig;
import marvin.host.application.memory.MemoryExportService;
import marvin.host.application.memory.MemoryLogService;
import marvin.host.application.memory.MemorySettingsService;
import marvin.host.application.memory.MemoryWorker;
import marvin.host.application.memory.NightlyPass;
import marvin.host.application.memory.port.in.MemoryHealth;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.VoiceModel;
import marvin.host.application.presence.PresenceHistoryService;
import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.domain.conversation.VoiceSettings;
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
                                          JdbcProfileStore profiles, Embeddings embeddings, LocalDays days, Clocks clocks) {
        return new MemoryAdminService(events, facts, episodes, profiles, embeddings, MemoryConfig.DEFAULTS, days, clocks,
                UUID::randomUUID);
    }

    @Bean
    public MemoryExportService memoryExport(JdbcEventLog events, JdbcFactStore facts, JdbcEpisodeStore episodes,
                                            JdbcProfileStore profiles, MemorySettingsService settings, Clocks clocks) {
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
                                     JdbcProfileStore profiles, Embeddings embeddings, OllamaMemoryModel model,
                                     MemorySettingsService settings, VoiceActivityTracker activity, VoiceService voice,
                                     JdbcMemoryState state, LocalDays days, Clocks clocks,
                                     @Value("${marvin.memory.tick-s:30}") double tickS) {
        MemoryConfig config = new MemoryConfig(MemoryConfig.DEFAULTS.batchGap(), MemoryConfig.DEFAULTS.maxBatchEvents(),
                MemoryConfig.DEFAULTS.pageSize(), MemoryConfig.DEFAULTS.similarK(), MemoryConfig.DEFAULTS.similarityFloor(),
                MemoryConfig.DEFAULTS.maxDaysPerNight(), tickS, MemoryConfig.DEFAULTS.tokens(), MemoryConfig.DEFAULTS.decay());
        Consolidator consolidator = new Consolidator(events, facts, profiles, embeddings, model, days.zone(), config, clocks,
                UUID::randomUUID);
        NightlyPass nightly = new NightlyPass(events, facts, episodes, profiles, embeddings, model, days, config, settings, clocks);
        MemoryWorker worker = new MemoryWorker(consolidator, nightly, events, settings, activity, voice::rewarm, state, days,
                clocks, config);
        return worker;
    }

    /**
     * After the start (the import done, the stores ready): the one-time backfill of the conversation and presence
     * histories, a first embedding to know the model is there, then the worker's schedule. At stop: the worker, then
     * what the log still has queued.
     */
    @Bean
    public SmartLifecycle memoryLifecycle(MemoryLogService memoryLog, MemoryWorker worker, Embeddings embeddings,
                                          ConversationService conversation, PresenceHistoryService presence,
                                          @Value("${marvin.memory.worker:true}") boolean enabled) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
                Thread.ofVirtual().name("memory-start").start(() -> {
                    try {
                        memoryLog.backfill(MemoryFeeds.conversation(conversation));
                        memoryLog.backfill(MemoryFeeds.presence(presence));
                    } catch (RuntimeException e) {
                        log.warn("memory backfill failed (it is tried again at the next start): {}", e.getMessage());
                    }
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
