// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import marvin.host.application.memory.port.out.MemoryStateStore;
import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.MemoryText;
import marvin.host.domain.memory.ProfileText;
import marvin.host.domain.shared.TokenEstimator;

/**
 * Statements the owner forgot (or corrected, archived, made sensitive) that the profile may still say in other
 * words. The lines that repeat them word for word go at once; the others need the model: the statements wait in
 * memory's state ({@code profile_removals}) for the next profile rewrite, which removes every line stating them,
 * and are then dropped. That is the only place a forgotten statement is kept, and only until that rewrite (the next
 * idle pass).
 */
final class ProfileRemovals {
    static final String KEY = "profile_removals";
    private static final Set<String> NOT_A_CLUE = Set.of("owner", "marvin", "user");

    private ProfileRemovals() {
    }

    static List<String> pending(MemoryStateStore state) {
        Object o = state.get(KEY).get("statements");
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    static void add(MemoryStateStore state, List<String> statements) {
        Set<String> all = new LinkedHashSet<>(pending(state));
        statements.stream().filter(s -> s != null && !s.isBlank()).forEach(all::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statements", new ArrayList<>(all));
        state.put(KEY, m);
    }

    static void clear(MemoryStateStore state) {
        state.put(KEY, Map.of());
    }

    /**
     * The lines of {@code previous} that are gone from {@code next} and share a content word with a removed
     * statement: the reworded lines the model took out for it.
     */
    static List<String> removedFor(String previous, String next, List<String> removed) {
        Set<String> clues = new LinkedHashSet<>();
        removed.forEach(r -> clues.addAll(MemoryText.words(r)));
        clues.removeAll(NOT_A_CLUE);
        List<String> nextLines = ProfileText.lines(next);
        List<String> out = new ArrayList<>();
        for (String l : ProfileText.lines(previous)) {
            if (!nextLines.contains(l) && MemoryText.words(l).stream().anyMatch(clues::contains)) {
                out.add(l);
            }
        }
        return out;
    }

    /** Takes lines out of every stored version but the active one (whose text is already the new one). */
    static void purgeHistory(ProfileStore profiles, TokenEstimator tokens, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        for (BlockVersion v : profiles.versions(Block.PROFILE, Integer.MAX_VALUE)) {
            if (v.status() == BlockVersion.Status.ACTIVE) {
                continue;
            }
            List<String> kept = ProfileText.lines(v.content()).stream().filter(l -> !lines.contains(l)).toList();
            if (kept.size() != ProfileText.lines(v.content()).size()) {
                String text = String.join("\n", kept);
                profiles.redact(v.id(), text, tokens.estimate(text), v.keptLines().stream().filter(kept::contains).toList());
            }
        }
    }

    /** Takes the lines like {@code statement} out of every stored version (the active one included); returns whether any changed. */
    static boolean redactHistory(ProfileStore profiles, TokenEstimator tokens, String statement) {
        boolean changed = false;
        for (BlockVersion v : profiles.versions(Block.PROFILE, Integer.MAX_VALUE)) {
            String text = ProfileText.withoutLinesLike(v.content(), statement);
            if (!text.equals(v.content())) {
                List<String> lines = ProfileText.lines(text);
                profiles.redact(v.id(), text, tokens.estimate(text), v.keptLines().stream().filter(l -> lines.contains(l.strip())).toList());
                changed = true;
            }
        }
        return changed;
    }
}
