// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import marvin.host.domain.shared.TokenEstimator;

/**
 * The soul's texts: their limits (about 150 tokens each, docs/design.md 8.1), their statements (one per line or sentence,
 * what a diff compares), and how they sit in the system prompt.
 */
public final class SoulText {
    /** The core's limit, in tokens. */
    public static final int CORE_TOKENS = 150;
    /** The learned part's limit, in tokens: a hard limit, checked by the guardrails. */
    public static final int LEARNED_TOKENS = 150;
    /** The soul is stored in English (the owner's decision of 14 October 2026): Qwen's English ratio. */
    public static final TokenEstimator TOKENS = new TokenEstimator(4.0, 0.10);
    /** The most characters a part may be sent as, whatever its tokens: a bound on what the app sends. */
    public static final int MAX_CHARS = 2000;

    /** The core Marvin starts with, until the owner writes their own (the character of today's persona). */
    public static final String DEFAULT_CORE = "Calm, grown-up, dry humour. Calls the owner by their first name. Answers in "
            + "the language it is spoken to in. Never pretends to sense what it cannot.";

    private SoulText() {
    }

    public static int tokens(String text) {
        return TOKENS.estimate(text == null ? "" : text.strip());
    }

    /** Spaces and line ends tidied: lines stripped, blank lines dropped. */
    public static String normalise(String text) {
        if (text == null) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String l = line.strip().replaceAll("[ \\t]+", " ");
            if (l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ")) {
                l = l.substring(2).strip();
            }
            if (!l.isEmpty()) {
                out.add(l);
            }
        }
        return String.join("\n", out);
    }

    /** The statements of a text: its lines, each split into sentences. */
    public static List<String> statements(String text) {
        List<String> out = new ArrayList<>();
        for (String line : normalise(text).split("\n")) {
            for (String s : line.split("(?<=[.!?])\\s+(?=\\p{Lu}|\\p{N}|[\"“«(])")) {
                if (!s.isBlank()) {
                    out.add(s.strip());
                }
            }
        }
        return out;
    }

    /** A statement as compared: lower case, without its closing punctuation and repeated spaces. */
    static String key(String statement) {
        return statement.strip().toLowerCase(Locale.ROOT).replaceAll("[.!?;:,]+$", "").replaceAll("\\s+", " ");
    }

    /**
     * The soul in the system prompt, right after the rules: the core, then the learned part when there is one. Empty
     * when both are.
     */
    public static String prompt(String core, String learned) {
        String c = normalise(core);
        String l = normalise(learned);
        StringBuilder b = new StringBuilder();
        if (!c.isEmpty()) {
            b.append("Your character, as your owner wrote it:\n").append(c);
        }
        if (!l.isEmpty()) {
            if (!b.isEmpty()) {
                b.append("\n\n");
            }
            b.append("What you have learned about how to be with your owner (they approved it):\n").append(l);
        }
        return b.toString();
    }
}
