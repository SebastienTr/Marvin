// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import marvin.host.domain.system.ComponentHealth;

class JdbcDatabaseProbeTest {

    @Test
    void anUnreachableDatabaseIsDownNotAnException() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://127.0.0.1:1/marvin?connectTimeout=1");
        ComponentHealth h = new JdbcDatabaseProbe(ds).check();
        assertThat(h.state()).isEqualTo(ComponentHealth.State.DOWN);
        assertThat(h.detail()).isNotBlank();
    }
}
