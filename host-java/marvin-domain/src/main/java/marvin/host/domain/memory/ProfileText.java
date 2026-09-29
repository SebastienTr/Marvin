// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import marvin.host.domain.shared.TokenEstimator;

/**
 * The profile block's rules (docs/design.md 5.2, step 5): one statement per line, a hard size limit, and the
 * owner's lines kept verbatim by every rewrite.
 */
public final class ProfileText {

    /** Words too common in profile lines to say two lines are about the same thing. */
    private static final Set<String> COMMON = Set.of("the", "owner", "and", "for", "with", "that", "this", "has", "have",
            "are", "was", "his", "her", "its", "their", "from", "who", "likes", "lives");

    private ProfileText() {
    }

    /** A rewrite once the rules are applied: the text, its estimated size, the model's lines cut to fit. */
    public record Enforced(String text, int tokens, List<String> dropped) {
    }

    /** The non-blank lines, stripped. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String l : text.split("\\R")) {
            String s = l.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The owner's kept lines first, verbatim and in their order, then the model's lines (bullets removed, headings and
     * duplicates of kept lines dropped), cut from the end until the whole fits in {@code maxTokens}. Kept lines are
     * never cut, even when they alone exceed the limit.
     */
    public static Enforced enforce(String modelText, List<String> kept, int maxTokens, TokenEstimator est) {
        List<String> keptLines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String k : kept) {
            String s = k.strip();
            if (!s.isEmpty() && seen.add(norm(s))) {
                keptLines.add(s);
            }
        }
        List<String> model = new ArrayList<>();
        for (String l : lines(modelText)) {
            String s = l.replaceFirst("^(?:[-*•]|\\d+[.)])\\s+", "").strip();
            if (s.isEmpty() || s.startsWith("#") || s.endsWith(":") && s.length() < 40) {
                continue;
            }
            if (seen.add(norm(s))) {
                model.add(s);
            }
        }
        List<String> dropped = new ArrayList<>();
        while (!model.isEmpty() && est.estimate(join(keptLines, model)) > maxTokens) {
            dropped.addFirst(model.removeLast());
        }
        String text = join(keptLines, model);
        return new Enforced(text, est.estimate(text), dropped);
    }

    /**
     * The lines to keep verbatim after the owner edits the profile: those already kept that are still there, and
     * every line the owner added or changed (not in the previous text).
     */
    public static List<String> keptAfterOwnerEdit(String previous, List<String> previousKept, String edited) {
        Set<String> before = new LinkedHashSet<>();
        for (String l : lines(previous)) {
            before.add(norm(l));
        }
        Set<String> wasKept = new LinkedHashSet<>();
        for (String l : previousKept) {
            wasKept.add(norm(l));
        }
        List<String> out = new ArrayList<>();
        for (String l : lines(edited)) {
            if (wasKept.contains(norm(l)) || !before.contains(norm(l))) {
                out.add(l);
            }
        }
        return out;
    }

    /** A line of a diff between two versions. */
    public record DiffLine(char op, String text) {
    }

    /** A line diff ({@code ' '} same, {@code '-'} removed, {@code '+'} added), longest common subsequence. */
    public static List<DiffLine> diff(String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<DiffLine> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new DiffLine(' ', a.get(i++)));
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new DiffLine('-', a.get(i++)));
            } else {
                out.add(new DiffLine('+', b.get(j++)));
            }
        }
        while (i < a.size()) {
            out.add(new DiffLine('-', a.get(i++)));
        }
        while (j < b.size()) {
            out.add(new DiffLine('+', b.get(j++)));
        }
        return out;
    }

    /**
     * The text without the lines that state {@code statement} (a forgotten fact): a line that contains it, or shares at
     * least half of its words (words of 3 letters or more). Returns the text unchanged when no line matches.
     */
    public static String withoutLinesLike(String text, String statement) {
        Set<String> words = words(statement);
        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (String l : lines(text)) {
            Set<String> lw = words(l);
            long common = words.stream().filter(lw::contains).count();
            boolean like = norm(l).contains(norm(statement))
                    || !words.isEmpty() && common * 2 >= words.size() && (common >= 2 || words.size() == 1);
            if (like) {
                changed = true;
            } else {
                out.add(l);
            }
        }
        return changed ? String.join("\n", out) : text;
    }

    private static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : norm(s).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !COMMON.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    private static String join(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return String.join("\n", all);
    }

    private static String norm(String s) {
        return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
