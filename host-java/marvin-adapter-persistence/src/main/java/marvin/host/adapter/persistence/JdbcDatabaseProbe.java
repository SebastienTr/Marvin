// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.domain.system.ComponentHealth;

/** The database in the health report: its server version and whether pgvector is installed. */
@Component
public class JdbcDatabaseProbe implements ComponentProbe {
    private final DataSource dataSource;

    public JdbcDatabaseProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        return "database";
    }

    @Override
    public ComponentHealth check() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(2);
            String version;
            try (ResultSet rs = st.executeQuery("SHOW server_version")) {
                rs.next();
                version = rs.getString(1);
            }
            String vector;
            try (ResultSet rs = st.executeQuery("SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
                vector = rs.next() ? "pgvector " + rs.getString(1) : "no pgvector";
            }
            return ComponentHealth.up("PostgreSQL " + version + ", " + vector);
        } catch (SQLException e) {
            return ComponentHealth.down(e.getMessage());
        }
    }
}
