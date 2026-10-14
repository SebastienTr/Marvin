// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;
import java.util.Optional;

import marvin.host.domain.conversation.task.Approval;

/** The owner's approvals of tasks' external writes, kept across restarts (the conversation's schema: {@code approval}). */
public interface ApprovalStore {

    /** Stores a new approval ({@code id} 0) and returns it with its id. */
    Approval add(Approval approval);

    /** Replaces a stored approval (its decision, its preview's link). */
    void update(Approval approval);

    Optional<Approval> get(long id);

    /** Every pending approval and the last {@code decided} decided ones, newest first. */
    List<Approval> recent(int decided);

    /**
     * After a restart: every pending approval is now expired, with {@code reason}, at {@code at} (its task was
     * interrupted).
     *
     * @return how many
     */
    int expirePending(double at, String reason);
}
