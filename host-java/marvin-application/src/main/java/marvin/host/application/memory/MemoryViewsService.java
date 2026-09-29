// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.in.VisualiseMemory;
import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactTimeline;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemoryGraph;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.memory.Projection;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * The app's four pictures of memory ({@link VisualiseMemory}), read-only, from the stores as they are: nothing here
 * writes, calls a model or computes an embedding. The meaning map's projection is computed here (the vectors never
 * leave the host) and cached for the last fact set.
 */
public final class MemoryViewsService implements VisualiseMemory {
    /** At most this many facts in the graph and on the map, and lanes on the timeline. */
    static final int MAX_FACTS = 2000;
    static final int MAX_NODES = 60;
    static final int MAX_LANES = 400;

    private final FactStore facts;
    private final EventLog events;
    private final EpisodeStore episodes;
    private final ProfileStore profiles;
    private final ConsolidateMemory worker;
    private final LocalDays days;
    private final Clocks clocks;

    private record Cached(String version, MapView view) {
    }

    private volatile Cached cached;

    public MemoryViewsService(FactStore facts, EventLog events, EpisodeStore episodes, ProfileStore profiles,
                              ConsolidateMemory worker, LocalDays days, Clocks clocks) {
        this.facts = facts;
        this.events = events;
        this.episodes = episodes;
        this.profiles = profiles;
        this.worker = worker;
        this.days = days;
        this.clocks = clocks;
    }

    private Instant now() {
        return EventFeeds.instant(clocks.wallSeconds());
    }

    private static FactStore.Query query(String validity, Boolean archived, Boolean pinned, Boolean reviewed,
                                         Sensitivity s, Instant now, int limit) {
        return new FactStore.Query("", "", "", validity, s, archived, pinned, reviewed, now, limit, 0);
    }

    // ------------------------------------------------------------------ graph

    @Override
    public GraphView graph() {
        Instant now = now();
        // every fact once, in its latest wording: current, archived and past ones (the past drawn faded)
        List<Fact> all = facts.list(query("all", null, null, null, null, now, MAX_FACTS)).stream()
                .filter(f -> f.supersededBy() == null).toList();
        return new GraphView(MemoryGraph.build(all, now, MAX_NODES), all);
    }

    // ------------------------------------------------------------------ meaning map

    @Override
    public MapView map() {
        Instant now = now();
        List<Fact> current = facts.list(query("current", false, null, null, null, now, MAX_FACTS));
        Map<UUID, float[]> vectors = facts.embeddings(current.stream().map(Fact::id).toList());
        int dims = facts.dimensions();
        List<Fact> placed = new ArrayList<>();
        List<Fact> unplaced = new ArrayList<>();
        for (Fact f : current) {
            float[] v = vectors.get(f.id());
            (v != null && v.length == dims ? placed : unplaced).add(f);
        }
        placed.sort(Comparator.comparing(Fact::id));
        String version = version(placed, unplaced);
        Cached c = cached;
        if (c != null && c.version().equals(version)) {
            // same facts and vectors: the same projection, with the facts as they are now (a pin, a review)
            Map<UUID, Fact> byId = new java.util.HashMap<>();
            current.forEach(f -> byId.put(f.id(), f));
            List<Point> points = c.view().points().stream()
                    .map(p -> new Point(byId.getOrDefault(p.fact().id(), p.fact()), p.x(), p.y())).toList();
            return new MapView(points, List.copyOf(unplaced), c.view().explained(), version);
        }
        Projection.Result r = Projection.pca2(placed.stream().map(f -> vectors.get(f.id())).toList());
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < placed.size(); i++) {
            points.add(new Point(placed.get(i), r.points()[i][0], r.points()[i][1]));
        }
        MapView view = new MapView(List.copyOf(points), List.copyOf(unplaced), r.explained(), version);
        cached = new Cached(version, view);
        return view;
    }

    /** The fact set's version: which facts are placed, which are not (an embedding written changes it). */
    static String version(List<Fact> placed, List<Fact> unplaced) {
        long h = 1125899906842597L;
        for (Fact f : placed) {
            h = 31 * h + f.id().hashCode();
        }
        h = 31 * h + 7;
        for (Fact f : unplaced.stream().sorted(Comparator.comparing(Fact::id)).toList()) {
            h = 31 * h + f.id().hashCode();
        }
        return placed.size() + "-" + unplaced.size() + "-" + Long.toHexString(h);
    }

    // ------------------------------------------------------------------ timeline

    @Override
    public TimelineView timeline(Range range) {
        Instant now = now();
        List<Fact> all = facts.list(query("all", null, null, null, null, now, MAX_FACTS * 2));
        List<FactTimeline.Lane> lanes = FactTimeline.lanes(all);
        Instant to = now;
        Instant from = switch (range) {
            case WEEK -> now.minus(Duration.ofDays(7));
            case MONTH -> now.minus(Duration.ofDays(31));
            case YEAR -> now.minus(Duration.ofDays(366));
            case ALL -> lanes.stream().map(FactTimeline.Lane::start).min(Comparator.naturalOrder())
                    .orElse(now.minus(Duration.ofDays(7)));
        };
        if (!from.isBefore(to)) {
            from = to.minus(Duration.ofDays(1));
        }
        Instant f0 = from;
        List<FactTimeline.Lane> shown = lanes.stream()
                .filter(l -> l.bars().stream().anyMatch(b -> b.overlaps(f0, to, now))).toList();
        boolean truncated = shown.size() > MAX_LANES;
        if (truncated) {
            shown = shown.subList(shown.size() - MAX_LANES, shown.size());
        }
        LocalDate first = from.atZone(days.zone()).toLocalDate();
        LocalDate last = to.atZone(days.zone()).toLocalDate().plusDays(1);
        if (first.isBefore(last.minusDays(800))) {
            first = last.minusDays(800);    // at most two years of days and weeks in one picture
        }
        List<Episode> eps = new ArrayList<>();
        eps.addAll(episodes.list(EpisodeLevel.WEEK, first.minusDays(7), last));
        eps.addAll(episodes.list(EpisodeLevel.DAY, first, last));
        return new TimelineView(range, from, to, List.copyOf(shown), List.copyOf(eps), truncated);
    }

    // ------------------------------------------------------------------ flow

    @Override
    public FlowView flow() {
        Instant now = now();
        List<Source> sources = new ArrayList<>();
        Map<String, EventLog.SourceCount> bySource = new java.util.LinkedHashMap<>();
        events.sources().forEach(s -> bySource.put(s.source(), s));
        for (String s : MemorySources.ALL) {
            EventLog.SourceCount c = bySource.remove(s);
            sources.add(new Source(s, c == null ? 0 : c.events(), c == null ? 0 : c.waiting()));
        }
        bySource.values().forEach(c -> sources.add(new Source(c.source(), c.events(), c.waiting())));

        long stored = facts.count(query("all", null, null, null, null, now, 1));
        long currentAll = facts.count(query("current", null, null, null, null, now, 1));
        long current = facts.count(query("current", false, null, null, null, now, 1));
        long past = facts.count(query("past", null, null, null, null, now, 1));
        long pinned = facts.count(query("current", null, true, null, null, now, 1));
        long suggested = facts.count(query("current", false, null, false, null, now, 1));
        long sensitive = facts.count(query("current", null, null, null, Sensitivity.SENSITIVE, now, 1));
        long forgotten = 0;
        for (MemoryEvent e : events.ofKind(MemorySources.OWNER, MemorySources.FORGET, 5000)) {
            forgotten += e.data().get("facts") instanceof Number n ? n.longValue() : 0;
        }
        FactCounts counts = new FactCounts(stored, current, currentAll - current, pinned, suggested, sensitive,
                Math.max(0, stored - currentAll - past), past, forgotten);

        List<BlockVersion> versions = profiles.versions(Block.PROFILE, 10_000);
        BlockVersion active = profiles.active(Block.PROFILE).orElse(null);
        LocalDate far = LocalDate.of(1970, 1, 1);
        LocalDate end = now.atZone(days.zone()).toLocalDate().plusDays(1);
        Written written = new Written(versions.size(), active == null ? null : active.id(),
                active == null ? null : active.createdAt(), episodes.list(EpisodeLevel.DAY, far, end).size(),
                episodes.list(EpisodeLevel.WEEK, far, end).size(), episodes.list(EpisodeLevel.MONTH, far, end).size());
        return new FlowView(List.copyOf(sources), counts, written, worker.status(), worker.recent());
    }
}
