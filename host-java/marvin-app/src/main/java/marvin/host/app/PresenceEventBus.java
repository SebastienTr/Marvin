// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.application.presence.port.out.PresenceEventPublisher;
import marvin.host.domain.presence.event.PresenceEvent;

/**
 * Delivers the brain's events to every subscriber (the face link now; the app and the store next), on
 * the thread that produced them, in order. A failing subscriber does not stop the others.
 */
public final class PresenceEventBus implements PresenceEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(PresenceEventBus.class);

    private final List<Consumer<PresenceEvent>> subscribers = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<PresenceEvent> subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(Consumer<PresenceEvent> subscriber) {
        subscribers.remove(subscriber);
    }

    @Override
    public void publish(PresenceEvent event) {
        log.info("{} s {} {}", String.format(java.util.Locale.ROOT, "%.2f", event.tUs() / 1e6),
                event.kind().wireName(), event.detail());
        for (Consumer<PresenceEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (RuntimeException e) {
                log.warn("presence event subscriber failed", e);
            }
        }
    }
}
