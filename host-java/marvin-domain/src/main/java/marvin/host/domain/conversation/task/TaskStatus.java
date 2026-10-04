// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.Locale;

/**
 * Where a background task is (docs/design.md 6.2, without approvals yet): waiting its turn, running, then done, failed
 * (an error, or a budget spent), cancelled by the owner, or interrupted by the host stopping while it ran.
 */
public enum TaskStatus {
    QUEUED, RUNNING, DONE, FAILED, CANCELLED, INTERRUPTED;

    /** The name the API and the database use ({@code queued}, {@code running} ...). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TaskStatus of(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }

    /** It will not change any more. */
    public boolean finished() {
        return this != QUEUED && this != RUNNING;
    }
}
