// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Something the brain noticed (events.py {@code Event}).
 *
 * @param tUs    the device clock, microseconds
 * @param detail a short note for people, English
 * @param data   numbers that go with it ({@code distance_m}, {@code breath_rate}, {@code heart_rate},
 *               {@code seated_s}), in a stable order
 */
public record PresenceEvent(EventKind kind, long tUs, String detail, Map<String, Double> data) {

    public PresenceEvent {
        Objects.requireNonNull(kind, "kind");
        detail = detail == null ? "" : detail;
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data == null ? Map.of() : data));
    }
}
