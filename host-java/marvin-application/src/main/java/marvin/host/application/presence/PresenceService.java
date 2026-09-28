// SPDX-License-Identifier: MIT
package marvin.host.application.presence;

import java.util.List;
import java.util.Objects;

import marvin.host.application.presence.port.in.ObservePresence;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.presence.port.out.PresenceEventPublisher;
import marvin.host.domain.presence.Brain;
import marvin.host.domain.presence.BrainConfig;
import marvin.host.domain.presence.TargetSighting;
import marvin.host.domain.presence.VitalsReading;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;

/**
 * The presence use cases around one {@link Brain}: frames in, state and events out. The brain is
 * called under a lock; events are published after it is released, in the order they happened.
 */
public final class PresenceService implements ObservePresence, PresenceQuery {
    private final Brain brain;
    private final PresenceEventPublisher publisher;
    private final Object lock = new Object();
    private volatile PresenceState state;

    public PresenceService(BrainConfig config, PresenceEventPublisher publisher) {
        this.brain = new Brain(config);
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.state = brain.state();
    }

    @Override
    public void onTargets(long tUs, boolean simulated, List<TargetSighting> targets) {
        List<PresenceEvent> events;
        synchronized (lock) {
            events = brain.onTargets(tUs, simulated, targets);
            state = brain.state();
        }
        events.forEach(publisher::publish);
    }

    @Override
    public void onVitals(long tUs, boolean simulated, VitalsReading reading) {
        List<PresenceEvent> events;
        synchronized (lock) {
            events = brain.onVitals(tUs, simulated, reading);
            state = brain.state();
        }
        events.forEach(publisher::publish);
    }

    @Override
    public PresenceState state() {
        return state;
    }

    @Override
    public List<PresenceEvent> recentEvents() {
        synchronized (lock) {
            return brain.events();
        }
    }

    public BrainConfig config() {
        return brain.config();
    }
}
