// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.application.memory.port.out.FactStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Sensitivity;

/** Memory's SQL on PostgreSQL with pgvector (and without it): the log, bi-temporal facts, HNSW search, forgetting. */
@Testcontainers(disabledWithoutDocker = true)
class MemoryStoresIT {
    static final int DIMS = 8;

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private ContextMigrations migrations;
    private JdbcEventLog log;
    private JdbcFactStore facts;

    static final Instant T0 = Instant.parse("2026-01-10T09:00:00Z");

    private PGSimpleDataSource dataSource(String db) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + db));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource ds = dataSource(postgres.getDatabaseName());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String s : ContextMigrations.SCHEMAS) {
                st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
            }
        }
        migrations = new ContextMigrations(ds, "live", DIMS, "auto");
        migrations.afterPropertiesSet();
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        log = new JdbcEventLog(jdbc, tx, migrations);
        facts = new JdbcFactStore(jdbc, tx, migrations, DIMS);
    }

    static float[] v(double... xs) {
        float[] out = new float[DIMS];
        for (int i = 0; i < xs.length; i++) {
            out[i] = (float) xs[i];
        }
        return out;
    }

    static MemoryEvent said(Instant at, String ref, String text) {
        return MemoryEvent.draft(at, "conversation", "heard", Sensitivity.NORMAL, ref, text, Map.of("language", "fr"));
    }

    private long event(Instant at, String ref, String text) {
        return log.appendOne(said(at, ref, text)).orElseThrow().id();
    }

    private Fact add(String statement, float[] vector, Instant learned, long... sources) {
        FactCandidate c = new FactCandidate("owner", statement, FactKind.BIOGRAPHICAL, null, null, 7, Sensitivity.NORMAL, 0.9);
        List<Long> ids = new ArrayList<>();
        for (long s : sources) {
            ids.add(s);
        }
        Reconciliation.Plan p = Reconciliation.plan(c, new Operation.Add(), List.of(), ids, learned, learned, UUID::randomUUID, "test");
        facts.apply(p, Map.of(p.added().getFirst().id(), vector));
        return p.added().getFirst();
    }

    @Test
    void theSchemaUsesPgvectorWithAnHnswIndex() {
        assertThat(facts.searchMode()).contains("HNSW");
        assertThat(facts.dimensions()).isEqualTo(DIMS);
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'memory' AND indexname = 'fact_embedding'")
                .query(String.class).single()).contains("hnsw").contains("vector_cosine_ops");
        assertThat(jdbc.sql("SELECT count(*) FROM memory.flyway_schema_history").query(Long.class).single()).isPositive();
    }

    @Test
    void theLogKeepsAnEventOnceAndTracksWhatWasRead() {
        MemoryEvent a = said(T0, "conversation:1", "J'habite à Lyon");
        assertThat(log.append(List.of(a, a, said(T0.plusSeconds(5), "conversation:2", "Et toi ?")))).isEqualTo(2);
        assertThat(log.append(List.of(a))).isZero();
        assertThat(log.appendOne(a)).isEmpty();
        assertThat(log.count()).isEqualTo(2);
        List<MemoryEvent> news = log.unconsolidated(10);
        assertThat(news).extracting(MemoryEvent::body).containsExactly("J'habite à Lyon", "Et toi ?");
        assertThat(news.getFirst().data()).containsEntry("language", "fr");
        assertThat(news.getFirst().ts()).isEqualTo(T0);
        log.markConsolidated(List.of(news.getFirst().id()), T0.plusSeconds(60));
        assertThat(log.unconsolidatedCount()).isEqualTo(1);
        assertThat(log.recent("lyon", 0, 10)).hasSize(1);
        assertThat(log.recent("", news.get(1).id(), 10)).extracting(MemoryEvent::id).containsExactly(news.getFirst().id());
        assertThat(log.between(T0, T0.plusSeconds(1), 10)).hasSize(1);
        assertThat(log.first()).contains(T0);
        log.redact(news.getFirst().id(), "[redacted]");
        assertThat(log.byIds(List.of(news.getFirst().id())).getFirst().body()).isEqualTo("[redacted]");
    }

    @Test
    void nearestFactsComeFromTheIndexAndRespectTheFilter() throws Exception {
        long e = event(T0, "conversation:1", "x");
        Fact lyon = add("Lives in Lyon", v(1, 0.1), T0, e);
        Fact cat = add("Has a cat named Tofu", v(0, 1), T0, e);
        Fact tea = add("Prefers tea to coffee", v(0.2, 0, 1), T0, e);
        facts.setArchived(List.of(tea.id()), true);

        List<FactStore.Scored> near = facts.nearest(v(1, 0), 5, FactStore.Filter.current(T0.plusSeconds(1)));
        assertThat(near.getFirst().fact().id()).isEqualTo(lyon.id());
        assertThat(near.getFirst().similarity()).isGreaterThan(0.99);
        assertThat(near).extracting(s -> s.fact().id()).contains(cat.id());
        assertThat(facts.nearest(v(0, 0, 1), 5, new FactStore.Filter(T0.plusSeconds(1), true, false, Sensitivity.SENSITIVE)))
                .extracting(s -> s.fact().id()).doesNotContain(tea.id());

        String plan;
        try (Connection c = ((PGSimpleDataSource) dataSource(postgres.getDatabaseName())).getConnection();
             Statement st = c.createStatement()) {
            st.execute("SET enable_seqscan = off");
            try (var rs = st.executeQuery("EXPLAIN SELECT id FROM memory.fact ORDER BY embedding OPERATOR(public.<=>) "
                    + "CAST('" + facts_literal(v(1, 0)) + "' AS public.vector) LIMIT 5")) {
                StringBuilder b = new StringBuilder();
                while (rs.next()) {
                    b.append(rs.getString(1)).append('\n');
                }
                plan = b.toString();
            }
        }
        assertThat(plan).contains("fact_embedding");
    }

    private static String facts_literal(float[] v) {
        return new MemoryRows.Vectors(true, DIMS).literal(v);
    }

    @Test
    void invalidationKeepsHistoryOnBothClocks() {
        long e1 = event(T0, "conversation:1", "J'habite à Lyon");
        Fact lyon = add("Lives in Lyon", v(1), T0, e1);
        Instant moved = T0.plus(Duration.ofDays(30));
        Instant learned = moved.plus(Duration.ofDays(2));
        long e2 = event(learned, "conversation:2", "J'ai déménagé à Lille le mois dernier");
        FactCandidate lille = new FactCandidate("owner", "Lives in Lille", FactKind.BIOGRAPHICAL, moved, null, 7,
                Sensitivity.NORMAL, 0.9);
        Reconciliation.Plan p = Reconciliation.plan(lille, new Operation.Invalidate(lyon.id(), null), List.of(lyon), List.of(e2),
                learned, learned, UUID::randomUUID, "test");
        facts.apply(p, Map.of(p.added().getFirst().id(), v(0.9, 0.3)));

        Fact oldNow = facts.get(lyon.id()).orElseThrow();
        assertThat(oldNow.validTo()).isEqualTo(moved);
        assertThat(oldNow.expiredAt()).isEqualTo(learned);
        assertThat(oldNow.current(learned.plusSeconds(1))).isFalse();

        Instant later = learned.plus(Duration.ofDays(1));
        FactStore.Query current = new FactStore.Query("", "", "", "current", null, null, null, later, 50, 0);
        FactStore.Query past = new FactStore.Query("", "", "", "past", null, null, null, later, 50, 0);
        assertThat(facts.list(current)).extracting(Fact::statement).containsExactly("Lives in Lille");
        assertThat(facts.list(past)).extracting(Fact::statement).containsExactly("Lives in Lyon");
        assertThat(facts.nearest(v(1), 5, FactStore.Filter.current(later))).extracting(s -> s.fact().statement())
                .containsExactly("Lives in Lille");

        // world time: where did the owner live in between? Lyon, as memory knows it now
        assertThat(facts.asOf(T0.plus(Duration.ofDays(10)), later, 10)).extracting(Fact::statement).containsExactly("Lives in Lyon");
        assertThat(facts.asOf(later, later, 10)).extracting(Fact::statement).containsExactly("Lives in Lille");
        // our time: before it learned of the move, memory believed Lyon for today too
        assertThat(facts.asOf(moved.plus(Duration.ofDays(1)), moved.plus(Duration.ofDays(1)), 10))
                .extracting(Fact::statement).containsExactly("Lives in Lyon");
        assertThat(facts.endedSince(learned)).extracting(Fact::id).containsExactly(lyon.id());
        assertThat(facts.learnedSince(learned)).extracting(Fact::statement).containsExactly("Lives in Lille");
    }

    @Test
    void anUpdateMakesAVersionChain() {
        long e1 = event(T0, "conversation:1", "a");
        long e2 = event(T0.plusSeconds(60), "conversation:2", "b");
        Fact first = add("Likes jazz", v(1), T0, e1);
        FactCandidate better = new FactCandidate("owner", "Likes jazz, especially Coltrane", FactKind.PREFERENCE, null, null, 6,
                Sensitivity.NORMAL, 0.8);
        Instant at = T0.plusSeconds(120);
        Reconciliation.Plan p = Reconciliation.plan(better, new Operation.Update(first.id(), null), List.of(first), List.of(e2), at, at,
                UUID::randomUUID, "test");
        facts.apply(p, Map.of(p.added().getFirst().id(), v(1, 0.1)));
        Fact second = p.added().getFirst();
        assertThat(facts.get(first.id()).orElseThrow().supersededBy()).isEqualTo(second.id());
        assertThat(facts.get(second.id()).orElseThrow().sources()).containsExactly(e1, e2);
        assertThat(facts.versions(first.id())).extracting(Fact::id).containsExactly(first.id(), second.id());
        assertThat(facts.versions(second.id())).extracting(Fact::id).containsExactly(first.id(), second.id());
        // the old wording is not a record of anything once replaced
        assertThat(facts.asOf(at.plusSeconds(1), at.plusSeconds(1), 10)).extracting(Fact::id).containsExactly(second.id());
        assertThat(facts.asOf(T0.plusSeconds(30), T0.plusSeconds(30), 10)).extracting(Fact::id).containsExactly(first.id());
        // NOOP: more sources for an existing fact
        long e3 = event(T0.plusSeconds(180), "conversation:3", "c");
        facts.apply(new Reconciliation.Plan(List.of(), List.of(), Map.of(second.id(), List.of(e3, 999_999L)), new Operation.Noop(second.id())),
                Map.of());
        assertThat(facts.get(second.id()).orElseThrow().sources()).containsExactly(e1, e2, e3);
    }

    @Test
    void forgettingEventsTakesTheFactsThatOnlyCameFromThem() {
        long e1 = event(T0, "conversation:1", "a");
        long e2 = event(T0.plusSeconds(1), "conversation:2", "b");
        long e3 = event(T0.plusSeconds(2), "presence:1", "You came in");
        Fact onlyE1 = add("Only from e1", v(1), T0, e1);
        Fact both = add("From e1 and e2", v(0, 1), T0, e1, e2);
        assertThat(log.delete(List.of(e1))).isEqualTo(1);
        assertThat(facts.get(onlyE1.id()).orElseThrow().sources()).isEmpty();
        assertThat(facts.deleteOrphans()).isEqualTo(1);
        assertThat(facts.get(onlyE1.id())).isEmpty();
        assertThat(facts.get(both.id()).orElseThrow().sources()).containsExactly(e2);

        jdbc.sql("UPDATE memory.event_log SET source = 'brain' WHERE id IN (?, ?)").params(e2, e3).update();
        assertThat(log.deleteUnreferenced("brain", T0.plusSeconds(10))).isEqualTo(1);   // e2 is a fact's source: kept
        assertThat(log.byIds(List.of(e2, e3))).extracting(MemoryEvent::id).containsExactly(e2);

        facts.touch(List.of(both.id()), T0.plusSeconds(30));
        assertThat(facts.get(both.id()).orElseThrow().useCount()).isEqualTo(1);
        facts.setPinned(both.id(), true);
        assertThat(facts.decayable(T0.plusSeconds(40))).isEmpty();
        assertThat(facts.delete(List.of(both.id()))).isEqualTo(1);
    }

    @Test
    void episodesProfileAndState() {
        var episodes = new JdbcEpisodeStore(jdbc, migrations, DIMS);
        LocalDate d = LocalDate.of(2026, 1, 10);
        Instant s = T0.minusSeconds(9 * 3600);
        Episode e = episodes.put(new Episode(0, EpisodeLevel.DAY, d, s, s.plus(Duration.ofDays(1)), "A quiet day.", false, T0, 12), v(1));
        assertThat(e.id()).isPositive();
        episodes.put(new Episode(0, EpisodeLevel.DAY, d, s, s.plus(Duration.ofDays(1)), "A quiet day, rewritten.", false, T0, 13), null);
        assertThat(episodes.get(EpisodeLevel.DAY, d).orElseThrow().summary()).isEqualTo("A quiet day, rewritten.");
        assertThat(episodes.markStale(T0, T0.plusSeconds(1))).isEqualTo(1);
        assertThat(episodes.stale()).hasSize(1);
        assertThat(episodes.latest(EpisodeLevel.DAY).orElseThrow().day()).isEqualTo(d);
        assertThat(episodes.list(EpisodeLevel.DAY, d, d.plusDays(1))).hasSize(1);
        assertThat(episodes.list(EpisodeLevel.WEEK, d, d.plusDays(1))).isEmpty();

        var profiles = new JdbcProfileStore(jdbc, tx, migrations);
        BlockVersion v1 = profiles.add(new BlockVersion(0, Block.PROFILE, "Lives in Lyon.", 5, BlockVersion.Status.ACTIVE, "first",
                List.of(1L, 2L), BlockVersion.Author.WORKER, T0, T0, List.of()));
        BlockVersion v2 = profiles.add(new BlockVersion(0, Block.PROFILE, "Lives in Lille.\nCall me Sam.", 9, BlockVersion.Status.ACTIVE,
                "owner", List.of(), BlockVersion.Author.OWNER, T0.plusSeconds(1), T0.plusSeconds(1), List.of("Call me Sam.")));
        assertThat(profiles.active(Block.PROFILE).orElseThrow().id()).isEqualTo(v2.id());
        assertThat(profiles.get(v1.id()).orElseThrow().status()).isEqualTo(BlockVersion.Status.SUPERSEDED);
        assertThat(profiles.get(v1.id()).orElseThrow().evidence()).containsExactly(1L, 2L);
        assertThat(profiles.active(Block.PROFILE).orElseThrow().keptLines()).containsExactly("Call me Sam.");
        assertThat(profiles.versions(Block.PROFILE, 10)).extracting(BlockVersion::id).containsExactly(v2.id(), v1.id());
        assertThatThrownBy(() -> jdbc.sql("UPDATE memory.block_version SET status = 'active'").update())
                .hasMessageContaining("block_version_active");

        var state = new JdbcMemoryState(jdbc, migrations);
        assertThat(state.get("worker")).isEmpty();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("last_night_at", 1.5);
        state.put("worker", w);
        assertThat(state.get("worker")).containsEntry("last_night_at", 1.5);
    }

    @Test
    void withoutPgvectorTheSearchIsExact() throws Exception {
        try (Connection c = dataSource(postgres.getDatabaseName()).getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS noext");
            st.execute("CREATE DATABASE noext");
        }
        PGSimpleDataSource ds = dataSource("noext");
        ContextMigrations m = new ContextMigrations(ds, "live", DIMS, "off");
        m.afterPropertiesSet();
        JdbcClient j = JdbcClient.create(ds);
        TransactionTemplate t = new TransactionTemplate(new DataSourceTransactionManager(ds));
        JdbcFactStore f = new JdbcFactStore(j, t, m, DIMS);
        JdbcEventLog l = new JdbcEventLog(j, t, m);
        assertThat(f.searchMode()).contains("exact").contains("no pgvector");
        long e = l.appendOne(said(T0, "conversation:1", "x")).orElseThrow().id();
        FactCandidate c1 = new FactCandidate("owner", "Lives in Lyon", FactKind.BIOGRAPHICAL, null, null, 7, Sensitivity.NORMAL, 0.9);
        FactCandidate c2 = new FactCandidate("owner", "Has a cat", FactKind.BIOGRAPHICAL, null, null, 7, Sensitivity.NORMAL, 0.9);
        for (var pair : List.of(Map.entry(c1, v(1, 0)), Map.entry(c2, v(0, 1)))) {
            Reconciliation.Plan p = Reconciliation.plan(pair.getKey(), new Operation.Add(), List.of(), List.of(e), T0, T0,
                    UUID::randomUUID, "test");
            f.apply(p, Map.of(p.added().getFirst().id(), pair.getValue()));
        }
        List<FactStore.Scored> near = f.nearest(v(0.1, 1), 1, FactStore.Filter.current(T0.plusSeconds(1)));
        assertThat(near).extracting(s -> s.fact().statement()).containsExactly("Has a cat");
        assertThat(near.getFirst().similarity()).isGreaterThan(0.99);
        assertThat(j.sql("SELECT count(*) FROM pg_indexes WHERE schemaname = 'memory' AND indexname = 'fact_embedding'")
                .query(Long.class).single()).isZero();
    }
}
