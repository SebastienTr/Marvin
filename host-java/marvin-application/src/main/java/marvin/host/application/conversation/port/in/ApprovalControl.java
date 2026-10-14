// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.in;

import java.util.List;

import marvin.host.domain.conversation.task.Approval;

/**
 * The owner's approvals for the app (docs/design.md 6.3): a background task that wants to write outside this computer
 * (an email draft) waits until the owner approves or rejects the call here.
 */
public interface ApprovalControl {

    /** Every pending approval and the last 50 decided ones, newest first. */
    List<Approval> approvals();

    /**
     * Approves a pending call: its task runs it.
     *
     * @throws NotFound       no such approval
     * @throws AlreadyDecided it was approved, rejected or expired already
     */
    Approval approve(long id);

    /**
     * Rejects a pending call: its task is told, and goes on without it.
     *
     * @throws NotFound       no such approval
     * @throws AlreadyDecided it was approved, rejected or expired already
     */
    Approval reject(long id, String reason);

    /** No approval with this id. */
    class NotFound extends RuntimeException {
        public NotFound(long id) {
            super("no such approval: " + id);
        }
    }

    /** The approval was decided already. */
    class AlreadyDecided extends RuntimeException {
        public AlreadyDecided(Approval a) {
            super("this approval is already " + a.state().id());
        }
    }
}
