// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.Locale;

/**
 * Where a background task is (docs/design.md 6.2): waiting its turn, running, waiting for the owner's approval of a call
 * that writes outside this computer ({@code needs_approval}), then done, failed (an error, or a budget spent), cancelled
 * by the owner, or interrupted by the host stopping while it ran.
 */
public enum TaskStatus {
    QUEUED, RUNNING, NEEDS_APPROVAL, DONE, FAILED, CANCELLED, INTERRUPTED;

    /** The name the API and the database use ({@code queued}, {@code running} ...). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TaskStatus of(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }

    /** It will not change any more. */
    public boolean finished() {
        return this != QUEUED && this != RUNNING && this != NEEDS_APPROVAL;
    }
}
