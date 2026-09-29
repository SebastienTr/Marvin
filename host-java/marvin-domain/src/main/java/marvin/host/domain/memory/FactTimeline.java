// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Facts on a time axis, for the app's timeline (docs/memory.md, "Seeing memory"). Each fact is a bar from when it
 * became true ({@code valid_from}, else when memory learned it) to when it stopped ({@code valid_to}, else when memory
 * stopped believing it, {@code expired_at}), open while it still holds. Facts that follow one another share a lane:
 * the wordings of one fact ({@code superseded_by}), and a fact the world ended followed by the one that replaced it
 * ("lives in Nice", then "lives in Lille": the same subject and kind, written by the same plan, so that the new fact
 * was learned at the very instant the old one expired; only when exactly one fact fits).
 */
public final class FactTimeline {

    /**
     * A fact's bar.
     *
     * @param end       {@code null}: still holds (drawn to now)
     * @param startKind {@code valid_from}, {@code learned} or {@code reworded} (a new wording, from when it was written)
     * @param endKind   {@code valid_to}, {@code expired}, {@code replaced} (a newer wording) or {@code open}
     * @param next      the fact that follows it in its lane, or {@code null}
     * @param nextKind  {@code reworded} or {@code then}, or {@code null}
     */
    public record Bar(Fact fact, Instant start, Instant end, String startKind, String endKind, UUID next, String nextKind) {

        /** Whether the bar meets {@code [from, to)}, an open bar lasting until {@code now}. */
        public boolean overlaps(Instant from, Instant to, Instant now) {
            Instant e = end != null ? end : now;
            return start.isBefore(to) && !e.isBefore(from);
        }
    }

    /** Facts that follow one another, oldest first; {@code subject} is the first one's. */
    public record Lane(String subject, List<Bar> bars) {
        public Instant start() {
            return bars.getFirst().start();
        }
    }

    private FactTimeline() {
    }

    /** The lanes of these facts, the earliest first. */
    public static List<Lane> lanes(List<Fact> facts) {
        Map<UUID, Fact> byId = new LinkedHashMap<>();
        facts.forEach(f -> byId.put(f.id(), f));
        Map<UUID, UUID> next = new HashMap<>();
        Map<UUID, String> nextKind = new HashMap<>();
        Map<UUID, UUID> previous = new HashMap<>();
        for (Fact f : facts) {
            if (f.supersededBy() != null && byId.containsKey(f.supersededBy()) && !previous.containsKey(f.supersededBy())) {
                link(f.id(), f.supersededBy(), "reworded", next, nextKind, previous);
            }
        }
        for (Fact f : facts) {
            if (next.containsKey(f.id()) || f.supersededBy() != null || f.expiredAt() == null || f.validTo() == null) {
                continue;
            }
            List<Fact> after = facts.stream().filter(o -> o != f && o.learnedAt().equals(f.expiredAt())
                    && !previous.containsKey(o.id())
                    && MemoryGraph.nodeId(o.subject()).equals(MemoryGraph.nodeId(f.subject())) && o.kind() == f.kind()).toList();
            if (after.size() == 1) {
                link(f.id(), after.getFirst().id(), "then", next, nextKind, previous);
            }
        }
        List<Lane> lanes = new ArrayList<>();
        for (Fact f : facts) {
            if (previous.containsKey(f.id())) {
                continue;
            }
            List<Bar> bars = new ArrayList<>();
            Fact at = f;
            String startKind = at.validFrom() != null ? "valid_from" : "learned";
            Instant start = at.validFrom() != null ? at.validFrom() : at.learnedAt();
            while (at != null && bars.size() <= facts.size()) {
                UUID n = next.get(at.id());
                Instant end;
                String endKind;
                if (at.supersededBy() != null) {
                    end = at.expiredAt() != null ? at.expiredAt() : at.learnedAt();
                    endKind = "replaced";
                } else if (at.validTo() != null) {
                    end = at.validTo();
                    endKind = "valid_to";
                } else if (at.expiredAt() != null) {
                    end = at.expiredAt();
                    endKind = "expired";
                } else {
                    end = null;
                    endKind = "open";
                }
                if (end != null && end.isBefore(start)) {
                    end = start;
                }
                bars.add(new Bar(at, start, end, startKind, endKind, n, n == null ? null : nextKind.get(at.id())));
                Fact following = n == null ? null : byId.get(n);
                if (following != null) {
                    if ("reworded".equals(nextKind.get(at.id()))) {
                        start = following.learnedAt().isBefore(start) ? start : following.learnedAt();
                        startKind = "reworded";
                    } else {
                        start = following.validFrom() != null ? following.validFrom() : following.learnedAt();
                        startKind = following.validFrom() != null ? "valid_from" : "learned";
                    }
                }
                at = following;
            }
            lanes.add(new Lane(f.subject(), List.copyOf(bars)));
        }
        lanes.sort(Comparator.comparing(Lane::start).thenComparing(l -> l.bars().getFirst().fact().statement().toLowerCase(Locale.ROOT)));
        return List.copyOf(lanes);
    }

    private static void link(UUID from, UUID to, String kind, Map<UUID, UUID> next, Map<UUID, String> nextKind,
                             Map<UUID, UUID> previous) {
        next.put(from, to);
        nextKind.put(from, kind);
        previous.put(to, from);
    }
}
