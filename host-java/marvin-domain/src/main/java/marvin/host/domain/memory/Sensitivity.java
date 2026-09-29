// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.Locale;

/**
 * How private an event or a fact is (docs/design.md 2.4). {@code sensitive}: health (vital signs included),
 * finances, third parties' private matters; never auto-retrieved while someone else is in the room, never sent
 * to a cloud model, never spoken proactively. {@code secret}: credentials and codes, never stored.
 */
public enum Sensitivity {
    NORMAL, PERSONAL, SENSITIVE, SECRET;

    /** The stored name. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A stored or model-given name; {@code fallback} when it is none of the four. */
    public static Sensitivity parse(String s, Sensitivity fallback) {
        if (s == null) {
            return fallback;
        }
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "normal" -> NORMAL;
            case "personal", "private" -> PERSONAL;
            case "sensitive" -> SENSITIVE;
            case "secret" -> SECRET;
            default -> fallback;
        };
    }

    /** The stricter of the two. */
    public Sensitivity atLeast(Sensitivity other) {
        return other != null && other.ordinal() > ordinal() ? other : this;
    }
}
