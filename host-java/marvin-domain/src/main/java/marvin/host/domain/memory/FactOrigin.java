// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.Locale;

/** Who wrote a fact: the memory worker from the log, the owner (in the app, or "remember that ..."), a task. */
public enum FactOrigin {
    EXTRACTED, OWNER, TASK;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FactOrigin parse(String s) {
        for (FactOrigin o : values()) {
            if (o.wire().equals(s)) {
                return o;
            }
        }
        return EXTRACTED;
    }
}
