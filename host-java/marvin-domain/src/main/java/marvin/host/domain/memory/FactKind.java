// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.Locale;

/** What a fact is about (docs/design.md 5.4). */
public enum FactKind {
    PREFERENCE, RELATION, PLAN, HABIT, BIOGRAPHICAL, STATE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A stored or model-given name; {@link #STATE} when it is unknown. */
    public static FactKind parse(String s) {
        if (s != null) {
            for (FactKind k : values()) {
                if (k.wire().equals(s.strip().toLowerCase(Locale.ROOT))) {
                    return k;
                }
            }
        }
        return STATE;
    }
}
