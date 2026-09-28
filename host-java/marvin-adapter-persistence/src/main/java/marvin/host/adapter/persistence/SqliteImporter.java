// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports the Python host's SQLite history ({@code marvin.db}: events, samples, settings and, from
 * schema 2, the conversation) into PostgreSQL, once per file: the import is recorded in
 * {@code platform.import} and a file already imported is skipped. Everything goes in one transaction.
 *
 * <p>Rows keep their ids when the target tables are empty (the normal first start), so the event ids
 * the app has seen stay the same; otherwise events get new ids after the existing ones. Settings and
 * conversation entries already in PostgreSQL win over the file's.
 */
@Component
public class SqliteImporter {
    private static final Logger log = LoggerFactory.getLogger(SqliteImporter.class);

    /** What one import did. */
    public record Result(boolean imported, String reason, int events, int samples, int conversation, int settings) {
        static Result skipped(String why) {
            return new Result(false, why, 0, 0, 0, 0);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public SqliteImporter(JdbcClient jdbc, TransactionTemplate tx, ContextMigrations migrated) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Imports {@code file} unless it is missing or was imported before. */
    public Result importOnce(Path file) {
        if (!Files.isRegularFile(file)) {
            return Result.skipped("no " + file);
        }
        String source = file.toAbsolutePath().normalize().toString();
        boolean done = jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform.import WHERE source = ?)")
                .param(source).query(Boolean.class).single();
        if (done) {
            return Result.skipped("already imported");
        }
        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:file:" + source + "?mode=ro")) {
            Set<String> tables = tables(sqlite);
            if (!tables.contains("events")) {
                return Result.skipped(file + " is not a Marvin history");
            }
            long size = Files.size(file);
            Result r = tx.execute(status -> {
                try {
                    int events = events(sqlite);
                    int samples = tables.contains("samples") ? samples(sqlite) : 0;
                    int settings = tables.contains("settings") ? settings(sqlite) : 0;
                    int conversation = tables.contains("conversation") ? conversation(sqlite) : 0;
                    jdbc.sql("INSERT INTO platform.import (source, size_bytes, events, samples, conversation, settings) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)")
                            .params(source, size, events, samples, conversation, settings).update();
                    return new Result(true, "imported", events, samples, conversation, settings);
                } catch (SQLException e) {
                    throw new IllegalStateException("could not read " + source + ": " + e.getMessage(), e);
                }
            });
            log.info("imported {}: {} events, {} samples, {} conversation entries, {} settings", source, r.events(),
                    r.samples(), r.conversation(), r.settings());
            return r;
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("could not read " + source + ": " + e.getMessage(), e);
        }
    }

    private static Set<String> tables(Connection c) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private int events(Connection c) throws SQLException {
        boolean keepIds = jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM presence.event)").query(Boolean.class).single();
        int n = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, ts, kind, detail, data, t_us FROM events ORDER BY ts, id")) {
            while (rs.next()) {
                String data = json(rs.getString("data"));
                Long tUs = rs.getObject("t_us") == null ? null : rs.getLong("t_us");
                if (keepIds) {
                    n += jdbc.sql("INSERT INTO presence.event (id, ts, kind, detail, data, t_us) "
                                    + "VALUES (?, ?, ?, ?, ?::json, ?) ON CONFLICT (id) DO NOTHING")
                            .params(rs.getLong("id"), rs.getDouble("ts"), rs.getString("kind"),
                                    text(rs.getString("detail")), data, tUs).update();
                } else {
                    n += jdbc.sql("INSERT INTO presence.event (ts, kind, detail, data, t_us) VALUES (?, ?, ?, ?::json, ?)")
                            .params(rs.getDouble("ts"), rs.getString("kind"), text(rs.getString("detail")), data, tUs)
                            .update();
                }
            }
        }
        if (keepIds) {
            jdbc.sql("SELECT setval(pg_get_serial_sequence('presence.event', 'id'), COALESCE(MAX(id), 1), "
                    + "MAX(id) IS NOT NULL) FROM presence.event").query(Long.class).single();
        }
        return n;
    }

    private int samples(Connection c) throws SQLException {
        int n = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT ts, present, seated, breath, heart FROM samples ORDER BY ts")) {
            while (rs.next()) {
                n += jdbc.sql("INSERT INTO presence.sample (ts, present, seated, breath, heart) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT (ts) DO NOTHING")
                        .params(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3),
                                JdbcPresenceHistoryStore.nullable(rs, 4), JdbcPresenceHistoryStore.nullable(rs, 5))
                        .update();
            }
        }
        return n;
    }

    private int settings(Connection c) throws SQLException {
        int n = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT key, value FROM settings ORDER BY key")) {
            while (rs.next()) {
                Object value = JsonValues.read(rs.getString(2));
                if (value == null && !"null".equals(rs.getString(2).strip())) {
                    continue;                                   // not JSON: nothing the settings could use
                }
                n += jdbc.sql("INSERT INTO settings.setting (key, value) VALUES (?, ?::jsonb) ON CONFLICT (key) DO NOTHING")
                        .params(rs.getString(1), rs.getString(2)).update();
            }
        }
        return n;
    }

    private int conversation(Connection c) throws SQLException {
        int n = 0;
        try (PreparedStatement st = c.prepareStatement("SELECT id, ts, kind, text, data FROM conversation ORDER BY ts, id");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                n += jdbc.sql("INSERT INTO conversation.entry (id, ts, kind, text, data) VALUES (?, ?, ?, ?, ?::json) "
                                + "ON CONFLICT (id) DO NOTHING")
                        .params(rs.getLong(1), rs.getDouble(2), rs.getString(3), text(rs.getString(4)),
                                json(rs.getString(5)))
                        .update();
            }
        }
        return n;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    /** The row's JSON object as it was written, or {@code {}} if it is not one. */
    private static String json(String s) {
        return s != null && JsonValues.isObject(s) ? s : "{}";
    }
}
