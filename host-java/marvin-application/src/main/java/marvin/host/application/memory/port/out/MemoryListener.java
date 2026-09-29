// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.util.Map;

/** Told when memory changes (the worker's state, a pass's report, an edit), for the app's live view. */
public interface MemoryListener {

    /** @param kind {@code worker} (state and last report) or {@code changed} (facts, profile, episodes) */
    void onMemory(String kind, Map<String, Object> payload);
}
