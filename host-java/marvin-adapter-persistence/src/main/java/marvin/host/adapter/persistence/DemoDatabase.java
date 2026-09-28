// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The demo ({@code marvin.mode=demo}) never touches the owner's data: it runs in a database of its own,
 * {@code <database>_demo} on the same server, emptied at every start.
 */
final class DemoDatabase {
    static final String SUFFIX = "_demo";

    private DemoDatabase() {
    }

    /** Creates database {@code name} through {@code admin} (a connection to another database) if needed. */
    static void ensure(Connection admin, String name) throws SQLException {
        if (!name.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("unexpected database name " + name);
        }
        try (Statement st = admin.createStatement()) {
            boolean exists;
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + name + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                st.execute("CREATE DATABASE " + name);
            }
        }
    }

    /** The JDBC URL of the demo database next to {@code url}'s. */
    static String demoUrl(String url) {
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        String params = q < 0 ? "" : url.substring(q);
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + databaseOf(url) + SUFFIX + params;
    }

    /** The database name in a {@code jdbc:postgresql://host:port/name?...} URL. */
    static String databaseOf(String url) {
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        return base.substring(base.lastIndexOf('/') + 1);
    }
}
