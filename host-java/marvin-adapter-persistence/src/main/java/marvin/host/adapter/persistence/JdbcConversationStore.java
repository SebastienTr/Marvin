// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.domain.conversation.ConversationEntry;

/** The conversation in {@code conversation.entry}. */
@Component
public class JdbcConversationStore implements ConversationStore {
    private final JdbcClient jdbc;

    public JdbcConversationStore(JdbcClient jdbc, ContextMigrations migrated) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(ConversationEntry e) {
        jdbc.sql("INSERT INTO conversation.entry (id, ts, kind, text, data) VALUES (?, ?, ?, ?, ?::json) "
                        + "ON CONFLICT (id) DO UPDATE SET ts = excluded.ts, kind = excluded.kind, text = excluded.text, "
                        + "data = excluded.data")
                .params(e.id(), e.t(), e.kind(), e.text(), JsonValues.write(e.data())).update();
    }

    @Override
    public List<ConversationEntry> between(double start, double end, int limit) {
        List<ConversationEntry> newest = jdbc.sql("SELECT id, ts, kind, text, data::text AS data FROM conversation.entry "
                        + "WHERE ts >= ? AND ts < ? ORDER BY ts DESC, id DESC LIMIT ?")
                .params(start, end, limit).query(JdbcConversationStore::entry).list();
        return newest.reversed();
    }

    @Override
    public List<ConversationEntry> search(String query, int limit) {
        String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("SELECT id, ts, kind, text, data::text AS data FROM conversation.entry "
                        + "WHERE kind IN ('heard', 'reply') AND text ILIKE ? ESCAPE '\\' ORDER BY ts DESC, id DESC LIMIT ?")
                .params(like, limit).query(JdbcConversationStore::entry).list();
    }

    @Override
    public long maxId() {
        return jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM conversation.entry").query(Long.class).single();
    }

    private static ConversationEntry entry(ResultSet rs, int n) throws SQLException {
        return new ConversationEntry(rs.getLong("id"), rs.getDouble("ts"), rs.getString("kind"), rs.getString("text"),
                JsonValues.readObject(rs.getString("data")));
    }
}
