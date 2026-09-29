// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
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
    public static final List<String> SCHEMAS = List.of("platform", "robot", "presence", "conversation", "settings", "memory");

    private final DataSource dataSource;
    private final boolean demo;
    private final int embeddingDimensions;
    private final boolean vectorAllowed;

    public ContextMigrations(DataSource dataSource, String mode) {
        this(dataSource, mode, 1024, "auto");
    }

    /**
     * @param embeddingDimensions the embedding size memory's tables are made for (the embedding model's: bge-m3 1024)
     * @param vector              {@code auto}: pgvector when installed; {@code off}: exact search on {@code real[]}
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ContextMigrations(DataSource dataSource, @Value("${marvin.mode:live}") String mode,
                             @Value("${marvin.memory.embedding-dimensions:1024}") int embeddingDimensions,
                             @Value("${marvin.memory.vector:auto}") String vector) {
        this.dataSource = dataSource;
        this.demo = "demo".equals(mode);
        this.embeddingDimensions = embeddingDimensions;
        this.vectorAllowed = !"off".equalsIgnoreCase(vector);
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        if (demo) {
            emptyDemoDatabase();
        }
        for (String schema : SCHEMAS) {
            // decided after platform's migration, which installs pgvector when it is available
            String embeddingType = "memory".equals(schema) && vectorAllowed && hasPgvector()
                    ? "public.vector(" + embeddingDimensions + ")" : "real[]";
            var result = Flyway.configure()
                    .dataSource(dataSource)
                    .placeholders(java.util.Map.of("embedding_type", embeddingType))
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

    private boolean hasPgvector() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'vector'")) {
            return rs.next();
        }
    }

    /** The demo starts from nothing, and only ever in a database named {@code *_demo}. */
    private void emptyDemoDatabase() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            String db;
            try (ResultSet rs = st.executeQuery("SELECT current_database()")) {
                rs.next();
                db = rs.getString(1);
            }
            if (!db.endsWith(DemoDatabase.SUFFIX)) {
                throw new IllegalStateException("demo mode on database " + db + ": refusing to empty it");
            }
            for (String schema : SCHEMAS.reversed()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
