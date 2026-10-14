// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.domain.vitals.VitalsMinute;

/** The per-minute vital signs (presence/V3) on PostgreSQL (Docker needed). */
@Testcontainers(disabledWithoutDocker = true)
class VitalsStoreIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    private JdbcVitalsStore store;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String s : ContextMigrations.SCHEMAS) {
                st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
            }
        }
        ContextMigrations migrations = new ContextMigrations(ds, "live");
        migrations.afterPropertiesSet();
        jdbc = JdbcClient.create(ds);
        store = new JdbcVitalsStore(jdbc, migrations);
    }

    @Test
    void minutesRoundTripReplaceAndPrune() {
        store.add(new VitalsMinute(1789982880.0, 66.0, 14.0, 58));
        store.add(new VitalsMinute(1789979400.0, null, null, 0));
        store.add(new VitalsMinute(1789982940.0, 67.5, 13.5, 60));
        assertThat(store.minutes(0, 2e9)).containsExactly(new VitalsMinute(1789979400.0, null, null, 0),
                new VitalsMinute(1789982880.0, 66.0, 14.0, 58), new VitalsMinute(1789982940.0, 67.5, 13.5, 60));
        store.add(new VitalsMinute(1789982880.0, 65.0, 14.5, 59));      // the same minute again replaces it
        assertThat(store.minutes(1789982880.0, 1789982940.0)).containsExactly(new VitalsMinute(1789982880.0, 65.0, 14.5, 59));
        assertThat(store.deleteBefore(1789982880.0)).isEqualTo(1);
        assertThat(store.minutes(0, 2e9)).extracting(VitalsMinute::t).containsExactly(1789982880.0, 1789982940.0);
    }

    @Test
    void theTableIsInThePresenceSchemaAndChecksN() {
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'presence' "
                + "AND table_name = 'vitals_minute'").query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> store.add(new VitalsMinute(60.0, 60.0, 12.0, 61))).isInstanceOf(RuntimeException.class);
    }
}
