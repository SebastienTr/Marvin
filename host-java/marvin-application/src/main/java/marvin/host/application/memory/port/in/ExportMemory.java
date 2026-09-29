// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.util.List;
import java.util.Map;

/** Everything memory holds, for the owner to take away (docs/design.md 2.6). */
public interface ExportMemory {

    /**
     * @param json     machine-readable: every table, every field (JSON-like values)
     * @param markdown readable: the profile, the facts with their sources, the episodes
     */
    record Export(Map<String, List<Map<String, Object>>> json, String markdown) {
    }

    Export export();
}
