// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.util.List;
import java.util.Optional;

import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;

/** The versions of the profile (and later the soul) blocks (docs/design.md 5.4, {@code block_version}). */
public interface ProfileStore {

    Optional<BlockVersion> active(Block block);

    /**
     * Adds a version. An {@code active} one replaces the active version, which becomes {@code superseded}, in the
     * same transaction. Returns it with its id.
     */
    BlockVersion add(BlockVersion version);

    Optional<BlockVersion> get(long id);

    /** Newest first. */
    List<BlockVersion> versions(Block block, int limit);

    void deleteAll();
}
