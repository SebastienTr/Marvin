// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One entry of the conversation (store.py {@code conversation}, schema 2).
 *
 * @param id   milliseconds, growing across restarts; the same id again replaces the entry
 * @param t    wall clock, Unix seconds
 * @param kind {@code heard}, {@code reply}, {@code ignored} or {@code note}
 * @param data the rest of the entry, JSON-like values in their original order (latency, the context the
 *             model was given, why an utterance was not answered ...)
 */
public record ConversationEntry(long id, double t, String kind, String text, Map<String, Object> data) {

    /** The kinds of entries. */
    public static final List<String> KINDS = List.of("heard", "reply", "ignored", "note");

    public ConversationEntry {
        Objects.requireNonNull(kind, "kind");
        text = text == null ? "" : text;
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data == null ? Map.of() : data));
    }

    /** As the app receives it: the data first, then {@code id}, {@code t}, {@code kind}, {@code text}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>(data);
        m.remove("id");
        m.remove("t");
        m.remove("kind");
        m.remove("text");
        m.put("id", id);
        m.put("t", t);
        m.put("kind", kind);
        m.put("text", text);
        return m;
    }

    /** As the voice publishes it live: {@code id}, {@code t}, {@code kind}, {@code text}, then the data. */
    public Map<String, Object> toLiveMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("t", t);
        m.put("kind", kind);
        m.put("text", text);
        data.forEach((k, v) -> {
            if (!m.containsKey(k)) {
                m.put(k, v);
            }
        });
        return m;
    }
}
