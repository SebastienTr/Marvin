// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;

import marvin.host.application.conversation.port.out.VoiceListener;
import marvin.host.application.presence.port.out.PresenceHistoryListener;
import marvin.host.application.settings.port.out.SettingsListener;
import marvin.host.application.system.port.out.HostLogListener;
import marvin.host.domain.presence.history.StoredEvent;
import marvin.host.domain.settings.AppSettings;
import marvin.host.domain.system.LogEntry;

/**
 * Fans out messages to the app's connected event streams (the Python {@code _Hub}). Each client has a
 * bounded queue: a stuck client misses messages, it never blocks the brain.
 */
public final class EventHub implements PresenceHistoryListener, HostLogListener, SettingsListener, VoiceListener {
    static final int QUEUE = 256;

    /** One message: an SSE event name and its payload (serialized when sent). */
    record Message(String kind, Object payload) {
    }

    private final Set<BlockingQueue<Message>> clients = new CopyOnWriteArraySet<>();

    BlockingQueue<Message> subscribe() {
        BlockingQueue<Message> q = new ArrayBlockingQueue<>(QUEUE);
        clients.add(q);
        return q;
    }

    void unsubscribe(BlockingQueue<Message> q) {
        clients.remove(q);
    }

    public int size() {
        return clients.size();
    }

    public void publish(String kind, Object payload) {
        Message m = new Message(kind, payload);
        for (BlockingQueue<Message> q : clients) {
            q.offer(m);
        }
    }

    @Override
    public void onStored(StoredEvent event) {
        publish("event", Views.event(event));
    }

    @Override
    public void onEntry(LogEntry entry) {
        publish("log", Views.log(entry));
    }

    @Override
    public void onChanged(AppSettings settings) {
        publish("settings", settings.toMap());
    }

    /** The devices list changed (connected, offline, back). */
    /** The voice's state, conversation entries and live signals, as they come. */
    @Override
    public void onVoice(String kind, Map<String, Object> payload) {
        publish(kind, payload);
    }

    public void devicesChanged(List<Map<String, Object>> devices) {
        publish("devices", devices);
    }
}
