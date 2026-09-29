// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;

/**
 * The profile store with the active versions kept in memory: the voice reads the profile for every question's system
 * prompt without a database round trip. Every write goes through here (memory's services share one instance), so
 * the cache is dropped exactly when a version is added or everything is forgotten.
 */
public final class CachedProfiles implements ProfileStore {
    private final ProfileStore store;
    private final Map<Block, Optional<BlockVersion>> active = new EnumMap<>(Block.class);

    public CachedProfiles(ProfileStore store) {
        this.store = store;
    }

    @Override
    public Optional<BlockVersion> active(Block block) {
        synchronized (active) {
            Optional<BlockVersion> v = active.get(block);
            if (v == null) {
                v = store.active(block);
                active.put(block, v);
            }
            return v;
        }
    }

    @Override
    public BlockVersion add(BlockVersion version) {
        synchronized (active) {
            active.remove(version.block());
            return store.add(version);
        }
    }

    @Override
    public Optional<BlockVersion> get(long id) {
        return store.get(id);
    }

    @Override
    public List<BlockVersion> versions(Block block, int limit) {
        return store.versions(block, limit);
    }

    @Override
    public void redact(long id, String content, int tokens, List<String> keptLines) {
        synchronized (active) {
            active.clear();
            store.redact(id, content, tokens, keptLines);
        }
    }

    @Override
    public void deleteAll() {
        synchronized (active) {
            active.clear();
            store.deleteAll();
        }
    }
}
