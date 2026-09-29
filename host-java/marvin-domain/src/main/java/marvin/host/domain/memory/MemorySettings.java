// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner's memory settings: what is collected into memory (a switch per source, docs/design.md 2.3), which
 * models consolidate it, and when.
 *
 * @param collectConversation remember what is said (the conversation itself is kept by the conversation either way)
 * @param collectBrain        remember what the brain noticed (arrivals, breaks, vital signs)
 * @param worker              consolidate at all (idle and nightly passes)
 * @param memoryModel         the model of the idle pass; empty: the voice's model (already loaded)
 * @param nightModel          the model of the nightly pass; empty: the memory model (a larger one is better at night)
 * @param embedModel          the embedding model, one for everything (changing it means re-embedding)
 * @param nightHour           the local hour of the nightly pass (or the first idle hour after it)
 * @param idleMinutes         minutes without conversation before an idle pass
 * @param retentionDays       brain events older than this are reduced to their day summaries
 */
public record MemorySettings(boolean collectConversation, boolean collectBrain, boolean worker, String memoryModel,
                             String nightModel, String embedModel, int nightHour, int idleMinutes, int retentionDays) {

    public static final MemorySettings DEFAULTS = new MemorySettings(true, true, true, "", "", "bge-m3", 3, 10, 365);

    /** The keys, as the app and the store name them. */
    public static final List<String> KEYS = List.of("collect_conversation", "collect_brain", "worker", "memory_model",
            "night_model", "embed_model", "night_hour", "idle_minutes", "retention_days");

    /** A setting that does not validate. */
    public static final class Invalid extends IllegalArgumentException {
        public Invalid(String message) {
            super(message);
        }
    }

    public MemorySettings {
        memoryModel = memoryModel == null ? "" : memoryModel.strip();
        nightModel = nightModel == null ? "" : nightModel.strip();
        embedModel = embedModel == null || embedModel.isBlank() ? "bge-m3" : embedModel.strip();
    }

    /** Whether events of this source are kept (the owner's own are always). */
    public boolean collects(String source) {
        return switch (source) {
            case MemorySources.CONVERSATION -> collectConversation;
            case MemorySources.BRAIN -> collectBrain;
            default -> true;
        };
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collect_conversation", collectConversation);
        m.put("collect_brain", collectBrain);
        m.put("worker", worker);
        m.put("memory_model", memoryModel);
        m.put("night_model", nightModel);
        m.put("embed_model", embedModel);
        m.put("night_hour", nightHour);
        m.put("idle_minutes", idleMinutes);
        m.put("retention_days", retentionDays);
        return m;
    }

    /** These settings with the given keys changed; throws {@link Invalid} with a message for the owner. */
    public MemorySettings with(Map<String, ?> update) {
        MemorySettings s = this;
        for (var e : update.entrySet()) {
            Object v = e.getValue();
            s = switch (e.getKey()) {
                case "collect_conversation" -> new MemorySettings(bool(e.getKey(), v), s.collectBrain, s.worker, s.memoryModel,
                        s.nightModel, s.embedModel, s.nightHour, s.idleMinutes, s.retentionDays);
                case "collect_brain" -> new MemorySettings(s.collectConversation, bool(e.getKey(), v), s.worker, s.memoryModel,
                        s.nightModel, s.embedModel, s.nightHour, s.idleMinutes, s.retentionDays);
                case "worker" -> new MemorySettings(s.collectConversation, s.collectBrain, bool(e.getKey(), v), s.memoryModel,
                        s.nightModel, s.embedModel, s.nightHour, s.idleMinutes, s.retentionDays);
                case "memory_model" -> new MemorySettings(s.collectConversation, s.collectBrain, s.worker, model(e.getKey(), v, true),
                        s.nightModel, s.embedModel, s.nightHour, s.idleMinutes, s.retentionDays);
                case "night_model" -> new MemorySettings(s.collectConversation, s.collectBrain, s.worker, s.memoryModel,
                        model(e.getKey(), v, true), s.embedModel, s.nightHour, s.idleMinutes, s.retentionDays);
                case "embed_model" -> new MemorySettings(s.collectConversation, s.collectBrain, s.worker, s.memoryModel,
                        s.nightModel, model(e.getKey(), v, false), s.nightHour, s.idleMinutes, s.retentionDays);
                case "night_hour" -> new MemorySettings(s.collectConversation, s.collectBrain, s.worker, s.memoryModel,
                        s.nightModel, s.embedModel, integer(e.getKey(), v, 0, 23), s.idleMinutes, s.retentionDays);
                case "idle_minutes" -> new MemorySettings(s.collectConversation, s.collectBrain, s.worker, s.memoryModel,
                        s.nightModel, s.embedModel, s.nightHour, integer(e.getKey(), v, 1, 240), s.retentionDays);
                case "retention_days" -> new MemorySettings(s.collectConversation, s.collectBrain, s.worker, s.memoryModel,
                        s.nightModel, s.embedModel, s.nightHour, s.idleMinutes, integer(e.getKey(), v, 7, 36_500));
                default -> throw new Invalid("unknown memory setting: " + e.getKey());
            };
        }
        return s;
    }

    /** Stored settings read leniently: what does not validate keeps its default. */
    public static MemorySettings fromStored(Map<String, ?> stored) {
        MemorySettings s = DEFAULTS;
        if (stored == null) {
            return s;
        }
        for (var e : stored.entrySet()) {
            try {
                s = s.with(Map.of(e.getKey(), e.getValue()));
            } catch (Invalid | NullPointerException ignored) {
                // a value from an older version: the default stands
            }
        }
        return s;
    }

    private static boolean bool(String key, Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new Invalid(key + " must be true or false");
    }

    private static int integer(String key, Object v, int min, int max) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            long l = n.longValue();
            if (l >= min && l <= max) {
                return (int) l;
            }
        }
        throw new Invalid(key + " must be a whole number from " + min + " to " + max);
    }

    private static String model(String key, Object v, boolean emptyOk) {
        if (v instanceof String s && s.strip().length() <= 200 && !s.contains("\n") && (emptyOk || !s.isBlank())) {
            return s.strip();
        }
        throw new Invalid(key + (emptyOk ? " must be a model name, or empty" : " must be a model name"));
    }
}
