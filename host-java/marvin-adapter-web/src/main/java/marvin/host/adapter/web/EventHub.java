// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;

import marvin.host.application.conversation.port.out.TaskListener;
import marvin.host.application.conversation.port.out.VoiceListener;
import marvin.host.application.memory.port.out.MemoryListener;
import marvin.host.application.presence.port.out.PresenceHistoryListener;
import marvin.host.application.settings.port.out.SettingsListener;
import marvin.host.application.system.port.out.HostLogListener;
import marvin.host.application.vision.port.out.CameraListener;
import marvin.host.domain.presence.history.StoredEvent;
import marvin.host.domain.settings.AppSettings;
import marvin.host.domain.system.LogEntry;

/**
 * Fans out messages to the app's connected event streams (the Python {@code _Hub}). Each client has a
 * bounded queue: a stuck client misses messages, it never blocks the brain. A client says which kinds it wants: the
 * app's main stream takes everything but the ears' diagnostics ({@link #SENSES_ONLY}), which only the Senses screen's
 * stream carries.
 */
public final class EventHub implements PresenceHistoryListener, HostLogListener, SettingsListener, VoiceListener,
        MemoryListener, CameraListener, TaskListener {
    static final int QUEUE = 256;
    /** Kinds only a stream that asks for them gets (the Senses screen's). */
    static final Set<String> SENSES_ONLY = Set.of("hearing");

    /** One message: an SSE event name and its payload (serialized when sent). */
    record Message(String kind, Object payload) {
    }

    private record Client(BlockingQueue<Message> queue, Predicate<String> wants) {
    }

    private final Set<Client> clients = new CopyOnWriteArraySet<>();

    /** Every kind but {@link #SENSES_ONLY}. */
    BlockingQueue<Message> subscribe() {
        return subscribe(kind -> !SENSES_ONLY.contains(kind));
    }

    /** Only the kinds {@code wants} accepts. */
    BlockingQueue<Message> subscribe(Predicate<String> wants) {
        BlockingQueue<Message> q = new ArrayBlockingQueue<>(QUEUE);
        clients.add(new Client(q, wants));
        return q;
    }

    void unsubscribe(BlockingQueue<Message> q) {
        clients.removeIf(c -> c.queue() == q);
    }

    public int size() {
        return clients.size();
    }

    public void publish(String kind, Object payload) {
        Message m = new Message(kind, payload);
        for (Client c : clients) {
            if (c.wants().test(kind)) {
                c.queue().offer(m);
            }
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

    /**
     * Memory changed ({@code memory} messages): {@code {"kind": "changed", "what": "facts" | "profile" | "log" | "all"}},
     * {@code {"kind": "worker", ...the worker's state}}, {@code {"kind": "forget", "pending": n}}. The app reloads
     * what it shows.
     */
    @Override
    public void onMemory(String kind, Map<String, Object> payload) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", kind);
        m.putAll(payload);
        publish("memory", m);
    }

    /** A camera took a picture ({@code camera} messages: which camera, why, its size, when; never the picture). */
    @Override
    public void pictureTaken(Map<String, Object> picture) {
        publish("camera", picture);
    }

    /** Background tasks: {@code task} (a task as lists show it) and {@code task_step} ({@code {id, step}}). */
    @Override
    public void onTask(String kind, Map<String, Object> payload) {
        publish(kind, payload);
    }

    public void devicesChanged(List<Map<String, Object>> devices) {
        publish("devices", devices);
    }
}
