// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.conversation.port.out.ApprovalStore;
import marvin.host.domain.conversation.task.Approval;
import marvin.host.domain.conversation.task.ApprovalState;
import marvin.host.domain.conversation.tool.ToolAccess;

/** The owner's approvals of tasks' external writes, in {@code conversation.approval}. */
@Component
public class JdbcApprovalStore implements ApprovalStore {
    private static final String COLUMNS = "id, task_id, tool, access, state, created, decided, summary, "
            + "preview::text AS preview, arguments::text AS arguments, reason";

    private final JdbcClient jdbc;

    public JdbcApprovalStore(JdbcClient jdbc, ContextMigrations migrated) {
        this.jdbc = jdbc;
    }

    @Override
    public Approval add(Approval a) {
        long id = jdbc.sql("INSERT INTO conversation.approval (task_id, tool, access, state, created, decided, summary, "
                        + "preview, arguments, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?::json, ?::json, ?) RETURNING id")
                .params(a.taskId(), a.tool(), a.access().name(), a.state().id(), a.created(), a.decided(), a.summary(),
                        JsonValues.write(a.preview()), JsonValues.write(a.arguments()), a.reason())
                .query(Long.class).single();
        return a.withId(id);
    }

    @Override
    public void update(Approval a) {
        jdbc.sql("UPDATE conversation.approval SET state = ?, decided = ?, preview = ?::json, reason = ? WHERE id = ?")
                .params(a.state().id(), a.decided(), JsonValues.write(a.preview()), a.reason(), a.id())
                .update();
    }

    @Override
    public Optional<Approval> get(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation.approval WHERE id = ?").params(id)
                .query(JdbcApprovalStore::approval).optional();
    }

    @Override
    public List<Approval> recent(int decided) {
        return jdbc.sql("(SELECT " + COLUMNS + " FROM conversation.approval WHERE state = 'pending') UNION ALL "
                        + "(SELECT " + COLUMNS + " FROM conversation.approval WHERE state <> 'pending' ORDER BY id DESC LIMIT ?) "
                        + "ORDER BY id DESC")
                .params(decided).query(JdbcApprovalStore::approval).list();
    }

    @Override
    public int expirePending(double at, String reason) {
        return jdbc.sql("UPDATE conversation.approval SET state = 'expired', decided = ?, reason = ? WHERE state = 'pending'")
                .params(at, reason).update();
    }

    private static Approval approval(ResultSet rs, int n) throws SQLException {
        double d = rs.getDouble("decided");
        Double decided = rs.wasNull() ? null : d;
        return new Approval(rs.getLong("id"), rs.getString("task_id"), rs.getString("tool"),
                ToolAccess.valueOf(rs.getString("access")), ApprovalState.of(rs.getString("state")), rs.getDouble("created"),
                decided, rs.getString("summary"), JsonValues.readObject(rs.getString("preview")),
                JsonValues.readObject(rs.getString("arguments")), rs.getString("reason"));
    }
}
