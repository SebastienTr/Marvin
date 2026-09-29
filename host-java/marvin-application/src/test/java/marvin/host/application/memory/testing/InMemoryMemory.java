// SPDX-License-Identifier: MIT
package marvin.host.application.memory.testing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.MemoryStateStore;
import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.memory.Vectors;
import marvin.host.domain.shared.Clocks;

/**
 * Memory's stores in memory, with the SQL stores' semantics (checked against them by the persistence tests), for
 * the application's tests and the evaluation set. Not thread-safe beyond one writer at a time.
 */
public final class InMemoryMemory {
    public final Log log = new Log();
    public final Facts facts = new Facts();
    public final Episodes episodes = new Episodes();
    public final Profiles profiles = new Profiles();
    public final State state = new State();

    /** A settable wall clock. */
    public static final class Clock implements Clocks {
        public volatile double wall;
        public volatile double mono;

        public Clock(double wall) {
            this.wall = wall;
        }

        public Clock(Instant at) {
            this(at.getEpochSecond() + at.getNano() / 1e9);
        }

        public void advance(double seconds) {
            wall += seconds;
            mono += seconds;
        }

        @Override
        public double wallSeconds() {
            return wall;
        }

        @Override
        public double monotonicSeconds() {
            return mono;
        }
    }

    public final class Log implements EventLog {
        public final Map<Long, MemoryEvent> rows = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        public volatile RuntimeException failure;

        @Override
        public synchronized int append(List<MemoryEvent> drafts) {
            if (failure != null) {
                throw failure;
            }
            int n = 0;
            for (MemoryEvent d : drafts) {
                if (appendOne(d).isPresent()) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public synchronized Optional<MemoryEvent> appendOne(MemoryEvent d) {
            if (d.externalRef() != null && rows.values().stream()
                    .anyMatch(e -> e.source().equals(d.source()) && d.externalRef().equals(e.externalRef()))) {
                return Optional.empty();
            }
            long id = ids.incrementAndGet();
            Sensitivity s = d.sensitivity() == Sensitivity.SECRET ? Sensitivity.SENSITIVE : d.sensitivity();
            MemoryEvent e = new MemoryEvent(id, d.ts(), d.ts(), d.source(), d.kind(), s, d.externalRef(), d.body(), d.data(), null);
            rows.put(id, e);
            return Optional.of(e);
        }

        @Override
        public synchronized List<MemoryEvent> unconsolidated(int limit) {
            return rows.values().stream().filter(e -> e.consolidatedAt() == null).limit(limit).toList();
        }

        @Override
        public synchronized long unconsolidatedCount() {
            return rows.values().stream().filter(e -> e.consolidatedAt() == null).count();
        }

        @Override
        public synchronized void markConsolidated(Collection<Long> ids, Instant at) {
            for (Long id : ids) {
                MemoryEvent e = rows.get(id);
                if (e != null) {
                    rows.put(id, new MemoryEvent(e.id(), e.ts(), e.recordedAt(), e.source(), e.kind(), e.sensitivity(),
                            e.externalRef(), e.body(), e.data(), at));
                }
            }
        }

        @Override
        public synchronized List<MemoryEvent> between(Instant from, Instant to, int limit) {
            return rows.values().stream().filter(e -> !e.ts().isBefore(from) && e.ts().isBefore(to))
                    .sorted(Comparator.comparing(MemoryEvent::ts).thenComparing(MemoryEvent::id)).limit(limit).toList();
        }

        @Override
        public synchronized List<MemoryEvent> byIds(Collection<Long> ids) {
            return rows.values().stream().filter(e -> ids.contains(e.id())).toList();
        }

        @Override
        public synchronized List<MemoryEvent> recent(String query, long beforeId, int limit) {
            return rows.values().stream().filter(e -> beforeId == 0 || e.id() < beforeId)
                    .filter(e -> query.isBlank() || e.body().toLowerCase().contains(query.strip().toLowerCase()))
                    .sorted(Comparator.comparingLong(MemoryEvent::id).reversed()).limit(limit).toList();
        }

        @Override
        public synchronized Optional<Instant> first() {
            return rows.values().stream().map(MemoryEvent::ts).min(Comparator.naturalOrder());
        }

        @Override
        public synchronized void redact(long id, String body) {
            MemoryEvent e = rows.get(id);
            if (e != null) {
                rows.put(id, e.withBody(body));
            }
        }

        @Override
        public synchronized int delete(Collection<Long> ids) {
            int n = 0;
            for (Long id : ids) {
                if (rows.remove(id) != null) {
                    n++;
                    facts.unlink(id);
                }
            }
            return n;
        }

        @Override
        public synchronized int deleteBetween(Instant from, Instant to) {
            return delete(between(from, to, Integer.MAX_VALUE).stream().map(MemoryEvent::id).toList());
        }

        @Override
        public synchronized int deleteUnreferenced(String source, Instant before) {
            Set<Long> used = facts.referenced();
            return delete(rows.values().stream().filter(e -> e.source().equals(source) && e.ts().isBefore(before)
                    && !used.contains(e.id())).map(MemoryEvent::id).toList());
        }

        @Override
        public synchronized long count() {
            return rows.size();
        }

        @Override
        public synchronized List<MemoryEvent> page(long afterId, int limit) {
            return rows.values().stream().filter(e -> e.id() > afterId).limit(limit).toList();
        }

        @Override
        public synchronized void deleteAll() {
            delete(new ArrayList<>(rows.keySet()));
        }
    }

    public final class Facts implements FactStore {
        public final Map<UUID, Fact> rows = new LinkedHashMap<>();
        public final Map<UUID, float[]> vectors = new LinkedHashMap<>();
        public int dims = 1024;

        synchronized void unlink(long event) {
            rows.replaceAll((id, f) -> f.sources().contains(event)
                    ? f.withSources(f.sources().stream().filter(s -> s != event).toList()) : f);
        }

        synchronized Set<Long> referenced() {
            Set<Long> out = new HashSet<>();
            rows.values().forEach(f -> out.addAll(f.sources()));
            return out;
        }

        private List<Long> existing(List<Long> ids) {
            return ids.stream().filter(log.rows::containsKey).toList();
        }

        @Override
        public synchronized void apply(Reconciliation.Plan plan, Map<UUID, float[]> embeddings) {
            for (Fact f : plan.added()) {
                rows.put(f.id(), f.withSources(existing(f.sources())));
                vectors.put(f.id(), embeddings.get(f.id()));
            }
            for (Reconciliation.Expiry x : plan.expired()) {
                Fact f = rows.get(x.id());
                if (f != null) {
                    rows.put(x.id(), f.withExpiry(x.expiredAt(), x.validTo(), x.supersededBy()));
                }
            }
            plan.moreSources().forEach((id, s) -> {
                Fact f = rows.get(id);
                if (f != null) {
                    LinkedHashSet<Long> all = new LinkedHashSet<>(f.sources());
                    all.addAll(existing(s));
                    rows.put(id, f.withSources(all.stream().sorted().toList()));
                }
            });
        }

        private static boolean allowed(Fact f, Filter filter) {
            if (filter.currentOnly() && !f.current(filter.now())) {
                return false;
            }
            if (!filter.includeArchived() && f.archived()) {
                return false;
            }
            return f.sensitivity().ordinal() <= filter.maxSensitivity().ordinal();
        }

        @Override
        public synchronized List<Scored> nearest(float[] embedding, int k, Filter filter) {
            return rows.values().stream().filter(f -> vectors.get(f.id()) != null && allowed(f, filter))
                    .map(f -> new Scored(f, Vectors.cosine(embedding, vectors.get(f.id()))))
                    .sorted(Comparator.comparingDouble(Scored::similarity).reversed()).limit(k).toList();
        }

        @Override
        public synchronized Optional<Fact> get(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        private boolean matches(Fact f, Query q) {
            if (q.text() != null && !q.text().isBlank() && !(f.statement() + " " + f.subject()).toLowerCase()
                    .contains(q.text().strip().toLowerCase())) {
                return false;
            }
            if (q.subject() != null && !q.subject().isBlank() && !q.subject().equals(f.subject())) {
                return false;
            }
            if (q.kind() != null && !q.kind().isBlank() && !q.kind().equals(f.kind().wire())) {
                return false;
            }
            String v = q.validity() == null ? "current" : q.validity();
            if ("current".equals(v) && !f.current(q.now())) {
                return false;
            }
            if ("past".equals(v) && (f.current(q.now()) || f.supersededBy() != null)) {
                return false;
            }
            if (q.sensitivity() != null && q.sensitivity() != f.sensitivity()) {
                return false;
            }
            if (q.archived() != null && q.archived() != f.archived()) {
                return false;
            }
            if (q.reviewed() != null && q.reviewed() != f.reviewed()) {
                return false;
            }
            return q.pinned() == null || q.pinned() == f.pinned();
        }

        @Override
        public synchronized List<Fact> list(Query q) {
            return rows.values().stream().filter(f -> matches(f, q))
                    .sorted(Comparator.comparing(Fact::learnedAt).reversed()).skip(q.offset()).limit(q.limit()).toList();
        }

        @Override
        public synchronized long count(Query q) {
            return rows.values().stream().filter(f -> matches(f, q)).count();
        }

        @Override
        public synchronized List<Fact> asOf(Instant world, Instant known, int limit) {
            return rows.values().stream().filter(f -> !f.learnedAt().isAfter(known)
                            && (f.expiredAt() == null || f.expiredAt().isAfter(known) || f.supersededBy() == null)
                            && (f.validFrom() == null || !f.validFrom().isAfter(world))
                            && (f.validTo() == null || f.validTo().isAfter(world)
                            || f.supersededBy() == null && f.expiredAt() != null && f.expiredAt().isAfter(known)))
                    .sorted(Comparator.comparing(Fact::learnedAt).reversed()).limit(limit).toList();
        }

        @Override
        public synchronized List<Fact> versions(UUID id) {
            Set<UUID> chain = new LinkedHashSet<>();
            if (!rows.containsKey(id)) {
                return List.of();
            }
            List<UUID> todo = new ArrayList<>(List.of(id));
            while (!todo.isEmpty()) {
                UUID x = todo.removeLast();
                if (!chain.add(x)) {
                    continue;
                }
                Fact f = rows.get(x);
                if (f != null && f.supersededBy() != null) {
                    todo.add(f.supersededBy());
                }
                rows.values().stream().filter(o -> x.equals(o.supersededBy())).forEach(o -> todo.add(o.id()));
            }
            return chain.stream().map(rows::get).sorted(Comparator.comparing(Fact::learnedAt)).toList();
        }

        @Override
        public synchronized List<Fact> learnedSince(Instant since) {
            return rows.values().stream().filter(f -> !f.learnedAt().isBefore(since) && f.expiredAt() == null).toList();
        }

        @Override
        public synchronized List<Fact> endedSince(Instant since) {
            return rows.values().stream().filter(f -> f.expiredAt() != null && !f.expiredAt().isBefore(since)
                    && f.supersededBy() == null).toList();
        }

        @Override
        public synchronized List<Fact> decayable(Instant now) {
            return rows.values().stream().filter(f -> f.current(now) && !f.archived() && !f.pinned()).toList();
        }

        @Override
        public synchronized void setArchived(Collection<UUID> ids, boolean archived) {
            ids.forEach(id -> rows.computeIfPresent(id, (k, f) -> f.withArchived(archived)));
        }

        @Override
        public synchronized void setPinned(UUID id, boolean pinned) {
            rows.computeIfPresent(id, (k, f) -> f.withPinned(pinned));
        }

        @Override
        public synchronized void setReviewed(Collection<UUID> ids, Instant at) {
            ids.forEach(id -> rows.computeIfPresent(id, (k, f) -> f.withReviewed(at)));
        }

        @Override
        public synchronized void touch(Collection<UUID> ids, Instant at) {
            ids.forEach(id -> rows.computeIfPresent(id, (k, f) -> f.withUse(at, f.useCount() + 1)));
        }

        @Override
        public synchronized int delete(Collection<UUID> ids) {
            int n = 0;
            for (UUID id : ids) {
                if (rows.remove(id) != null) {
                    vectors.remove(id);
                    n++;
                }
            }
            rows.replaceAll((k, f) -> f.supersededBy() != null && ids.contains(f.supersededBy())
                    ? f.withExpiry(f.expiredAt(), f.validTo(), null) : f);
            return n;
        }

        @Override
        public synchronized int deleteOrphans() {
            return delete(rows.values().stream().filter(f -> f.sources().isEmpty()).map(Fact::id).toList());
        }

        @Override
        public synchronized List<Fact> withoutEmbedding(int limit) {
            return rows.values().stream().filter(f -> vectors.get(f.id()) == null).limit(limit).toList();
        }

        @Override
        public synchronized void setEmbedding(UUID id, float[] embedding) {
            vectors.put(id, embedding);
        }

        @Override
        public String searchMode() {
            return "in memory";
        }

        @Override
        public int dimensions() {
            return dims;
        }

        @Override
        public synchronized void deleteAll() {
            rows.clear();
            vectors.clear();
        }

        /** The current facts' statements, oldest first. */
        public synchronized List<String> currentStatements(Instant now) {
            return rows.values().stream().filter(f -> f.current(now)).map(Fact::statement).toList();
        }
    }

    public static final class Episodes implements EpisodeStore {
        public final Map<String, Episode> rows = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        private static String key(EpisodeLevel l, LocalDate d) {
            return l + " " + d;
        }

        @Override
        public synchronized Optional<Episode> get(EpisodeLevel level, LocalDate day) {
            return Optional.ofNullable(rows.get(key(level, day)));
        }

        @Override
        public synchronized Episode put(Episode e, float[] embedding) {
            Episode old = rows.get(key(e.level(), e.day()));
            Episode s = new Episode(old != null ? old.id() : ids.incrementAndGet(), e.level(), e.day(), e.periodStart(), e.periodEnd(),
                    e.summary(), false, e.createdAt(), e.events());
            rows.put(key(e.level(), e.day()), s);
            return s;
        }

        @Override
        public synchronized List<Episode> list(EpisodeLevel level, LocalDate from, LocalDate to) {
            return rows.values().stream().filter(e -> e.level() == level && !e.day().isBefore(from) && e.day().isBefore(to))
                    .sorted(Comparator.comparing(Episode::day)).toList();
        }

        @Override
        public synchronized Optional<Episode> latest(EpisodeLevel level) {
            return rows.values().stream().filter(e -> e.level() == level).max(Comparator.comparing(Episode::day));
        }

        @Override
        public synchronized int markStale(Instant from, Instant to) {
            int n = 0;
            for (var en : rows.entrySet()) {
                Episode e = en.getValue();
                if (e.periodStart().isBefore(to) && e.periodEnd().isAfter(from)) {
                    en.setValue(new Episode(e.id(), e.level(), e.day(), e.periodStart(), e.periodEnd(), e.summary(), true,
                            e.createdAt(), e.events()));
                    n++;
                }
            }
            return n;
        }

        @Override
        public synchronized List<Episode> stale() {
            return rows.values().stream().filter(Episode::stale).sorted(Comparator.comparing(Episode::day)).toList();
        }

        @Override
        public synchronized void deleteAll() {
            rows.clear();
        }
    }

    public static final class Profiles implements ProfileStore {
        public final List<BlockVersion> rows = new ArrayList<>();

        @Override
        public synchronized Optional<BlockVersion> active(Block block) {
            return rows.stream().filter(v -> v.block() == block && v.status() == BlockVersion.Status.ACTIVE).findFirst();
        }

        @Override
        public synchronized BlockVersion add(BlockVersion v) {
            if (v.status() == BlockVersion.Status.ACTIVE) {
                rows.replaceAll(o -> o.block() == v.block() && o.status() == BlockVersion.Status.ACTIVE
                        ? new BlockVersion(o.id(), o.block(), o.content(), o.tokens(), BlockVersion.Status.SUPERSEDED, o.rationale(),
                        o.evidence(), o.author(), o.createdAt(), v.createdAt(), o.keptLines()) : o);
            }
            BlockVersion s = new BlockVersion(rows.size() + 1, v.block(), v.content(), v.tokens(), v.status(), v.rationale(),
                    v.evidence(), v.author(), v.createdAt(), v.decidedAt(), v.keptLines());
            rows.add(s);
            return s;
        }

        @Override
        public synchronized Optional<BlockVersion> get(long id) {
            return rows.stream().filter(v -> v.id() == id).findFirst();
        }

        @Override
        public synchronized List<BlockVersion> versions(Block block, int limit) {
            return rows.stream().filter(v -> v.block() == block).sorted(Comparator.comparingLong(BlockVersion::id).reversed())
                    .limit(limit).toList();
        }

        @Override
        public synchronized void deleteAll() {
            rows.clear();
        }
    }

    public static final class State implements MemoryStateStore {
        public final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        @Override
        public synchronized Map<String, Object> get(String key) {
            return new LinkedHashMap<>(rows.getOrDefault(key, Map.of()));
        }

        @Override
        public synchronized void put(String key, Map<String, Object> value) {
            rows.put(key, new LinkedHashMap<>(value));
        }
    }
}
