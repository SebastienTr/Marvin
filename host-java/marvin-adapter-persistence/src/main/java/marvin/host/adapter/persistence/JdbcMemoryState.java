// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.memory.port.out.MemoryStateStore;

/** Memory's small state in {@code memory.state}. */
@Component
public class JdbcMemoryState implements MemoryStateStore {
    private final JdbcClient jdbc;

    public JdbcMemoryState(JdbcClient jdbc, ContextMigrations migrated) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Object> get(String key) {
        return jdbc.sql("SELECT value::text FROM memory.state WHERE key = ?").param(key).query(String.class).optional()
                .map(JsonValues::readObject).orElseGet(LinkedHashMap::new);
    }

    @Override
    public void put(String key, Map<String, Object> value) {
        jdbc.sql("INSERT INTO memory.state (key, value) VALUES (?, CAST(? AS jsonb)) "
                        + "ON CONFLICT (key) DO UPDATE SET value = excluded.value")
                .params(key, JsonValues.write(value)).update();
    }
}
