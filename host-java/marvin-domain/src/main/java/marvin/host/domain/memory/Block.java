// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.Locale;

/** The labelled blocks always in the system prompt (docs/design.md 5.1, Letta's core memory). */
public enum Block {
    PROFILE(500), SOUL_CORE(150), SOUL_LEARNED(150);

    /** The hard size limit, in estimated tokens. */
    private final int maxTokens;

    Block(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Block parse(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }
}
