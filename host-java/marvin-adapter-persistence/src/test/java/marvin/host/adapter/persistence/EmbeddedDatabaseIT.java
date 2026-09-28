// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * The embedded mode, for machines without Docker: starts, migrates, and keeps its data. PostgreSQL
 * refuses to run as root, so this test is skipped there (containers, some CI runners).
 */
@DisabledIfSystemProperty(named = "user.name", matches = "root")
class EmbeddedDatabaseIT {

    @Test
    void startsMigratesAndKeepsItsData(@TempDir Path dir) throws Exception {
        var props = new DatabaseProperties(DatabaseProperties.Mode.EMBEDDED, new DatabaseProperties.Embedded(dir, 0));
        var config = new EmbeddedDatabaseConfiguration();
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().setDataDirectory(dir).setCleanDataDirectory(false).start()) {
            var ds = config.dataSource(pg, "live");
            new ContextMigrations(ds, "live").afterPropertiesSet();
            assertThat(new JdbcDatabaseProbe(ds).check().isUp()).isTrue();
        }
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().setDataDirectory(dir).setCleanDataDirectory(false).start()) {
            var ds = config.dataSource(pg, "live");
            try (var c = ds.getConnection(); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM settings.flyway_schema_history")) {
                rs.next();
                assertThat(rs.getInt(1)).isPositive();
            }
        }
        assertThat(props.mode()).isEqualTo(DatabaseProperties.Mode.EMBEDDED);
    }
}
