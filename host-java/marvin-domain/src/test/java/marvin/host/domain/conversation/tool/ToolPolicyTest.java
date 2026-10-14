// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tool filter (docs/design.md 6.3): Marvin can never send or delete an email, nor do anything else irreversible
 * outside this computer; a tool that writes outside it waits for the owner's approval in a task and is never offered in
 * the conversation.
 */
class ToolPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"gmail_send", "send_email", "gmail_send_draft", "sendMessage", "drafts_send", "gmail_delete",
            "delete_message", "deleteDraft", "gmail_trash_thread", "trash_message", "forward", "gmail_forward",
            "reply_to_email", "gmail_reply", "calendar_delete_event", "respond_to_event", "remove_event", "purge_mailbox",
            "pay_invoice", "share_file", "publish_post", "mark_spam", "archive_thread", "cancel_event", "invite_people",
            "send2", "SEND", "Gmail_Send_Now", "resend_message", "undelete", "autoforward_rule"})
    void irreversibleOrOutwardToolsAreRefused(String name) {
        for (ToolAccess access : ToolAccess.values()) {
            assertThat(ToolPolicy.refusal(name, access)).as(name + " as " + access).isNotNull();
            assertThat(ToolPolicy.allowed(name, access)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gmail_search", "gmail_create_draft", "calendar_list_events", "get_weather", "recall", "remember",
            "forget", "look", "start_task", "task_result", "final_answer"})
    void marvinsOwnToolsAndTheConnectorsAreAllowed(String name) {
        assertThat(ToolPolicy.refusal(name, ToolAccess.READ)).isNull();
    }

    @Test
    void wordsSplitOnUnderscoresDigitsAndCamelCase() {
        assertThat(ToolPolicy.words("gmail_createDraft2now")).containsExactly("gmail", "create", "draft", "now");
        assertThat(ToolPolicy.words("sendMail")).containsExactly("send", "mail");
        assertThat(ToolPolicy.words("resend")).containsExactly("resend");
    }

    @Test
    void anExternalWriteIsOfferedOnlyInATaskThatAsksForApprovals() {
        for (ToolSurface s : ToolSurface.values()) {
            assertThat(ToolPolicy.offerable(ToolAccess.READ, s, false)).isTrue();
            assertThat(ToolPolicy.offerable(ToolAccess.LOCAL_WRITE, s, false)).isTrue();
        }
        assertThat(ToolPolicy.offerable(ToolAccess.EXTERNAL_WRITE, ToolSurface.CONVERSATION, true)).isFalse();
        assertThat(ToolPolicy.offerable(ToolAccess.EXTERNAL_WRITE, ToolSurface.CONVERSATION, false)).isFalse();
        assertThat(ToolPolicy.offerable(ToolAccess.EXTERNAL_WRITE, ToolSurface.TASK, false)).isFalse();
        assertThat(ToolPolicy.offerable(ToolAccess.EXTERNAL_WRITE, ToolSurface.TASK, true)).isTrue();
    }

    @Test
    void theForbiddenWordsCoverSendingAndDeleting() {
        assertThat(ToolPolicy.FORBIDDEN_WORDS).containsAll(List.of("send", "delete", "trash", "forward", "reply"));
    }
}
