// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.domain.conversation.task.Approval;
import marvin.host.domain.conversation.task.ApprovalState;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskStatus;

/** The owner's approvals and tasks waiting for them, on PostgreSQL (Docker needed). */
@Testcontainers(disabledWithoutDocker = true)
class ApprovalStoreIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    private JdbcClient jdbc;
    private ContextMigrations migrations;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String s : ContextMigrations.SCHEMAS) {
                st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
            }
        }
        migrations = new ContextMigrations(ds, "live");
        migrations.afterPropertiesSet();
        jdbc = JdbcClient.create(ds);
    }

    @Test
    void approvalsAreKeptDecidedOnceAndExpiredByARestart() {
        JdbcTaskStore tasks = new JdbcTaskStore(jdbc, migrations);
        JdbcApprovalStore approvals = new JdbcApprovalStore(jdbc, migrations);
        Task t = Task.queued("t1", "Draft a reply to the plumber", "en", Task.VOICE, 100.0, TaskBudget.DEFAULT)
                .running(101.0, "m", "high", "xhigh").awaiting(true, "Waiting for your approval (gmail_create_draft)");
        tasks.save(t);
        assertThat(tasks.get("t1").orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_APPROVAL);

        Map<String, Object> args = Map.of("to", List.of("plumber@example.com"), "subject", "Re: Thursday", "body", "Thursday works.");
        Approval a = approvals.add(Approval.pending("t1", "gmail_create_draft", 102.0, t.title(),
                Map.of("kind", "email_draft", "to", List.of("plumber@example.com"), "subject", "Re: Thursday", "body",
                        "Thursday works."), args));
        assertThat(a.id()).isPositive();
        Approval back = approvals.get(a.id()).orElseThrow();
        assertThat(back).isEqualTo(a);
        assertThat(back.arguments()).isEqualTo(args);

        Approval b = approvals.add(Approval.pending("t1", "gmail_create_draft", 103.0, t.title(), null, args));
        approvals.update(b.reject(104.0, "wrong tone"));
        Approval rejected = approvals.get(b.id()).orElseThrow();
        assertThat(rejected.state()).isEqualTo(ApprovalState.REJECTED);
        assertThat(rejected.reason()).isEqualTo("wrong tone");
        assertThat(rejected.decided()).isEqualTo(104.0);
        assertThat(approvals.recent(50)).extracting(Approval::id).containsExactly(b.id(), a.id());
        assertThat(approvals.recent(0)).extracting(Approval::id).containsExactly(a.id());

        approvals.update(a.approve(105.0).withOpenUrl("https://mail.google.com/mail/u/0/#drafts?compose=r1"));
        assertThat(approvals.get(a.id()).orElseThrow().preview()).containsEntry("open_url",
                "https://mail.google.com/mail/u/0/#drafts?compose=r1");

        Approval c = approvals.add(Approval.pending("t1", "gmail_create_draft", 106.0, t.title(), null, args));
        assertThat(approvals.expirePending(200.0, "Marvin's host stopped while it waited")).isEqualTo(1);
        assertThat(approvals.get(c.id()).orElseThrow().state()).isEqualTo(ApprovalState.EXPIRED);
        assertThat(tasks.interruptUnfinished(200.0, "stopped")).isEqualTo(1);
        assertThat(tasks.get("t1").orElseThrow().status()).isEqualTo(TaskStatus.INTERRUPTED);
    }

    @Test
    void theDatabaseRefusesAnApprovalThatIsNotAnExternalWriteOrHalfDecided() {
        new JdbcTaskStore(jdbc, migrations).save(Task.queued("t1", "x", "en", Task.APP, 1.0, TaskBudget.DEFAULT));
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO conversation.approval (task_id, tool, access, state, created) "
                + "VALUES ('t1', 'recall', 'READ', 'pending', 1)").update()).hasMessageContaining("check");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO conversation.approval (task_id, tool, state, created) "
                + "VALUES ('t1', 'gmail_create_draft', 'approved', 1)").update()).hasMessageContaining("check");
    }
}
