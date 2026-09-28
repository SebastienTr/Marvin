// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import marvin.host.application.settings.port.out.SettingsStore;

/** The settings in {@code settings.setting}, one JSON value per key. */
@Component
public class JdbcSettingsStore implements SettingsStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcSettingsStore(JdbcClient jdbc, TransactionTemplate tx, ContextMigrations migrated) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Map<String, Object> load() {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value::text FROM settings.setting ORDER BY key")
                .query(rs -> {
                    out.put(rs.getString(1), JsonValues.read(rs.getString(2)));
                });
        return out;
    }

    @Override
    public void save(Map<String, Object> values) {
        tx.executeWithoutResult(s -> values.forEach((k, v) ->
                jdbc.sql("INSERT INTO settings.setting (key, value) VALUES (?, ?::jsonb) "
                                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value")
                        .params(k, JsonValues.write(v)).update()));
    }
}
