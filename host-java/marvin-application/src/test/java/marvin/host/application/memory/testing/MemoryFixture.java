// SPDX-License-Identifier: MIT
package marvin.host.application.memory.testing;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import marvin.host.application.memory.Consolidator;
import marvin.host.application.memory.Embeddings;
import marvin.host.application.memory.MemoryAdminService;
import marvin.host.application.memory.MemoryConfig;
import marvin.host.application.memory.MemorySettingsService;
import marvin.host.application.memory.NightlyPass;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.port.out.VoiceModel;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.LocalDays;

/** Memory's use cases on in-memory stores, a scripted (or real) model and word embeddings. */
public final class MemoryFixture {
    public static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    public final InMemoryMemory store = new InMemoryMemory();
    public final InMemoryMemory.Clock clock;
    public final WordEmbedder embedder = new WordEmbedder();
    public final MemoryModel model;
    public final MemorySettingsService settings;
    public final Embeddings embeddings;
    public final Consolidator consolidator;
    public final NightlyPass nightly;
    public final MemoryAdminService admin;
    public final LocalDays days = new LocalDays(ZONE);
    public final MemoryConfig config = MemoryConfig.DEFAULTS;
    public volatile String voiceModel = "qwen3:4b-instruct";

    public MemoryFixture(Instant now, MemoryModel model, marvin.host.application.memory.port.out.Embedder embedder, String host) {
        this.clock = new InMemoryMemory.Clock(now);
        this.model = model;
        this.settings = new MemorySettingsService(store.state, new VoiceModel() {
            @Override
            public String host() {
                return host;
            }

            @Override
            public String model() {
                return voiceModel;
            }
        });
        this.embeddings = new Embeddings(embedder == null ? this.embedder : embedder, settings, 1024);
        this.consolidator = new Consolidator(store.log, store.facts, store.profiles, embeddings, model, ZONE, config, clock,
                UUID::randomUUID);
        this.nightly = new NightlyPass(store.log, store.facts, store.episodes, store.profiles, embeddings, model, days, config,
                settings, clock);
        this.admin = new MemoryAdminService(store.log, store.facts, store.episodes, store.profiles, embeddings, config, days,
                clock, UUID::randomUUID);
    }

    public MemoryFixture(Instant now, MemoryModel model) {
        this(now, model, null, "http://127.0.0.1:11434");
    }

    /** Appends a conversation line (heard: the owner; reply: Marvin). */
    public MemoryEvent say(Instant at, String kind, String text) {
        return store.log.appendOne(MemoryEvent.draft(at, "conversation", kind, Sensitivity.NORMAL,
                "conversation:" + UUID.randomUUID(), text, Map.of())).orElseThrow();
    }

    public MemoryEvent brain(Instant at, String kind, String text, Sensitivity s) {
        return store.log.appendOne(MemoryEvent.draft(at, "brain", kind, s, "presence:" + UUID.randomUUID(), text, Map.of()))
                .orElseThrow();
    }
}
