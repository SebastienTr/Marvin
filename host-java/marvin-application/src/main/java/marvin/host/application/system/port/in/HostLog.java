// SPDX-License-Identifier: MIT
package marvin.host.application.system.port.in;

import java.util.List;
import java.util.Map;
import java.util.Set;

import marvin.host.domain.system.LogEntry;

/** The app's Log panel: the last lines from the brain, the devices, the host and the voice. */
public interface HostLog {

    /** Adds a line now. */
    LogEntry add(String source, String level, String text, Map<String, Object> extra);

    /** Adds a line with its own time (wall clock, Unix seconds). */
    LogEntry add(String source, String level, String text, double ts, Map<String, Object> extra);

    /** Newest first: at most {@code limit}, with {@code id > since}, from these sources (all when empty). */
    List<LogEntry> entries(int limit, Set<String> sources, long since);
}
