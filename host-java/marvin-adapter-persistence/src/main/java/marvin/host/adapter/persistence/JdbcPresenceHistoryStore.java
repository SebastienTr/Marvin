// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.presence.port.out.PresenceHistoryStore;
import marvin.host.domain.presence.history.Sample;
import marvin.host.domain.presence.history.StoredEvent;

/** The presence history in the {@code presence} schema. */
@Component
public class JdbcPresenceHistoryStore implements PresenceHistoryStore {
    private final JdbcClient jdbc;

    public JdbcPresenceHistoryStore(JdbcClient jdbc, ContextMigrations migrated) {
        this.jdbc = jdbc;
    }

    @Override
    public StoredEvent add(StoredEvent e, Long deviceTUs) {
        long id = jdbc.sql("INSERT INTO presence.event (ts, kind, detail, data, t_us) VALUES (?, ?, ?, ?::json, ?) RETURNING id")
                .params(e.ts(), e.kind(), e.detail(), JsonValues.write(e.data()), deviceTUs)
                .query(Long.class).single();
        return new StoredEvent(e.ts(), e.kind(), e.detail(), e.data(), id);
    }

    @Override
    public List<StoredEvent> events(double start, double end) {
        return jdbc.sql("SELECT id, ts, kind, detail, data::text AS data FROM presence.event WHERE ts >= ? AND ts < ? "
                        + "ORDER BY ts, id")
                .params(start, end).query(JdbcPresenceHistoryStore::event).list();
    }

    @Override
    public List<StoredEvent> after(long afterId, int limit) {
        return jdbc.sql("SELECT id, ts, kind, detail, data::text AS data FROM presence.event WHERE id > ? ORDER BY id LIMIT ?")
                .params(afterId, limit).query(JdbcPresenceHistoryStore::event).list();
    }

    @Override
    public List<StoredEvent> recent(int limit, long sinceId, Collection<String> excludeKinds) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, ts, kind, detail, data::text AS data FROM presence.event WHERE id > ?");
        args.add(sinceId);
        if (!excludeKinds.isEmpty()) {
            sql.append(" AND kind <> ALL (?)");
            args.add(excludeKinds.toArray(String[]::new));
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.sql(sql.toString()).params(args).query(JdbcPresenceHistoryStore::event).list();
    }

    @Override
    public void addSample(Sample s) {
        jdbc.sql("INSERT INTO presence.sample (ts, present, seated, breath, heart) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (ts) DO UPDATE SET present = excluded.present, seated = excluded.seated, "
                        + "breath = excluded.breath, heart = excluded.heart")
                .params(s.ts(), s.present(), s.seated(), s.breath(), s.heart()).update();
    }

    @Override
    public List<Sample> samples(double start, double end) {
        return jdbc.sql("SELECT ts, present, seated, breath, heart FROM presence.sample WHERE ts >= ? AND ts < ? ORDER BY ts")
                .params(start, end)
                .query((rs, n) -> new Sample(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), nullable(rs, 4),
                        nullable(rs, 5)))
                .list();
    }

    private static StoredEvent event(ResultSet rs, int n) throws SQLException {
        return new StoredEvent(rs.getDouble("ts"), rs.getString("kind"), rs.getString("detail"),
                JsonValues.readObject(rs.getString("data")), rs.getLong("id"));
    }

    static Double nullable(ResultSet rs, int col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
