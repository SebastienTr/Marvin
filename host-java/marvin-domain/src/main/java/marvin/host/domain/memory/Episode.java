// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A summary of a period (docs/design.md 5.2, step 4). {@code stale}: something it was made from was forgotten,
 * so the next nightly pass rewrites it.
 *
 * @param day the period's first local day (its {@code periodStart} in the owner's zone)
 */
public record Episode(long id, EpisodeLevel level, LocalDate day, Instant periodStart, Instant periodEnd, String summary,
                      boolean stale, Instant createdAt, int events) {

    public Episode {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(day, "day");
        summary = summary == null ? "" : summary;
    }
}
