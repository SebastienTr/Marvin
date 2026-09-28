// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code marvin.db.*}: where the database is.
 *
 * @param mode     {@code external}: a PostgreSQL server at {@code spring.datasource.url} (the Docker
 *                 container that {@code ./marvin up} starts); {@code embedded}: a PostgreSQL started
 *                 by the host itself, for machines without Docker
 * @param embedded the embedded server's settings
 */
@ConfigurationProperties("marvin.db")
public record DatabaseProperties(Mode mode, Embedded embedded) {

    /** Where the database comes from. */
    public enum Mode { EXTERNAL, EMBEDDED }

    /**
     * @param dataDirectory the cluster's files, kept between runs
     * @param port          TCP port on 127.0.0.1
     */
    public record Embedded(Path dataDirectory, int port) {
    }

    public DatabaseProperties {
        mode = mode == null ? Mode.EXTERNAL : mode;
        if (embedded == null) {
            embedded = new Embedded(null, 5434);
        }
    }
}
