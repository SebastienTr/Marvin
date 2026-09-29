// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import marvin.host.application.memory.port.out.MemoryStateStore;
import marvin.host.domain.memory.MemoryEvent;

/**
 * What the owner forgot from the log, so that a catch-up of the other contexts' records never brings it back: the
 * time ranges forgotten and the references of single events forgotten (times and ids only, never content), in
 * memory's state ({@code forgotten_feed}).
 */
final class ForgottenFeed {
    static final String KEY = "forgotten_feed";

    private final List<double[]> ranges;
    private final Set<String> refs;

    private ForgottenFeed(List<double[]> ranges, Set<String> refs) {
        this.ranges = ranges;
        this.refs = refs;
    }

    static ForgottenFeed read(MemoryStateStore state) {
        Map<String, Object> m = state.get(KEY);
        List<double[]> ranges = new ArrayList<>();
        if (m.get("ranges") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof List<?> r && r.size() == 2 && r.get(0) instanceof Number a && r.get(1) instanceof Number b) {
                    ranges.add(new double[] {a.doubleValue(), b.doubleValue()});
                }
            }
        }
        Set<String> refs = new LinkedHashSet<>();
        if (m.get("refs") instanceof List<?> l) {
            l.forEach(x -> refs.add(String.valueOf(x)));
        }
        return new ForgottenFeed(ranges, refs);
    }

    boolean covers(MemoryEvent e) {
        if (e.externalRef() != null && refs.contains(e.source() + " " + e.externalRef())) {
            return true;
        }
        double t = seconds(e.ts());
        for (double[] r : ranges) {
            if (t >= r[0] && t < r[1]) {
                return true;
            }
        }
        return false;
    }

    static void range(MemoryStateStore state, Instant from, Instant to) {
        ForgottenFeed f = read(state);
        f.ranges.add(new double[] {seconds(from), seconds(to)});
        f.write(state);
    }

    static void event(MemoryStateStore state, MemoryEvent e) {
        if (e.externalRef() == null) {
            return;
        }
        ForgottenFeed f = read(state);
        f.refs.add(e.source() + " " + e.externalRef());
        f.write(state);
    }

    private void write(MemoryStateStore state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ranges", ranges.stream().map(r -> List.of(r[0], r[1])).toList());
        m.put("refs", new ArrayList<>(refs));
        state.put(KEY, m);
    }

    private static double seconds(Instant t) {
        return t.getEpochSecond() + t.getNano() / 1e9;
    }
}
