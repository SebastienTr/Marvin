// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;

/** Day, week and month summaries (docs/design.md 5.4, {@code episode}). */
public interface EpisodeStore {

    Optional<Episode> get(EpisodeLevel level, LocalDate day);

    /** Adds or replaces the summary of a period (one per level and start); {@code stale} is cleared. */
    Episode put(Episode episode, float[] embedding);

    /** Episodes of a level whose first day is in {@code [from, to)}, oldest first. */
    List<Episode> list(EpisodeLevel level, LocalDate from, LocalDate to);

    /** The most recent episode of a level, if any. */
    Optional<Episode> latest(EpisodeLevel level);

    /**
     * Marks the episodes that overlap {@code [from, to)} for rewriting and blanks their summary at once (what they
     * said may be what was just forgotten); returns how many.
     */
    int markStale(Instant from, Instant to);

    /** Deletes the summary of a period (a stale day with nothing left to summarise). */
    void delete(EpisodeLevel level, LocalDate day);

    List<Episode> stale();

    void deleteAll();
}
