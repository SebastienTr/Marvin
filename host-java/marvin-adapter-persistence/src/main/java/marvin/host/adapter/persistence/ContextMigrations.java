// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Runs the Flyway migrations of each bounded context in its own schema, with its own history
 * table, so that a context can later move to its own database without untangling a shared one
 * (docs/design.md 10.4). {@code platform} holds what every context shares (extensions).
 */
@Component
public class ContextMigrations implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(ContextMigrations.class);

    /** The schemas, in migration order. */
    public static final List<String> SCHEMAS = List.of("platform", "robot", "presence", "conversation", "settings");

    private final DataSource dataSource;

    public ContextMigrations(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        for (String schema : SCHEMAS) {
            var result = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration/" + schema)
                    .failOnMissingLocations(false)
                    .load()
                    .migrate();
            if (result.migrationsExecuted > 0) {
                log.info("database schema {}: {} migration(s) applied", schema, result.migrationsExecuted);
            }
        }
    }
}
