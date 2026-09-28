// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.history;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An event as stored (stats.py {@code StoredEvent}): the host's wall clock (Unix seconds) when it was
 * received, its kind as a string (a brain event's wire name, or one of {@link HistoryKinds}), a detail
 * for people, and the data that came with it (numbers, or booleans for {@code robot_online}).
 *
 * @param id the store's id, growing; 0 before it is stored
 */
public record StoredEvent(double ts, String kind, String detail, Map<String, Object> data, long id) {

    public StoredEvent {
        Objects.requireNonNull(kind, "kind");
        detail = detail == null ? "" : detail;
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data == null ? Map.of() : data));
    }

    public StoredEvent(double ts, String kind, String detail, Map<String, Object> data) {
        this(ts, kind, detail, data, 0);
    }

    /** The sentence the app shows for it. */
    public String text() {
        return Words.describe(this);
    }

    /** A number in {@link #data}, or {@code null}. */
    Double number(String key) {
        Object v = data.get(key);
        if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;                       // Python: bool is an int
        }
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /** A truthy value in {@link #data}. */
    boolean flag(String key) {
        Object v = data.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return v != null;
    }
}
