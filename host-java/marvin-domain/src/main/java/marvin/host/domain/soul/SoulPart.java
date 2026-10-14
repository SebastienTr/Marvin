// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.util.Locale;

/** The two parts of the soul (docs/design.md 8.1). */
public enum SoulPart {
    /** Written and changed by the owner only. */
    CORE,
    /** Proposed by the weekly reflection, changed once the owner approves (or by the owner's own edit). */
    LEARNED;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException not {@code core} or {@code learned} */
    public static SoulPart of(String id) {
        if (id == null) {
            throw new IllegalArgumentException("part must be core or learned");
        }
        return switch (id.strip().toLowerCase(Locale.ROOT)) {
            case "core" -> CORE;
            case "learned" -> LEARNED;
            default -> throw new IllegalArgumentException("part must be core or learned");
        };
    }
}
