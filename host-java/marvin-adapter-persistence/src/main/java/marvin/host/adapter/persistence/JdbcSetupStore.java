// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.system.port.out.SetupStore;

/** The first-run setup's answers in {@code settings.onboarding}, one JSON object per step. */
@Component
public class JdbcSetupStore implements SetupStore {
    private final JdbcClient jdbc;

    public JdbcSetupStore(JdbcClient jdbc, ContextMigrations migrated) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Map<String, Object>> load() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT step, state::text FROM settings.onboarding ORDER BY step")
                .query(rs -> {
                    out.put(rs.getString(1), JsonValues.readObject(rs.getString(2)));
                });
        return out;
    }

    @Override
    public void save(String step, Map<String, Object> state) {
        jdbc.sql("INSERT INTO settings.onboarding (step, state) VALUES (?, ?::jsonb) "
                        + "ON CONFLICT (step) DO UPDATE SET state = excluded.state, updated_at = now()")
                .params(step, JsonValues.write(state)).update();
    }
}
