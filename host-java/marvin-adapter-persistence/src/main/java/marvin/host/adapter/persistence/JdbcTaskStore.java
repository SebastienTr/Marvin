// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.conversation.port.out.TaskStore;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskSpent;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.task.TaskStep;

/** Background tasks in {@code conversation.task}, their steps in {@code conversation.task_step}. */
@Component
public class JdbcTaskStore implements TaskStore {
    private static final String COLUMNS = "id, goal, title, language, origin, status, created_at, started_at, finished_at, "
            + "model, reasoning, think, budget::text AS budget, spent::text AS spent, summary, result, error, announced, "
            + "announced_at";

    private final JdbcClient jdbc;

    public JdbcTaskStore(JdbcClient jdbc, ContextMigrations migrated) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Task t) {
        jdbc.sql("INSERT INTO conversation.task (id, goal, title, language, origin, status, created_at, started_at, "
                        + "finished_at, model, reasoning, think, budget, spent, summary, result, error, announced, announced_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::json, ?::json, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET goal = excluded.goal, title = excluded.title, "
                        + "language = excluded.language, origin = excluded.origin, status = excluded.status, "
                        + "started_at = excluded.started_at, finished_at = excluded.finished_at, model = excluded.model, "
                        + "reasoning = excluded.reasoning, think = excluded.think, budget = excluded.budget, "
                        + "spent = excluded.spent, summary = excluded.summary, result = excluded.result, error = excluded.error, "
                        + "announced = excluded.announced, announced_at = excluded.announced_at")
                .params(t.id(), t.goal(), t.title(), t.language(), t.origin(), t.status().id(), t.createdAt(), t.startedAt(),
                        t.finishedAt(), t.model(), t.reasoning(), t.think(), JsonValues.write(t.budget().toMap()),
                        JsonValues.write(t.spent().toMap()), t.summary(), t.result(), t.error(), t.announced(),
                        t.announcedAt())
                .update();
    }

    @Override
    public void addStep(String taskId, TaskStep step) {
        jdbc.sql("INSERT INTO conversation.task_step (task_id, n, at, kind, data) VALUES (?, ?, ?, ?, ?::json) "
                        + "ON CONFLICT (task_id, n) DO NOTHING")
                .params(taskId, step.n(), step.at(), step.kind(), JsonValues.write(step.data())).update();
    }

    @Override
    public Optional<Task> get(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation.task WHERE id = ?").params(id)
                .query(JdbcTaskStore::task).optional();
    }

    @Override
    public List<TaskStep> steps(String id) {
        return jdbc.sql("SELECT n, at, kind, data::text AS data FROM conversation.task_step WHERE task_id = ? ORDER BY n")
                .params(id)
                .query((rs, i) -> new TaskStep(rs.getInt("n"), rs.getDouble("at"), rs.getString("kind"),
                        JsonValues.readObject(rs.getString("data"))))
                .list();
    }

    @Override
    public List<Task> recent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation.task ORDER BY created_at DESC, id LIMIT ?")
                .params(limit).query(JdbcTaskStore::task).list();
    }

    @Override
    public int interruptUnfinished(double at, String error) {
        return jdbc.sql("UPDATE conversation.task SET status = 'interrupted', finished_at = ?, error = ? "
                        + "WHERE status IN ('queued', 'running', 'needs_approval')")
                .params(at, error).update();
    }

    private static Task task(ResultSet rs, int n) throws SQLException {
        return new Task(rs.getString("id"), rs.getString("goal"), rs.getString("title"), rs.getString("language"),
                rs.getString("origin"), TaskStatus.of(rs.getString("status")), rs.getDouble("created_at"),
                nullable(rs, "started_at"), nullable(rs, "finished_at"), rs.getString("model"), rs.getString("reasoning"),
                rs.getString("think"), TaskBudget.of(JsonValues.readObject(rs.getString("budget"))),
                TaskSpent.of(JsonValues.readObject(rs.getString("spent"))), "", rs.getString("summary"),
                rs.getString("result"), rs.getString("error"), rs.getString("announced"), nullable(rs, "announced_at"));
    }

    private static Double nullable(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }
}
