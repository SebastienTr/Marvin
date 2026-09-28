// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The migrations on the same PostgreSQL image as ./marvin up (Docker needed). */
@Testcontainers(disabledWithoutDocker = true)
class ContextMigrationsIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    @Test
    void everyContextGetsItsOwnSchemaAndHistoryAndPgvectorIsThere() throws Exception {
        var ds = dataSource();
        new ContextMigrations(ds).afterPropertiesSet();
        new ContextMigrations(ds).afterPropertiesSet();          // idempotent
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT table_schema FROM information_schema.tables "
                    + "WHERE table_name = 'flyway_schema_history' ORDER BY table_schema")) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
            assertThat(schemas).containsExactlyInAnyOrderElementsOf(ContextMigrations.SCHEMAS);
            try (ResultSet rs = st.executeQuery("SELECT to_regclass('settings.setting') IS NOT NULL")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
        assertThat(new JdbcDatabaseProbe(ds).check().detail()).startsWith("PostgreSQL 18").contains("pgvector");
    }
}
