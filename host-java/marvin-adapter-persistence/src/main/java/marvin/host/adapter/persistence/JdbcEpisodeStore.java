// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;

/** Episodes in {@code memory.episode}. */
@Component
public class JdbcEpisodeStore implements EpisodeStore {
    static final String COLUMNS = "id, level, day, period_start, period_end, summary, stale, created_at, events";

    private final JdbcClient jdbc;
    private final MemoryRows.Vectors vectors;

    public JdbcEpisodeStore(JdbcClient jdbc, ContextMigrations migrated,
                            @Value("${marvin.memory.embedding-dimensions:1024}") int dimensions) {
        this.jdbc = jdbc;
        this.vectors = MemoryRows.Vectors.of(jdbc, "episode", dimensions);
    }

    @Override
    public Optional<Episode> get(EpisodeLevel level, LocalDate day) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.episode WHERE level = ? AND day = ?")
                .params(level.wire(), day).query(JdbcEpisodeStore::episode).optional();
    }

    @Override
    public Episode put(Episode e, float[] embedding) {
        return jdbc.sql("INSERT INTO memory.episode (level, day, period_start, period_end, summary, embedding, stale, created_at, events) "
                        + "VALUES (?, ?, ?, ?, ?, CAST(? AS " + vectors.type() + "), false, ?, ?) "
                        + "ON CONFLICT (level, day) DO UPDATE SET period_start = excluded.period_start, period_end = excluded.period_end, "
                        + "summary = excluded.summary, embedding = excluded.embedding, stale = false, created_at = excluded.created_at, "
                        + "events = excluded.events RETURNING " + COLUMNS)
                .params(e.level().wire(), e.day(), MemoryRows.at(e.periodStart()), MemoryRows.at(e.periodEnd()), e.summary(),
                        vectors.literal(embedding), MemoryRows.at(e.createdAt()), e.events())
                .query(JdbcEpisodeStore::episode).single();
    }

    @Override
    public List<Episode> list(EpisodeLevel level, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.episode WHERE level = ? AND day >= ? AND day < ? ORDER BY day")
                .params(level.wire(), from, to).query(JdbcEpisodeStore::episode).list();
    }

    @Override
    public Optional<Episode> latest(EpisodeLevel level) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.episode WHERE level = ? ORDER BY day DESC LIMIT 1")
                .param(level.wire()).query(JdbcEpisodeStore::episode).optional();
    }

    @Override
    public int markStale(Instant from, Instant to) {
        return jdbc.sql("UPDATE memory.episode SET stale = true, summary = '', embedding = NULL WHERE period_start < ? AND period_end > ?")
                .params(MemoryRows.at(to), MemoryRows.at(from)).update();
    }

    @Override
    public void delete(EpisodeLevel level, LocalDate day) {
        jdbc.sql("DELETE FROM memory.episode WHERE level = ? AND day = ?").params(level.wire(), day).update();
    }

    @Override
    public List<Episode> stale() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.episode WHERE stale ORDER BY day").query(JdbcEpisodeStore::episode).list();
    }

    @Override
    public void deleteAll() {
        jdbc.sql("DELETE FROM memory.episode").update();
    }

    static Episode episode(ResultSet rs, int n) throws SQLException {
        return new Episode(rs.getLong("id"), EpisodeLevel.parse(rs.getString("level")), rs.getObject("day", LocalDate.class),
                MemoryRows.instant(rs, "period_start"), MemoryRows.instant(rs, "period_end"), rs.getString("summary"),
                rs.getBoolean("stale"), MemoryRows.instant(rs, "created_at"), rs.getInt("events"));
    }
}
