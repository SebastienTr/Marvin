// SPDX-License-Identifier: MIT
package marvin.host.application.system.port.out;

import java.util.Map;

/**
 * Where the first-run setup keeps what only the owner can say: a step skipped, the speaker heard, the quiet hours
 * answered. One small JSON-like object per step (Boolean, Number, String, Map, List values).
 */
public interface SetupStore {

    /** Every step's stored state, by step id (steps never touched are absent). */
    Map<String, Map<String, Object>> load();

    /** Replaces one step's stored state. */
    void save(String step, Map<String, Object> state);
}
