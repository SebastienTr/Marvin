// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.presence.history.HistoryKinds;
import marvin.host.domain.presence.history.Sample;
import marvin.host.domain.presence.history.StoredEvent;

/** The stores and the one-time import of the Python host's marvin.db, on PostgreSQL (Docker needed). */
@Testcontainers(disabledWithoutDocker = true)
class StoresAndImportIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    private PGSimpleDataSource ds;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private ContextMigrations migrations;

    @BeforeEach
    void setUp() throws Exception {
        ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String s : ContextMigrations.SCHEMAS) {
                st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
            }
        }
        migrations = new ContextMigrations(ds, "live");
        migrations.afterPropertiesSet();
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test
    void presenceHistoryRoundTrip() {
        var store = new JdbcPresenceHistoryStore(jdbc, migrations);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seated_s", 3540.0);
        data.put("distance_m", 0.8625347270697842);
        StoredEvent a = store.add(new StoredEvent(1000.5, "arrived", "1.80 m away", Map.of("distance_m", 1.8)), 1_000_000L);
        StoredEvent b = store.add(new StoredEvent(2000.25, "stood_up", "after 59 min seated", data), 3_000_000L);
        store.add(new StoredEvent(1500, HistoryKinds.ROBOT_ONLINE, "", Map.of("present", true)), null);
        store.add(new StoredEvent(1600, "vitals_acquired", "", Map.of()), null);
        assertThat(b.id()).isGreaterThan(a.id());
        assertThat(store.events(0, 3000)).extracting(StoredEvent::kind)
                .containsExactly("arrived", "robot_online", "vitals_acquired", "stood_up");
        StoredEvent back = store.recent(10, 0, List.of()).getFirst();
        assertThat(back.data()).containsExactly(Map.entry("seated_s", 3540.0), Map.entry("distance_m", 0.8625347270697842));
        assertThat(store.recent(10, 0, HistoryKinds.VITALS)).extracting(StoredEvent::kind).doesNotContain("vitals_acquired");
        assertThat(store.recent(10, b.id() - 1, List.of())).hasSizeLessThanOrEqualTo(3);
        assertThat(store.recent(10, 0, List.of()).stream().filter(e -> e.kind().equals("robot_online")).findFirst()
                .orElseThrow().data()).containsEntry("present", true);

        store.addSample(new Sample(60, 1, 0.5, 14.0, null));
        store.addSample(new Sample(60, 1, 1, 15.0, 66.0));               // the same minute again replaces it
        store.addSample(new Sample(120, 0, 0, null, null));
        assertThat(store.samples(0, 1000)).containsExactly(new Sample(60, 1, 1, 15.0, 66.0), new Sample(120, 0, 0, null, null));
    }

    @Test
    void settingsAndConversation() {
        var settings = new JdbcSettingsStore(jdbc, tx, migrations);
        settings.save(Map.of("break_interval_min", 30, "quiet_hours", Map.of("enabled", true)));
        settings.save(Map.of("clock", "12h", "break_interval_min", 45.5));
        assertThat(settings.load()).containsEntry("break_interval_min", 45.5).containsEntry("clock", "12h")
                .containsEntry("quiet_hours", Map.of("enabled", true));

        var convo = new JdbcConversationStore(jdbc, migrations);
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("endpoint", 0.55);
        latency.put("stt", 0.18);
        latency.put("audio_start", 0.71);
        convo.add(new ConversationEntry(1000, 10.0, "heard", "What's my HEART rate?", Map.of("language", "en")));
        convo.add(new ConversationEntry(1001, 11.0, "reply", "About 66 beats 100%_sure", Map.of("latency", latency)));
        convo.add(new ConversationEntry(1002, 12.0, "ignored", "heart", Map.of()));
        convo.add(new ConversationEntry(1000, 10.0, "heard", "What's my heart rate?", Map.of("language", "en")));
        assertThat(convo.between(0, 100, 1000)).extracting(ConversationEntry::id).containsExactly(1000L, 1001L, 1002L);
        assertThat(convo.between(0, 100, 2)).extracting(ConversationEntry::id).containsExactly(1001L, 1002L);
        assertThat(convo.search("HEART", 10)).extracting(ConversationEntry::id).containsExactly(1000L);
        assertThat(convo.search("100%_", 10)).extracting(ConversationEntry::id).containsExactly(1001L);
        assertThat(convo.search("0%s", 10)).isEmpty();
        assertThat(convo.between(0, 100, 10).get(1).data().get("latency")).isEqualTo(latency);
        assertThat(List.copyOf(((Map<?, ?>) convo.between(0, 100, 10).get(1).data().get("latency")).keySet()))
                .isEqualTo(List.of("endpoint", "stt", "audio_start"));
        assertThat(convo.maxId()).isEqualTo(1002);
    }

    @Test
    void importsAPythonHistoryOnce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("marvin.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            // store.py's schema, version 2
            st.executeUpdate("CREATE TABLE events (id INTEGER PRIMARY KEY, ts REAL NOT NULL, kind TEXT NOT NULL, "
                    + "detail TEXT NOT NULL DEFAULT '', data TEXT NOT NULL DEFAULT '{}', t_us INTEGER)");
            st.executeUpdate("CREATE TABLE samples (ts REAL PRIMARY KEY, present REAL NOT NULL, seated REAL NOT NULL, "
                    + "breath REAL, heart REAL)");
            st.executeUpdate("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE conversation (id INTEGER PRIMARY KEY, ts REAL NOT NULL, kind TEXT NOT NULL, "
                    + "text TEXT NOT NULL DEFAULT '', data TEXT NOT NULL DEFAULT '{}')");
            st.executeUpdate("INSERT INTO events VALUES (5, 100.5, 'host_started', '', '{}', NULL)");
            st.executeUpdate("INSERT INTO events VALUES (7, 101.25, 'arrived', '1.80 m away', '{\"distance_m\": 1.8}', 5000)");
            st.executeUpdate("INSERT INTO events VALUES (9, 102.0, 'left', '', 'not json', NULL)");
            st.executeUpdate("INSERT INTO samples VALUES (60.0, 1.0, 0.5, 14.2, NULL)");
            st.executeUpdate("INSERT INTO settings VALUES ('break_interval_min', '30')");
            st.executeUpdate("INSERT INTO settings VALUES ('clock', '\"12h\"')");
            st.executeUpdate("INSERT INTO conversation VALUES (1700000000000, 100.0, 'heard', 'Hello', "
                    + "'{\"language\": \"en\", \"source\": \"voice\"}')");
        }
        new JdbcSettingsStore(jdbc, tx, migrations).save(Map.of("clock", "24h"));     // the Java host's own wins

        var importer = new SqliteImporter(jdbc, tx, migrations);
        SqliteImporter.Result r = importer.importOnce(file);
        assertThat(r.imported()).isTrue();
        assertThat(r.events()).isEqualTo(3);
        assertThat(r.samples()).isEqualTo(1);
        assertThat(r.conversation()).isEqualTo(1);
        assertThat(importer.importOnce(file).imported()).isFalse();                      // once
        assertThat(importer.importOnce(dir.resolve("missing.db")).imported()).isFalse();

        var store = new JdbcPresenceHistoryStore(jdbc, migrations);
        assertThat(store.events(0, 1000)).extracting(StoredEvent::id).containsExactly(5L, 7L, 9L);
        assertThat(store.events(0, 1000).get(1).data()).containsEntry("distance_m", 1.8);
        assertThat(store.events(0, 1000).get(2).data()).isEmpty();
        assertThat(store.add(new StoredEvent(200, "left", "", Map.of()), null).id()).isEqualTo(10L);
        assertThat(store.samples(0, 100)).containsExactly(new Sample(60, 1, 0.5, 14.2, null));
        assertThat(new JdbcSettingsStore(jdbc, tx, migrations).load())
                .containsEntry("clock", "24h").containsEntry("break_interval_min", 30);
        var convo = new JdbcConversationStore(jdbc, migrations).between(0, 1000, 10);
        assertThat(convo).hasSize(1);
        assertThat(convo.getFirst().toMap()).containsExactly(Map.entry("language", "en"), Map.entry("source", "voice"),
                Map.entry("id", 1700000000000L), Map.entry("t", 100.0), Map.entry("kind", "heard"),
                Map.entry("text", "Hello"));
    }

    @Test
    void theDemoEmptiesOnlyADemoDatabase() throws Exception {
        try (Connection c = ds.getConnection()) {
            DemoDatabase.ensure(c, "test_demo");
            DemoDatabase.ensure(c, "test_demo");
        }
        assertThat(DemoDatabase.demoUrl("jdbc:postgresql://127.0.0.1:5433/marvin?ssl=false"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:5433/marvin_demo?ssl=false");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ContextMigrations(ds, "demo").afterPropertiesSet())
                .hasMessageContaining("refusing");
        PGSimpleDataSource demo = new PGSimpleDataSource();
        demo.setUrl(DemoDatabase.demoUrl(postgres.getJdbcUrl()).replace("/" + DemoDatabase.databaseOf(postgres.getJdbcUrl())
                + "_demo", "/test_demo"));
        demo.setUser(postgres.getUsername());
        demo.setPassword(postgres.getPassword());
        var m = new ContextMigrations(demo, "demo");
        m.afterPropertiesSet();
        JdbcClient dj = JdbcClient.create(demo);
        new JdbcPresenceHistoryStore(dj, m).add(new StoredEvent(1, "arrived", "", Map.of()), null);
        new ContextMigrations(demo, "demo").afterPropertiesSet();
        assertThat(new JdbcPresenceHistoryStore(dj, m).events(0, 10)).isEmpty();
    }
}
