// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.conversation.port.in.ApprovalControl;
import marvin.host.domain.conversation.task.Approval;

/**
 * The owner's approvals (docs/design.md 6.3; golden/api/get/approvals.json): a background task that wants to write
 * outside this computer (an email draft) waits here. Changes arrive on the event stream as {@code approval}
 * ({@code {id, task_id, state, tool, summary}}).
 *
 * <ul>
 * <li>{@code GET /api/approvals}: {@code approvals}, newest first: the pending ones and the last 50 decided;</li>
 * <li>{@code POST /api/approvals/{id}/approve}: the approval, approved (its task makes the call); 404 when there is none,
 * 409 when it was decided already;</li>
 * <li>{@code POST /api/approvals/{id}/reject} {@code {"reason": "..."}} (optional): the approval, rejected (the call is
 * never made).</li>
 * </ul>
 */
@RestController
public class ApprovalController {
    static final int MAX_REASON = 500;

    private final ApprovalControl approvals;

    public ApprovalController(ApprovalControl approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/api/approvals")
    public ResponseEntity<byte[]> list() {
        return Responses.json(Map.of("approvals", approvals.approvals().stream().map(Approval::toMap).toList()));
    }

    @PostMapping("/api/approvals/{id}/approve")
    public ResponseEntity<byte[]> approve(@PathVariable long id) {
        try {
            return Responses.json(Map.of("approval", approvals.approve(id).toMap()));
        } catch (ApprovalControl.NotFound e) {
            return Responses.error(404, "no such approval");
        } catch (ApprovalControl.AlreadyDecided e) {
            return Responses.error(409, e.getMessage());
        }
    }

    @PostMapping("/api/approvals/{id}/reject")
    public ResponseEntity<byte[]> reject(@PathVariable long id, HttpServletRequest rq) {
        Object body = ApiController.body(rq);
        if (!(body instanceof Map<?, ?> m)) {
            throw new ApiController.BadRequest("send a JSON object");
        }
        Object reason = m.get("reason");
        if (reason != null && !(reason instanceof String)) {
            throw new ApiController.BadRequest("reason must be a string");
        }
        String r = reason == null ? "" : ((String) reason).strip();
        if (r.length() > MAX_REASON) {
            throw new ApiController.BadRequest("a reason is at most " + MAX_REASON + " characters");
        }
        try {
            return Responses.json(Map.of("approval", approvals.reject(id, r).toMap()));
        } catch (ApprovalControl.NotFound e) {
            return Responses.error(404, "no such approval");
        } catch (ApprovalControl.AlreadyDecided e) {
            return Responses.error(409, e.getMessage());
        }
    }
}
