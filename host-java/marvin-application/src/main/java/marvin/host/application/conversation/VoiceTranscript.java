// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.conversation.port.out.VoiceListener;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * What the voice leaves behind and passes on (a collaborator of {@link VoiceService}): the conversation entries
 * (today's, restored at start, the last {@link VoiceService#HISTORY} in memory, each saved in the conversation store
 * and published live), the voice's live signals for the app, and the ears' diagnostics.
 */
final class VoiceTranscript {
    private static final Logger log = Logger.getLogger("marvin.voice");

    private final ConversationStore store;
    private final Clocks clocks;
    private final List<VoiceListener> listeners = new CopyOnWriteArrayList<>();
    /** Guards the transcript; the voice's own lock, so a command waiting for its status sees entries in order. */
    private final Object lock;
    private final Deque<ConversationEntry> transcript = new ArrayDeque<>();
    private final Deque<Map<String, Object>> hearings = new ArrayDeque<>();
    private final AtomicLong ids;

    VoiceTranscript(ConversationStore store, Clocks clocks, Object lock) {
        this.store = store;
        this.clocks = clocks;
        this.lock = lock;
        // ids keep growing across restarts, above everything already stored, so an open page never mistakes a
        // new entry for one it already shows
        this.ids = new AtomicLong(Math.max((long) (clocks.wallSeconds() * 1000), store.maxId() + 1));
    }

    /** Today's entries, read back from the store when the host starts. */
    void restoreToday(LocalDays days) {
        double now = clocks.wallSeconds();
        double[] b = days.bounds(days.dayOf(now));
        try {
            List<ConversationEntry> past = store.between(b[0], now + 86400, VoiceService.HISTORY);
            synchronized (lock) {
                transcript.addAll(past);
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the conversation history", e);
        }
    }

    /** The language of the last question heard in the transcript (today's, restored, or since), {@code null} when none. */
    String lastHeardLanguage() {
        synchronized (lock) {
            var it = transcript.descendingIterator();
            while (it.hasNext()) {
                ConversationEntry e = it.next();
                if ("heard".equals(e.kind()) && e.data().get("language") instanceof String l && !l.isBlank()) {
                    return l;
                }
            }
        }
        return null;
    }

    void addListener(VoiceListener l) {
        listeners.add(l);
    }

    List<ConversationEntry> recent(long since) {
        synchronized (lock) {
            return transcript.stream().filter(e -> e.id() > since).sorted(Comparator.comparingLong(ConversationEntry::id)).toList();
        }
    }

    ConversationEntry entry(String kind, double t, String text, Map<String, Object> data) {
        ConversationEntry e = new ConversationEntry(ids.getAndIncrement(), t, kind, text, data);
        synchronized (lock) {
            transcript.addLast(e);
            while (transcript.size() > VoiceService.HISTORY) {
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

    void note(String text) {
        entry("note", clocks.wallSeconds(), text, Map.of());
    }

    void publish(String kind, Map<String, Object> payload) {
        for (VoiceListener l : listeners) {
            try {
                l.onVoice(kind, payload);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "voice listener failed", e);
            }
        }
    }

    /** A live signal for the app: its key-value pairs and the time. */
    Map<String, Object> live(String kind, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        m.put("t", clocks.wallSeconds());
        publish(kind, m);
        return m;
    }

    /** The ears' diagnostics: the last {@link VoiceService#HEARINGS} kept in memory for the app, each passed on as it comes. */
    void onHearing(VoiceSidecar.Hearing h) {
        Map<String, Object> m = h.toMap();
        synchronized (hearings) {
            hearings.addLast(m);
            while (hearings.size() > VoiceService.HEARINGS) {
                hearings.removeFirst();
            }
        }
        publish("hearing", m);
    }

    List<Map<String, Object>> hearings() {
        synchronized (hearings) {
            return List.copyOf(hearings);
        }
    }
}
