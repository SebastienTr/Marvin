// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * {@code marvin.db.mode=embedded}: the host starts its own PostgreSQL (zonky embedded-postgres,
 * binaries for macOS arm64 and Linux) with its files in {@code marvin.db.embedded.data-directory},
 * kept between runs. For machines without Docker; pgvector is not bundled, see host-java/NOTES.md.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "marvin.db.mode", havingValue = "embedded")
public class EmbeddedDatabaseConfiguration {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedDatabaseConfiguration.class);
    static final String DATABASE = "marvin";

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(DatabaseProperties properties) throws IOException {
        Path dir = properties.embedded().dataDirectory();
        if (dir == null) {
            throw new IllegalStateException("marvin.db.embedded.data-directory is not set");
        }
        Files.createDirectories(dir);
        log.info("starting the embedded PostgreSQL in {} on 127.0.0.1:{}", dir, properties.embedded().port());
        return EmbeddedPostgres.builder()
                .setDataDirectory(dir)
                .setCleanDataDirectory(false)
                .setPort(properties.embedded().port())
                .setServerConfig("listen_addresses", "127.0.0.1")
                // stopped by Spring after the host has written its last rows, not by a JVM hook racing it
                .setRegisterShutdownHook(false)
                .start();
    }

    @Bean
    public DataSource dataSource(EmbeddedPostgres postgres, @Value("${marvin.mode:live}") String mode)
            throws SQLException {
        String name = "demo".equals(mode) ? DATABASE + DemoDatabase.SUFFIX : DATABASE;
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            DemoDatabase.ensure(c, name);
        }
        return postgres.getDatabase("postgres", name);
    }
}
