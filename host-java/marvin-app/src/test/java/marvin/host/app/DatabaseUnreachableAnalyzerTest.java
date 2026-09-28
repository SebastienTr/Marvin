// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUnreachableAnalyzerTest {

    @Test
    void aRefusedDatabaseConnectionIsOneLineWithWhatToRun() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5433/marvin");
        ConnectException refused = new ConnectException("Connection refused");
        Throwable failure = new IllegalStateException("Error creating bean 'contextMigrations'",
                new SQLException("Connection to 127.0.0.1:5433 refused", refused));
        FailureAnalysis a = new DatabaseUnreachableAnalyzer(env).analyze(failure);
        assertThat(a).isNotNull();
        assertThat(a.getDescription()).isEqualTo("cannot reach PostgreSQL at jdbc:postgresql://127.0.0.1:5433/marvin (Connection refused)");
        assertThat(a.getAction()).contains("./marvin status").contains("./marvin doctor");
    }

    @Test
    void otherRefusedConnectionsAreNotItsBusiness() {
        assertThat(new DatabaseUnreachableAnalyzer(new MockEnvironment())
                .analyze(new IllegalStateException(new ConnectException("Connection refused")))).isNull();
    }
}
