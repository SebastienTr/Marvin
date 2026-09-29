// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import marvin.host.application.memory.port.out.FactStore;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.FactOrigin;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.memory.Vectors;

/**
 * Facts in {@code memory.fact} and {@code memory.fact_source}. With pgvector the nearest facts come from the HNSW
 * index (cosine distance); without it (the embedded PostgreSQL) the filter picks the candidate ids and their vectors,
 * cached in memory after their first read, are compared in Java (exact; about a millisecond for 3000 facts).
 */
@Component
public class JdbcFactStore implements FactStore {
    static final String COLUMNS = "f.id, f.subject, f.statement, f.kind, f.importance, f.confidence, f.sensitivity, "
            + "f.valid_from, f.valid_to, f.learned_at, f.expired_at, f.superseded_by, f.last_used_at, f.use_count, f.archived, "
            + "f.pinned, f.origin, f.extracted_by, f.reviewed_at, "
            + "ARRAY(SELECT s.event_id FROM memory.fact_source s WHERE s.fact_id = f.id ORDER BY s.event_id) AS sources";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MemoryRows.Vectors vectors;
    /** Without pgvector: the facts' vectors, read once (an embedding never changes once written). */
    private final java.util.concurrent.ConcurrentHashMap<UUID, float[]> cache;

    public JdbcFactStore(JdbcClient jdbc, TransactionTemplate tx, ContextMigrations migrated,
                         @Value("${marvin.memory.embedding-dimensions:1024}") int dimensions) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.vectors = MemoryRows.Vectors.of(jdbc, "fact", dimensions);
        this.cache = vectors.pgvector() ? null : new java.util.concurrent.ConcurrentHashMap<>();
        if (cache != null) {
            // read the vectors once in the background, so that the first question does not pay for it
            Thread.ofVirtual().name("memory-vectors").start(() -> {
                try {
                    jdbc.sql("SELECT id, embedding FROM memory.fact WHERE embedding IS NOT NULL").query((rs, n) -> {
                        cache.putIfAbsent(rs.getObject("id", UUID.class), floats(rs.getArray("embedding")));
                        return 0;
                    }).list();
                } catch (RuntimeException e) {
                    // read on demand instead
                }
            });
        }
    }

    private static float[] floats(java.sql.Array a) throws SQLException {
        Object[] xs = (Object[]) a.getArray();
        float[] v = new float[xs.length];
        for (int i = 0; i < xs.length; i++) {
            v[i] = xs[i] == null ? 0f : ((Number) xs[i]).floatValue();
        }
        return v;
    }

    @Override
    public void apply(List<Reconciliation.Plan> plans, Map<UUID, float[]> embeddings) {
        tx.executeWithoutResult(status -> {
            for (Reconciliation.Plan plan : plans) {
                // ends first: a plan's new version must not be written when the fact it replaces changed meanwhile
                for (Reconciliation.Expiry x : plan.expired()) {
                    int n = jdbc.sql("UPDATE memory.fact SET expired_at = ?, valid_to = ?, superseded_by = ? "
                                    + "WHERE id = ? AND expired_at IS NULL")
                            .params(MemoryRows.at(x.expiredAt()), MemoryRows.at(x.validTo()), null, x.id()).update();
                    if (n != 1) {
                        throw new Conflict("fact " + x.id() + " was changed or forgotten meanwhile");
                    }
                }
                for (Fact f : plan.added()) {
                    jdbc.sql("INSERT INTO memory.fact (id, subject, statement, kind, importance, confidence, sensitivity, valid_from, "
                                    + "valid_to, learned_at, expired_at, superseded_by, last_used_at, use_count, archived, pinned, origin, "
                                    + "extracted_by, reviewed_at, embedding) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS "
                                    + vectors.type() + "))")
                            .params(f.id(), f.subject(), f.statement(), f.kind().wire(), f.importance(), (float) f.confidence(),
                                    stored(f.sensitivity()), MemoryRows.at(f.validFrom()), MemoryRows.at(f.validTo()),
                                    MemoryRows.at(f.learnedAt()), MemoryRows.at(f.expiredAt()), f.supersededBy(),
                                    MemoryRows.at(f.lastUsedAt()), f.useCount(), f.archived(), f.pinned(), f.origin().wire(),
                                    f.extractedBy(), MemoryRows.at(f.reviewedAt()), vectors.literal(embeddings.get(f.id())))
                            .update();
                    if (!f.sources().isEmpty() && link(f.id(), f.sources()) == 0) {
                        throw new Conflict("the sources of a new fact were forgotten meanwhile");
                    }
                }
                // the successor exists now: point the ended facts to it
                for (Reconciliation.Expiry x : plan.expired()) {
                    if (x.supersededBy() != null) {
                        jdbc.sql("UPDATE memory.fact SET superseded_by = ? WHERE id = ?").params(x.supersededBy(), x.id()).update();
                    }
                }
                plan.moreSources().forEach(this::link);
            }
        });
        if (cache != null) {
            plans.forEach(p -> p.added().forEach(f -> {
                float[] v = embeddings.get(f.id());
                if (v != null) {
                    cache.put(f.id(), v);
                }
            }));
        }
    }

    /** Links a fact to events that still exist (one may have been forgotten meanwhile). */
    private int link(UUID fact, List<Long> events) {
        if (events.isEmpty()) {
            return 0;
        }
        return jdbc.sql("INSERT INTO memory.fact_source (fact_id, event_id) SELECT ?, e.id FROM memory.event_log e "
                            + "WHERE e.id = ANY(?) ON CONFLICT DO NOTHING")
                .params(fact, events.toArray(Long[]::new)).update();
    }

    private static String stored(Sensitivity s) {
        return (s == Sensitivity.SECRET ? Sensitivity.SENSITIVE : s).wire();
    }

    private static final class Where {
        final StringBuilder sql = new StringBuilder(" WHERE true");
        final List<Object> params = new ArrayList<>();

        Where and(String clause, Object... ps) {
            sql.append(" AND ").append(clause);
            params.addAll(List.of(ps));
            return this;
        }
    }

    private static Where filter(Filter f) {
        Where w = new Where();
        if (f.currentOnly()) {
            w.and("f.expired_at IS NULL AND (f.valid_to IS NULL OR f.valid_to > ?)", MemoryRows.at(f.now()));
        }
        if (!f.includeArchived()) {
            w.and("NOT f.archived");
        }
        List<String> allowed = new ArrayList<>();
        for (Sensitivity s : Sensitivity.values()) {
            if (s.ordinal() <= f.maxSensitivity().ordinal() && s != Sensitivity.SECRET) {
                allowed.add(s.wire());
            }
        }
        w.and("f.sensitivity = ANY(?)", (Object) allowed.toArray(String[]::new));
        return w;
    }

    /**
     * The nearest facts: the index (or the exact scan) finds the {@code k} ids first; only those rows are then read
     * whole, so the sources of thousands of candidates are never gathered.
     */
    @Override
    public List<Scored> nearest(float[] embedding, int k, Filter filter) {
        Where w = filter(filter).and("f.embedding IS NOT NULL");
        Map<UUID, Double> found = new java.util.LinkedHashMap<>();
        if (vectors.pgvector()) {
            String q = "CAST(? AS public.vector)";
            List<Object> ps = new ArrayList<>();
            ps.add(vectors.literal(embedding));
            ps.addAll(w.params);
            ps.add(vectors.literal(embedding));
            ps.add(k);
            // pgvector 0.8 filters after the index scan; the iterative scan keeps scanning until k rows pass the filter
            // (older versions take the setting as an unknown placeholder and ignore it). Relaxed order: sorted here.
            tx.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
                jdbc.sql("SELECT f.id, 1 - (f.embedding OPERATOR(public.<=>) " + q + ") AS similarity FROM memory.fact f"
                                + w.sql + " ORDER BY f.embedding OPERATOR(public.<=>) " + q + " LIMIT ?")
                        .params(ps).query((rs, n) -> found.put(rs.getObject("id", UUID.class), rs.getDouble("similarity"))).list();
            });
        } else {
            // the embedded database: the filter picks the ids, their vectors come from the cache (read once)
            List<UUID> ids = jdbc.sql("SELECT f.id FROM memory.fact f" + w.sql).params(w.params)
                    .query((rs, n) -> rs.getObject("id", UUID.class)).list();
            List<UUID> missing = ids.stream().filter(id -> !cache.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                jdbc.sql("SELECT id, embedding FROM memory.fact WHERE id = ANY(?) AND embedding IS NOT NULL")
                        .param(missing.toArray(UUID[]::new))
                        .query((rs, n) -> {
                            cache.put(rs.getObject("id", UUID.class), floats(rs.getArray("embedding")));
                            return 0;
                        }).list();
            }
            List<Map.Entry<UUID, Double>> all = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                float[] v = cache.get(id);
                if (v != null) {
                    all.add(Map.entry(id, Vectors.cosine(embedding, v)));
                }
            }
            all.stream().sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()).limit(k)
                    .forEach(e -> found.put(e.getKey(), e.getValue()));
        }
        if (found.isEmpty()) {
            return List.of();
        }
        List<Fact> rows = jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f WHERE f.id = ANY(?)")
                .param(found.keySet().toArray(UUID[]::new)).query(JdbcFactStore::fact).list();
        return rows.stream().map(f -> new Scored(f, found.get(f.id())))
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed()).toList();
    }

    @Override
    public Optional<Fact> get(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f WHERE f.id = ?").param(id).query(JdbcFactStore::fact).optional();
    }

    private static Where where(Query q) {
        Where w = new Where();
        if (q.text() != null && !q.text().isBlank()) {
            String like = "%" + q.text().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            w.and("(f.statement ILIKE ? ESCAPE '\\' OR f.subject ILIKE ? ESCAPE '\\')", like, like);
        }
        if (q.subject() != null && !q.subject().isBlank()) {
            w.and("f.subject = ?", q.subject());
        }
        if (q.kind() != null && !q.kind().isBlank()) {
            w.and("f.kind = ?", q.kind());
        }
        String validity = q.validity() == null ? "current" : q.validity();
        if ("current".equals(validity)) {
            w.and("f.expired_at IS NULL AND (f.valid_to IS NULL OR f.valid_to > ?)", MemoryRows.at(q.now()));
        } else if ("past".equals(validity)) {
            w.and("(f.expired_at IS NOT NULL OR f.valid_to <= ?) AND f.superseded_by IS NULL", MemoryRows.at(q.now()));
        }
        if (q.sensitivity() != null) {
            w.and("f.sensitivity = ?", stored(q.sensitivity()));
        }
        if (q.archived() != null) {
            w.and("f.archived = ?", q.archived());
        }
        if (q.pinned() != null) {
            w.and("f.pinned = ?", q.pinned());
        }
        if (Boolean.TRUE.equals(q.reviewed())) {
            w.and("(f.reviewed_at IS NOT NULL OR f.origin = 'owner')");
        } else if (Boolean.FALSE.equals(q.reviewed())) {
            w.and("f.reviewed_at IS NULL AND f.origin <> 'owner'");
        }
        return w;
    }

    @Override
    public List<Fact> list(Query q) {
        Where w = where(q);
        List<Object> ps = new ArrayList<>(w.params);
        ps.add(q.limit());
        ps.add(q.offset());
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f" + w.sql + " ORDER BY f.learned_at DESC, f.id LIMIT ? OFFSET ?")
                .params(ps).query(JdbcFactStore::fact).list();
    }

    @Override
    public long count(Query q) {
        Where w = where(q);
        return jdbc.sql("SELECT count(*) FROM memory.fact f" + w.sql).params(w.params).query(Long.class).single();
    }

    @Override
    public List<Fact> asOf(Instant world, Instant known, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f WHERE f.learned_at <= ? "
                        + "AND (f.expired_at IS NULL OR f.expired_at > ? OR f.superseded_by IS NULL) "
                        + "AND (f.valid_from IS NULL OR f.valid_from <= ?) "
                        // an end the world gave the fact after `known` was not known then
                        + "AND (f.valid_to IS NULL OR f.valid_to > ? OR (f.superseded_by IS NULL AND f.expired_at > ?)) "
                        + "ORDER BY f.learned_at DESC LIMIT ?")
                .params(MemoryRows.at(known), MemoryRows.at(known), MemoryRows.at(world), MemoryRows.at(world),
                        MemoryRows.at(known), limit)
                .query(JdbcFactStore::fact).list();
    }

    @Override
    public List<Fact> versions(UUID id) {
        return jdbc.sql("WITH RECURSIVE older(id) AS (SELECT id FROM memory.fact WHERE id = ? "
                        + "UNION SELECT f.id FROM memory.fact f JOIN older o ON f.superseded_by = o.id), "
                        + "newer(id, next) AS (SELECT id, superseded_by FROM memory.fact WHERE id = ? "
                        + "UNION SELECT f.id, f.superseded_by FROM memory.fact f JOIN newer n ON f.id = n.next) "
                        + "SELECT " + COLUMNS + " FROM memory.fact f WHERE f.id IN (SELECT id FROM older UNION SELECT id FROM newer) "
                        + "ORDER BY f.learned_at, f.id")
                .params(id, id).query(JdbcFactStore::fact).list();
    }

    @Override
    public List<Fact> learnedSince(Instant since) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f WHERE f.learned_at >= ? AND f.expired_at IS NULL ORDER BY f.learned_at")
                .param(MemoryRows.at(since)).query(JdbcFactStore::fact).list();
    }

    @Override
    public List<Fact> endedSince(Instant since) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f WHERE f.expired_at >= ? AND f.superseded_by IS NULL "
                        + "ORDER BY f.expired_at")
                .param(MemoryRows.at(since)).query(JdbcFactStore::fact).list();
    }

    @Override
    public List<Fact> decayable(Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f WHERE f.expired_at IS NULL AND NOT f.archived AND NOT f.pinned "
                        + "AND (f.valid_to IS NULL OR f.valid_to > ?)")
                .param(MemoryRows.at(now)).query(JdbcFactStore::fact).list();
    }

    @Override
    public void setArchived(Collection<UUID> ids, boolean archived) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE memory.fact SET archived = ? WHERE id = ANY(?)").params(archived, ids.toArray(UUID[]::new)).update();
        }
    }

    @Override
    public void setPinned(UUID id, boolean pinned) {
        jdbc.sql("UPDATE memory.fact SET pinned = ? WHERE id = ?").params(pinned, id).update();
    }

    @Override
    public void setReviewed(Collection<UUID> ids, Instant at) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE memory.fact SET reviewed_at = ? WHERE id = ANY(?)").params(MemoryRows.at(at), ids.toArray(UUID[]::new))
                    .update();
        }
    }

    @Override
    public void touch(Collection<UUID> ids, Instant at) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE memory.fact SET last_used_at = ?, use_count = use_count + 1 WHERE id = ANY(?)")
                    .params(MemoryRows.at(at), ids.toArray(UUID[]::new)).update();
        }
    }

    @Override
    public int delete(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        if (cache != null) {
            ids.forEach(cache::remove);
        }
        return jdbc.sql("DELETE FROM memory.fact WHERE id = ANY(?)").param(ids.toArray(UUID[]::new)).update();
    }

    @Override
    public int deleteOrphans() {
        List<UUID> gone = jdbc.sql("DELETE FROM memory.fact f WHERE NOT EXISTS "
                        + "(SELECT 1 FROM memory.fact_source s WHERE s.fact_id = f.id) RETURNING f.id")
                .query((rs, n) -> rs.getObject("id", UUID.class)).list();
        if (cache != null) {
            gone.forEach(cache::remove);
        }
        return gone.size();
    }

    @Override
    public List<Fact> orphans() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f WHERE NOT EXISTS "
                        + "(SELECT 1 FROM memory.fact_source s WHERE s.fact_id = f.id)")
                .query(JdbcFactStore::fact).list();
    }

    @Override
    public List<Fact> withoutEmbedding(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.fact f WHERE f.embedding IS NULL ORDER BY f.learned_at LIMIT ?")
                .param(limit).query(JdbcFactStore::fact).list();
    }

    @Override
    public void setEmbedding(UUID id, float[] embedding) {
        jdbc.sql("UPDATE memory.fact SET embedding = CAST(? AS " + vectors.type() + ") WHERE id = ?")
                .params(vectors.literal(embedding), id).update();
        if (cache != null && embedding != null) {
            cache.put(id, embedding);
        }
    }

    @Override
    public Map<UUID, float[]> embeddings(Collection<UUID> ids) {
        Map<UUID, float[]> out = new java.util.LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            float[] v = cache == null ? null : cache.get(id);
            if (v != null) {
                out.put(id, v);
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            // pgvector casts vector to real[]; without it the column already is real[]
            jdbc.sql("SELECT id, CAST(embedding AS real[]) AS e FROM memory.fact WHERE id = ANY(?) AND embedding IS NOT NULL")
                    .param(missing.toArray(UUID[]::new))
                    .query((rs, n) -> {
                        UUID id = rs.getObject("id", UUID.class);
                        float[] v = floats(rs.getArray("e"));
                        out.put(id, v);
                        if (cache != null) {
                            cache.put(id, v);
                        }
                        return 0;
                    }).list();
        }
        return out;
    }

    @Override
    public int clearEmbeddings() {
        int n = jdbc.sql("UPDATE memory.fact SET embedding = NULL WHERE embedding IS NOT NULL").update();
        if (cache != null) {
            cache.clear();
        }
        return n;
    }

    @Override
    public String searchMode() {
        return vectors.pgvector() ? "pgvector HNSW index (cosine)" : "exact cosine scan (no pgvector)";
    }

    @Override
    public int dimensions() {
        return vectors.dimensions();
    }

    @Override
    public void deleteAll() {
        if (cache != null) {
            cache.clear();
        }
        jdbc.sql("DELETE FROM memory.fact").update();
    }

    static Fact fact(ResultSet rs, int n) throws SQLException {
        return new Fact(rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("statement"),
                FactKind.parse(rs.getString("kind")), rs.getInt("importance"), rs.getFloat("confidence"),
                Sensitivity.parse(rs.getString("sensitivity"), Sensitivity.NORMAL), MemoryRows.instant(rs, "valid_from"),
                MemoryRows.instant(rs, "valid_to"), MemoryRows.instant(rs, "learned_at"), MemoryRows.instant(rs, "expired_at"),
                rs.getObject("superseded_by", UUID.class), MemoryRows.instant(rs, "last_used_at"), rs.getInt("use_count"),
                rs.getBoolean("archived"), rs.getBoolean("pinned"), FactOrigin.parse(rs.getString("origin")),
                rs.getString("extracted_by"), MemoryRows.longs(rs, "sources"), MemoryRows.instant(rs, "reviewed_at"));
    }
}
