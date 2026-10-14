// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.conversation.port.in.ApprovalControl;
import marvin.host.domain.conversation.task.Approval;
import marvin.host.domain.shared.JsonText;

/** The approvals' API behind the real access filter, on stand-in approvals. */
class ApprovalApiTest {

    static final class FakeApprovals implements ApprovalControl {
        final List<Approval> list = new ArrayList<>(List.of(Approval.pending("t1", "gmail_create_draft", 100, "Reply to the plumber",
                Map.of("kind", "email_draft", "to", List.of("plumber@example.com"), "subject", "Re: Thursday", "body", "Fine."),
                Map.of()).withId(12)));

        @Override
        public List<Approval> approvals() {
            return list;
        }

        Approval find(long id) {
            Approval a = list.stream().filter(x -> x.id() == id).findFirst().orElseThrow(() -> new NotFound(id));
            if (a.state().decided()) {
                throw new AlreadyDecided(a);
            }
            return a;
        }

        @Override
        public Approval approve(long id) {
            Approval a = find(id).approve(110);
            list.set(0, a);
            return a;
        }

        @Override
        public Approval reject(long id, String reason) {
            Approval a = find(id).reject(110, reason);
            list.set(0, a);
            return a;
        }
    }

    final FakeApprovals approvals = new FakeApprovals();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(approvals)).setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(ResultActions r) throws Exception {
        return (Map<String, Object>) JsonText.parse(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Host", "localhost:8765").contentType("application/json").content(body));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listApproveAndDecideOnce() throws Exception {
        Map<String, Object> list = json(mvc.perform(get("/api/approvals").header("Host", "localhost:8765"))
                .andExpect(status().isOk()));
        Map<String, Object> first = ((List<Map<String, Object>>) list.get("approvals")).getFirst();
        assertThat(first).containsEntry("id", 12L).containsEntry("state", "pending").containsEntry("class", "EXTERNAL_WRITE")
                .containsEntry("tool", "gmail_create_draft");
        assertThat((Map<String, Object>) first.get("preview")).containsEntry("kind", "email_draft");
        Map<String, Object> ok = json(postJson("/api/approvals/12/approve", "{}").andExpect(status().isOk()));
        assertThat((Map<String, Object>) ok.get("approval")).containsEntry("state", "approved");
        assertThat(json(postJson("/api/approvals/12/reject", "{}").andExpect(status().isConflict())))
                .containsEntry("error", "this approval is already approved");
        postJson("/api/approvals/99/approve", "{}").andExpect(status().isNotFound());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectWithAReasonAndTheAccessRules() throws Exception {
        assertThat(json(postJson("/api/approvals/12/reject", "{\"reason\": 4}").andExpect(status().isBadRequest())))
                .containsEntry("error", "reason must be a string");
        Map<String, Object> no = json(postJson("/api/approvals/12/reject", "{\"reason\": \"not that tone\"}")
                .andExpect(status().isOk()));
        assertThat((Map<String, Object>) no.get("approval")).containsEntry("state", "rejected");
        assertThat(approvals.list.getFirst().reason()).isEqualTo("not that tone");
        // only these routes, JSON, same origin, the key from another device
        postJson("/api/approvals/12/send", "{}").andExpect(status().isNotFound());
        postJson("/api/approvals/x/approve", "{}").andExpect(status().isNotFound());
        mvc.perform(post("/api/approvals/12/approve").header("Host", "localhost:8765").header("Origin", "http://evil.example")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/approvals/12/approve").header("Host", "192.168.1.20:8765").with(r -> {
            r.setRemoteAddr("192.168.1.30");
            return r;
        }).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
}
