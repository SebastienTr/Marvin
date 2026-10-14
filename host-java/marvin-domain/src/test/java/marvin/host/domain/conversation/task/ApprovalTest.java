// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.domain.conversation.tool.ToolAccess;

/** The approval state machine (docs/design.md 6.3): pending, then approved, rejected or expired, once. */
class ApprovalTest {
    final Approval pending = Approval.pending("t1", "gmail_create_draft", 100, "Reply to the plumber",
            Map.of("kind", "email_draft", "to", List.of("plumber@example.com"), "subject", "Re: Thursday", "body", "Thursday works."),
            Map.of("to", List.of("plumber@example.com"), "subject", "Re: Thursday", "body", "Thursday works.")).withId(7);

    @Test
    void decidedOnceAndOnlyOnce() {
        assertThat(pending.state()).isEqualTo(ApprovalState.PENDING);
        assertThat(pending.decided()).isNull();
        Approval ok = pending.approve(110);
        assertThat(ok.state()).isEqualTo(ApprovalState.APPROVED);
        assertThat(ok.decided()).isEqualTo(110.0);
        assertThatThrownBy(() -> ok.reject(120, "no")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ok.expire(120, "late")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ok.approve(120)).isInstanceOf(IllegalStateException.class);
        Approval no = pending.reject(111, "  not that tone  ");
        assertThat(no.state()).isEqualTo(ApprovalState.REJECTED);
        assertThat(no.reason()).isEqualTo("not that tone");
        assertThat(pending.expire(112, "no answer").state()).isEqualTo(ApprovalState.EXPIRED);
        assertThat(ApprovalState.of("expired")).isEqualTo(ApprovalState.EXPIRED);
    }

    @Test
    void onlyExternalWritesNeedOne() {
        for (ToolAccess a : List.of(ToolAccess.READ, ToolAccess.LOCAL_WRITE)) {
            assertThatThrownBy(() -> new Approval(1, "t", "recall", a, ApprovalState.PENDING, 1, null, "", null, null, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Approval(1, "t", "x", ToolAccess.EXTERNAL_WRITE, ApprovalState.APPROVED, 1, null, "", null,
                null, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void asTheAppListsItAndAsTheStreamSaysIt() {
        Map<String, Object> m = pending.withOpenUrl("https://mail.google.com/mail/u/0/#drafts").toMap();
        assertThat(m.keySet()).containsExactly("id", "task_id", "tool", "class", "state", "created", "decided", "summary",
                "preview");
        assertThat(m).containsEntry("class", "EXTERNAL_WRITE").containsEntry("state", "pending").containsEntry("task_id", "t1");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) m.get("preview");
        assertThat(p.keySet()).containsExactly("kind", "to", "subject", "body", "open_url");
        assertThat(p).containsEntry("kind", "email_draft").containsEntry("open_url", "https://mail.google.com/mail/u/0/#drafts");
        assertThat(pending.toEventMap().keySet()).containsExactly("id", "task_id", "state", "tool", "summary");
    }

    @Test
    void aCallWithoutAPreviewShowsItsArguments() {
        Map<String, Object> p = Approval.preview("calendar_create_event", Map.of("title", "Football"), null);
        assertThat(p).containsEntry("kind", Approval.TOOL_CALL).containsEntry("subject", "calendar_create_event")
                .containsEntry("open_url", null);
        assertThat((String) p.get("body")).contains("Football");
    }

    @Test
    void theTaskWaitsInNeedsApproval() {
        Task t = Task.queued("t1", "Draft a reply", "en", Task.VOICE, 1, TaskBudget.DEFAULT).running(2, "m", "high", "xhigh");
        Task w = t.awaiting(true, ApprovalText.waiting("gmail_create_draft"));
        assertThat(w.status()).isEqualTo(TaskStatus.NEEDS_APPROVAL);
        assertThat(w.status().id()).isEqualTo("needs_approval");
        assertThat(w.status().finished()).isFalse();
        assertThat(w.awaiting(false, "Back").status()).isEqualTo(TaskStatus.RUNNING);
        Task done = w.finished(TaskStatus.CANCELLED, 3, "", "", "Cancelled", null);
        assertThatThrownBy(() -> done.awaiting(true, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marvinMentionsItOnceInTheOwnersLanguage() {
        assertThat(ApprovalText.spoken("fr", "Répondre au plombier")).isEqualTo(
                "Pour « Répondre au plombier », j'ai besoin de ton accord dans l'appli.");
        assertThat(ApprovalText.spoken("xx", "Reply")).isEqualTo("For “Reply”, I need your approval in the app.");
        assertThat(ApprovalText.rejected("wrong tone")).contains("(wrong tone)").contains("Do not ask again");
    }
}
