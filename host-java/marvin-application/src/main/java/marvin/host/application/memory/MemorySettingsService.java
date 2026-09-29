// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import marvin.host.application.memory.port.in.ConfigureMemory;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.port.out.MemoryStateStore;
import marvin.host.application.memory.port.out.VoiceModel;
import marvin.host.domain.memory.MemorySettings;

/** The owner's memory settings, kept in memory's own state, and the models they choose. */
public final class MemorySettingsService implements ConfigureMemory {
    static final String KEY = "settings";

    private final MemoryStateStore state;
    private final VoiceModel voice;
    private final List<Consumer<MemorySettings>> listeners = new CopyOnWriteArrayList<>();
    private volatile MemorySettings current;

    public MemorySettingsService(MemoryStateStore state, VoiceModel voice) {
        this.state = state;
        this.voice = voice;
        this.current = MemorySettings.fromStored(state.get(KEY));
    }

    public void addListener(Consumer<MemorySettings> l) {
        listeners.add(l);
    }

    @Override
    public MemorySettings settings() {
        return current;
    }

    @Override
    public synchronized MemorySettings update(Map<String, ?> changes) {
        MemorySettings next = current.with(changes);
        state.put(KEY, next.toMap());
        current = next;
        listeners.forEach(l -> l.accept(next));
        return next;
    }

    /** The model server (the voice's). */
    public String host() {
        return voice.host();
    }

    /** The idle pass's model: the memory model, or the voice's. */
    public MemoryModel.Target idleModel() {
        MemorySettings s = current;
        return new MemoryModel.Target(voice.host(), s.memoryModel().isEmpty() ? voice.model() : s.memoryModel());
    }

    /** The nightly pass's model: the night model, or the idle pass's. */
    public MemoryModel.Target nightModel() {
        MemorySettings s = current;
        return s.nightModel().isEmpty() ? idleModel() : new MemoryModel.Target(voice.host(), s.nightModel());
    }

    /** Whether this model is the voice's (using it evicts the voice's cached prompt: warm it up afterwards). */
    public boolean isVoiceModel(MemoryModel.Target t) {
        return t.model().equals(voice.model()) && t.host().equals(voice.host());
    }
}
