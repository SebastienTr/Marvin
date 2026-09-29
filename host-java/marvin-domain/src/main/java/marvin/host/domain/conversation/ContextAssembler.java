// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import marvin.host.domain.shared.TokenEstimator;

/**
 * The budgets of the question's volatile block (docs/design.md 5.3). Each section has a hard token budget; when its
 * items do not all fit, the lowest-scored go first, never the last ones by position. What is kept is rendered in the
 * section's own order.
 *
 * <p>An item costs its line ({@code "- " + text + "\n"}); a section that keeps anything also pays for its heading.
 * Items are considered best first and skipped when they do not fit, so a long low-value line never pushes out two
 * short useful ones.
 */
public final class ContextAssembler {

    /**
     * The voice path's budgets (tokens), design 5.3. The "now" section has 200 rather than the design's 120: the
     * brain's context is up to about 280 estimated tokens before calibration (230 after), and its most important line
     * after the clock, the one saying the sensors are simulated, alone takes about 100; with 120 it would be cut, and
     * Marvin would give simulated readings as real ones.
     */
    public static final int NOW_BUDGET = 200;
    public static final int GIST_BUDGET = 150;
    public static final int FACTS_BUDGET = 300;

    /**
     * A candidate line.
     *
     * @param key    what it is (a fact id, {@code time}, {@code seated} ...)
     * @param detail what the reply inspector shows about it (scores, dates)
     */
    public record Item(String key, String text, double score, Map<String, Object> detail) {
        public Item {
            text = text == null ? "" : text;
            detail = detail == null ? Map.of() : detail;
        }

        public static Item of(String key, String text, double score) {
            return new Item(key, text, score, Map.of());
        }
    }

    /** A section's candidates, in the order they are shown. */
    public record Section(String name, String heading, int budget, List<Item> items) {
        public Section {
            heading = heading == null ? "" : heading;
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    /**
     * What a section kept.
     *
     * @param tokens estimated tokens of what is kept, heading included
     */
    public record Cut(Section section, List<Item> kept, List<Item> dropped, int tokens) {

        public boolean isEmpty() {
            return kept.isEmpty();
        }

        public List<String> lines() {
            return kept.stream().map(Item::text).toList();
        }

        /** For the reply inspector. */
        public Map<String, Object> report() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", section.name());
            m.put("budget", section.budget());
            m.put("tokens", tokens);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Item i : kept) {
                items.add(itemReport(i, true));
            }
            for (Item i : dropped) {
                items.add(itemReport(i, false));
            }
            m.put("items", items);
            return m;
        }

        private static Map<String, Object> itemReport(Item i, boolean kept) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", i.key());
            m.put("text", i.text());
            m.put("score", Math.round(i.score() * 1000) / 1000.0);
            m.put("kept", kept);
            m.putAll(i.detail());
            return m;
        }
    }

    private ContextAssembler() {
    }

    /** The tokens a line costs. */
    public static int lineTokens(String text, TokenEstimator est) {
        return est.estimate("- " + text + "\n");
    }

    /** Cuts a section to its budget by score. */
    public static Cut cut(Section s, TokenEstimator est) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < s.items().size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> s.items().get(i).score()).reversed().thenComparing(i -> i));
        int heading = s.heading().isEmpty() ? 0 : est.estimate(s.heading() + "\n");
        int used = 0;
        boolean[] keep = new boolean[s.items().size()];
        for (int i : order) {
            Item it = s.items().get(i);
            if (it.text().isBlank()) {
                continue;
            }
            int cost = lineTokens(it.text(), est) + (used == 0 ? heading : 0);
            if (used + cost <= s.budget()) {
                keep[i] = true;
                used += cost;
            }
        }
        List<Item> kept = new ArrayList<>();
        List<Item> dropped = new ArrayList<>();
        for (int i = 0; i < keep.length; i++) {
            (keep[i] ? kept : dropped).add(s.items().get(i));
        }
        return new Cut(s, kept, dropped, used);
    }

    /** A cut section as text: the heading, then one {@code "- "} line per item kept; empty when nothing is kept. */
    public static String render(Cut c) {
        if (c.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        if (!c.section().heading().isEmpty()) {
            lines.add(c.section().heading());
        }
        for (Item i : c.kept()) {
            lines.add("- " + i.text());
        }
        return String.join("\n", lines);
    }
}
