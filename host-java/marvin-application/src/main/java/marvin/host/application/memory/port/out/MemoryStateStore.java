// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.util.Map;

/** Memory's own small state: the worker's progress, the backfills done, the owner's memory settings. */
public interface MemoryStateStore {

    /** The value of a key (a JSON object), empty when there is none. */
    Map<String, Object> get(String key);

    void put(String key, Map<String, Object> value);
}
