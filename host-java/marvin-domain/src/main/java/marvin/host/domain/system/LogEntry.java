// SPDX-License-Identifier: MIT
package marvin.host.domain.system;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of the app's Log panel: the brain's events, the devices' log lines and connections, the
 * host's warnings, the voice's notes.
 *
 * @param id     growing, from 1 at start
 * @param ts     wall clock, Unix seconds
 * @param source {@code brain}, {@code device}, {@code host} or {@code voice}
 * @param level  {@code info}, {@code attention}, {@code warning} or {@code error}
 * @param extra  more keys for the app ({@code kind}, {@code device}, {@code logger})
 */
public record LogEntry(long id, double ts, String source, String level, String text, Map<String, Object> extra) {

    public LogEntry {
        extra = Collections.unmodifiableMap(new LinkedHashMap<>(extra == null ? Map.of() : extra));
    }
}
