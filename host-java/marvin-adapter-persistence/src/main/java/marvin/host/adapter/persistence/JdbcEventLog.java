// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import marvin.host.application.memory.port.out.EventLog;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Sensitivity;

/** Memory's event log in {@code memory.event_log}. */
@Component
public class JdbcEventLog implements EventLog {
    static final String COLUMNS = "id, ts, recorded_at, source, kind, sensitivity, external_ref, body, data::text AS data, "
            + "consolidated_at";
    private static final String INSERT = "INSERT INTO memory.event_log (ts, source, kind, sensitivity, external_ref, body, data) "
            + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) "
            + "ON CONFLICT (source, external_ref) WHERE external_ref IS NOT NULL DO NOTHING";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcEventLog(JdbcClient jdbc, TransactionTemplate tx, ContextMigrations migrated) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static Object[] params(MemoryEvent e) {
        // "secret" is never stored: such an event keeps the strictest stored label (its body is already redacted)
        Sensitivity s = e.sensitivity() == Sensitivity.SECRET ? Sensitivity.SENSITIVE : e.sensitivity();
        return new Object[] {MemoryRows.at(e.ts()), e.source(), e.kind(), s.wire(), e.externalRef(), e.body(),
                JsonValues.write(e.data())};
    }

    @Override
    public int append(List<MemoryEvent> drafts) {
        if (drafts.isEmpty()) {
            return 0;
        }
        Integer n = tx.execute(status -> {
            int added = 0;
            for (MemoryEvent e : drafts) {
                added += jdbc.sql(INSERT).params(params(e)).update();
            }
            return added;
        });
        return n == null ? 0 : n;
    }

    @Override
    public Optional<MemoryEvent> appendOne(MemoryEvent draft) {
        return jdbc.sql(INSERT + " RETURNING " + COLUMNS).params(params(draft)).query(JdbcEventLog::event).optional();
    }

    @Override
    public List<MemoryEvent> unconsolidated(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.event_log WHERE consolidated_at IS NULL AND NOT withheld ORDER BY id LIMIT ?")
                .param(limit).query(JdbcEventLog::event).list();
    }

    @Override
    public long unconsolidatedCount() {
        return jdbc.sql("SELECT count(*) FROM memory.event_log WHERE consolidated_at IS NULL AND NOT withheld").query(Long.class).single();
    }

    @Override
    public void markConsolidated(Collection<Long> ids, Instant at) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE memory.event_log SET consolidated_at = ? WHERE id = ANY(?)")
                    .params(MemoryRows.at(at), ids.toArray(Long[]::new)).update();
        }
    }

    @Override
    public List<MemoryEvent> between(Instant from, Instant to, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.event_log WHERE ts >= ? AND ts < ? AND NOT withheld ORDER BY ts, id LIMIT ?")
                .params(MemoryRows.at(from), MemoryRows.at(to), limit).query(JdbcEventLog::event).list();
    }

    @Override
    public List<MemoryEvent> byIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.event_log WHERE id = ANY(?) AND NOT withheld ORDER BY ts, id")
                .param(ids.toArray(Long[]::new)).query(JdbcEventLog::event).list();
    }

    @Override
    public Optional<MemoryEvent> byRef(String source, String externalRef) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.event_log WHERE source = ? AND external_ref = ? AND NOT withheld")
                .params(source, externalRef).query(JdbcEventLog::event).optional();
    }

    @Override
    public List<MemoryEvent> recent(String query, long beforeId, int limit) {
        String like = "%" + query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.event_log WHERE NOT withheld AND (? = 0 OR id < ?) "
                        + "AND (? = '' OR body ILIKE ? ESCAPE '\\') ORDER BY id DESC LIMIT ?")
                .params(beforeId, beforeId, query.strip(), like, limit).query(JdbcEventLog::event).list();
    }

    @Override
    public Optional<Instant> first() {
        return jdbc.sql("SELECT min(ts) AS ts FROM memory.event_log")
                .query((rs, n) -> Optional.ofNullable(MemoryRows.instant(rs, "ts")))
                .single();
    }

    @Override
    public void redact(long id, String body) {
        jdbc.sql("UPDATE memory.event_log SET body = ? WHERE id = ?").params(body, id).update();
    }

    @Override
    public void relabel(Collection<Long> ids, Sensitivity s) {
        if (ids.isEmpty() || s == Sensitivity.NORMAL) {
            return;
        }
        String to = (s == Sensitivity.SECRET ? Sensitivity.SENSITIVE : s).wire();
        // only ever raised: sensitive > personal > normal
        String lower = to.equals("sensitive") ? "('normal', 'personal')" : "('normal')";
        jdbc.sql("UPDATE memory.event_log SET sensitivity = ? WHERE id = ANY(?) AND sensitivity IN " + lower)
                .params(to, ids.toArray(Long[]::new)).update();
    }

    @Override
    public int withhold(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE memory.event_log SET withheld = true, consolidated_at = coalesce(consolidated_at, now()) "
                        + "WHERE id = ANY(?) AND NOT withheld")
                .param(ids.toArray(Long[]::new)).update();
    }

    @Override
    public List<LocalDate> days(Instant from, ZoneId zone, int limit) {
        return jdbc.sql("SELECT DISTINCT (ts AT TIME ZONE ?)::date AS d FROM memory.event_log WHERE ts >= ? ORDER BY d LIMIT ?")
                .params(zone.getId(), MemoryRows.at(from), limit)
                .query((rs, n) -> rs.getObject("d", LocalDate.class)).list();
    }

    @Override
    public int delete(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM memory.event_log WHERE id = ANY(?)").param(ids.toArray(Long[]::new)).update();
    }

    @Override
    public int deleteBetween(Instant from, Instant to) {
        return jdbc.sql("DELETE FROM memory.event_log WHERE ts >= ? AND ts < ?")
                .params(MemoryRows.at(from), MemoryRows.at(to)).update();
    }

    @Override
    public int deleteUnreferenced(String source, Instant before) {
        return jdbc.sql("DELETE FROM memory.event_log e WHERE e.source = ? AND e.ts < ? "
                        + "AND NOT EXISTS (SELECT 1 FROM memory.fact_source s WHERE s.event_id = e.id)")
                .params(source, MemoryRows.at(before)).update();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM memory.event_log").query(Long.class).single();
    }

    @Override
    public List<MemoryEvent> page(long afterId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.event_log WHERE id > ? AND NOT withheld ORDER BY id LIMIT ?")
                .params(afterId, limit).query(JdbcEventLog::event).list();
    }

    @Override
    public void deleteAll() {
        jdbc.sql("DELETE FROM memory.event_log").update();
    }

    static MemoryEvent event(ResultSet rs, int n) throws SQLException {
        return new MemoryEvent(rs.getLong("id"), MemoryRows.instant(rs, "ts"), MemoryRows.instant(rs, "recorded_at"),
                rs.getString("source"), rs.getString("kind"), Sensitivity.parse(rs.getString("sensitivity"), Sensitivity.NORMAL),
                rs.getString("external_ref"), rs.getString("body"), JsonValues.readObject(rs.getString("data")),
                MemoryRows.instant(rs, "consolidated_at"));
    }
}
