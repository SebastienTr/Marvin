// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.ProfileText;

/** The profile, the episodes and the raw log, as the app shows them (docs/design.md 5.6). */
public interface BrowseMemory {

    /** A version of the profile with its diff to the version before it. */
    record ProfileVersion(BlockVersion version, List<ProfileText.DiffLine> diff) {
    }

    Optional<BlockVersion> profile();

    /** Newest first. */
    List<ProfileVersion> profileVersions(int limit);

    /**
     * The owner writes the profile: a new active version; lines the owner added or changed are kept verbatim by every
     * later rewrite, as are {@code pinnedLines}.
     */
    BlockVersion editProfile(String content, List<String> pinnedLines);

    /** Makes an older version active again (as a new version, authored by the owner). */
    BlockVersion restoreProfile(long versionId);

    List<Episode> episodes(EpisodeLevel level, LocalDate from, LocalDate to);

    /** The raw log, newest first. */
    List<MemoryEvent> log(String query, long beforeId, int limit);
}
