// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.Locale;

/**
 * Where an approval is (docs/design.md 6.3): waiting for the owner, then approved, rejected, or expired (nobody answered in
 * time, its task was cancelled, or the host stopped while it waited).
 */
public enum ApprovalState {
    PENDING, APPROVED, REJECTED, EXPIRED;

    /** The name the API and the database use. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ApprovalState of(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }

    /** It will not change any more. */
    public boolean decided() {
        return this != PENDING;
    }
}
