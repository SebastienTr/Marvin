// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One row of the event log, the source of truth of memory (docs/design.md 5.2): what Marvin heard and
 * answered, what the brain noticed, what the owner changed.
 *
 * @param id             the log's id, growing; 0 before it is stored
 * @param ts             when it happened (world time)
 * @param recordedAt     when the log got it; {@code null} before it is stored
 * @param source         {@link MemorySources}
 * @param kind           {@code heard}, {@code reply}, {@code sat_down}, {@code edit} ...
 * @param externalRef    the original's id ({@code conversation:<id>}, {@code presence:<id>}), unique per source:
 *                       the same event fed twice is kept once
 * @param body           the human-readable content, when there is one
 * @param data           the structured payload (JSON-like values, in order)
 * @param consolidatedAt the last memory pass that read it; {@code null} while none did
 */
public record MemoryEvent(long id, Instant ts, Instant recordedAt, String source, String kind, Sensitivity sensitivity,
                          String externalRef, String body, Map<String, Object> data, Instant consolidatedAt) {

    public MemoryEvent {
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        sensitivity = sensitivity == null ? Sensitivity.NORMAL : sensitivity;
        body = body == null ? "" : body;
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data == null ? Map.of() : data));
    }

    /** An event to append. */
    public static MemoryEvent draft(Instant ts, String source, String kind, Sensitivity sensitivity, String externalRef,
                                    String body, Map<String, Object> data) {
        return new MemoryEvent(0, ts, null, source, kind, sensitivity, externalRef, body, data, null);
    }

    /** The same event with the body redacted (a secret was found in it). */
    public MemoryEvent withBody(String newBody) {
        return new MemoryEvent(id, ts, recordedAt, source, kind, sensitivity, externalRef, newBody, data, consolidatedAt);
    }
}
