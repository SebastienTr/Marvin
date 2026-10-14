// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.vitals.port.out.VitalsStore;
import marvin.host.domain.vitals.VitalsMinute;

/** The vital signs per minute, in the {@code presence} schema (migration presence/V3). */
@Component
public class JdbcVitalsStore implements VitalsStore {
    private final JdbcClient jdbc;

    public JdbcVitalsStore(JdbcClient jdbc, ContextMigrations migrated) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(VitalsMinute m) {
        jdbc.sql("INSERT INTO presence.vitals_minute (ts, heart, breath, n) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (ts) DO UPDATE SET heart = excluded.heart, breath = excluded.breath, n = excluded.n")
                .params(m.t(), m.hr(), m.br(), m.n()).update();
    }

    @Override
    public List<VitalsMinute> minutes(double start, double end) {
        return jdbc.sql("SELECT ts, heart, breath, n FROM presence.vitals_minute WHERE ts >= ? AND ts < ? ORDER BY ts")
                .params(start, end)
                .query((rs, i) -> new VitalsMinute(rs.getDouble(1), JdbcPresenceHistoryStore.nullable(rs, 2),
                        JdbcPresenceHistoryStore.nullable(rs, 3), rs.getInt(4)))
                .list();
    }

    @Override
    public int deleteBefore(double t) {
        return jdbc.sql("DELETE FROM presence.vitals_minute WHERE ts < ?").param(t).update();
    }
}
