// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A proposal as the owner reads it (docs/design.md 8.2): the learned part's statements kept, added and removed, in order
 * (a longest common subsequence over the statements, compared without case and closing punctuation).
 */
public final class SoulDiff {
    public static final String KEEP = "keep";
    public static final String ADD = "add";
    public static final String REMOVE = "remove";

    /** One statement and what happens to it. */
    public record Op(String op, String text) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", op);
            m.put("text", text);
            return m;
        }
    }

    private SoulDiff() {
    }

    public static List<Op> of(String before, String after) {
        List<String> a = SoulText.statements(before);
        List<String> b = SoulText.statements(after);
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = SoulText.key(a.get(i)).equals(SoulText.key(b.get(j))) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Op> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (SoulText.key(a.get(i)).equals(SoulText.key(b.get(j)))) {
                out.add(new Op(KEEP, b.get(j)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Op(REMOVE, a.get(i++)));
            } else {
                out.add(new Op(ADD, b.get(j++)));
            }
        }
        while (i < n) {
            out.add(new Op(REMOVE, a.get(i++)));
        }
        while (j < m) {
            out.add(new Op(ADD, b.get(j++)));
        }
        return out;
    }

    /** Whether anything changes. */
    public static boolean changes(List<Op> ops) {
        return ops.stream().anyMatch(o -> !KEEP.equals(o.op()));
    }
}
