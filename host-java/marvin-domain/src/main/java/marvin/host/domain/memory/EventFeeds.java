// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How the other contexts' records become events of the log, and their sensitivity labels, set here by rule at
 * the source (docs/design.md 5.7) rather than by a model. Only plain values come in: memory knows the other
 * contexts' kinds by name, not their types.
 */
public final class EventFeeds {
    /** The host's own markers in the presence history: not about the owner, not remembered. */
    static final List<String> HOST_MARKERS = List.of("host_started", "host_stopped", "robot_offline", "robot_online");
    /** Brain events about the body: sensitive by rule. */
    static final List<String> VITALS = List.of("vitals_acquired", "vitals_lost");
    /** What memory keeps of a reply's data (not the context or prompt sent, which the conversation keeps). */
    static final List<String> REPLY_KEYS = List.of("language", "source", "interrupted", "proactive", "error", "tools", "model");

    private EventFeeds() {
    }

    /** A line of the conversation; empty for kinds memory does not keep ({@code ignored}, {@code note}). */
    public static Optional<MemoryEvent> conversation(long entryId, double t, String kind, String text,
                                                     Map<String, Object> data) {
        if (!"heard".equals(kind) && !"reply".equals(kind)) {
            return Optional.empty();
        }
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : REPLY_KEYS) {
            if (data != null && data.containsKey(k) && data.get(k) != null) {
                d.put(k, data.get(k));
            }
        }
        return Optional.of(MemoryEvent.draft(instant(t), MemorySources.CONVERSATION, kind, Sensitivity.NORMAL,
                conversationRef(entryId), Redaction.redact(text.strip()), d));
    }

    /** The log's reference to a line of the conversation. */
    public static String conversationRef(long entryId) {
        return "conversation:" + entryId;
    }

    /** A stored brain event; empty for the host's own markers. {@code text}: the sentence the app shows for it. */
    public static Optional<MemoryEvent> presence(long eventId, double ts, String kind, String text,
                                                 Map<String, Object> data) {
        if (HOST_MARKERS.contains(kind)) {
            return Optional.empty();
        }
        boolean vitals = VITALS.contains(kind) || data != null
                && (data.containsKey("heart_rate") || data.containsKey("breath_rate"));
        return Optional.of(MemoryEvent.draft(instant(ts), MemorySources.BRAIN, kind,
                vitals ? Sensitivity.SENSITIVE : Sensitivity.NORMAL, "presence:" + eventId, text, data));
    }

    /** Unix seconds as an instant, to the microsecond (what PostgreSQL keeps). */
    public static Instant instant(double unixSeconds) {
        long micros = Math.round(unixSeconds * 1e6);
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
    }
}
