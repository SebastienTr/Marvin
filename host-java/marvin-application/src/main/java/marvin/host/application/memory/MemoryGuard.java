// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.util.function.Supplier;

/**
 * Orders the owner's changes and the worker's writes. A pass decides its writes over seconds of model calls, from
 * facts, events and a profile it read before; the owner may forget, correct or pin meanwhile. Every owner change
 * runs under this guard and moves the {@linkplain #generation() generation} on; the worker writes only under the
 * guard and only if the generation is the one it read before deciding, so the owner's word is never undone and
 * nothing forgotten comes back. A write refused this way is decided again by the next pass, from fresh data.
 *
 * <p>Forgetting everything is also a {@linkplain #resets() reset}: a pass running then stops at once.
 */
public final class MemoryGuard {
    private long generation;
    private long resets;

    /** Moves on with every owner change. */
    public synchronized long generation() {
        return generation;
    }

    /** Moves on when everything is forgotten. */
    public synchronized long resets() {
        return resets;
    }

    /** An owner change: no worker write happens during it, and none decided before it happens after it. */
    public synchronized <T> T owner(Supplier<T> change) {
        try {
            return change.get();
        } finally {
            generation++;
        }
    }

    /** An owner change without a result. */
    public void ownerRun(Runnable change) {
        owner(() -> {
            change.run();
            return null;
        });
    }

    /** Forgetting everything: an owner change that also stops the pass in progress. */
    public synchronized <T> T reset(Supplier<T> change) {
        resets++;
        return owner(change);
    }

    /**
     * A worker write decided at {@code seen}: done, and {@code true}, only when no owner change happened since.
     */
    public synchronized boolean write(long seen, Runnable write) {
        if (seen != generation) {
            return false;
        }
        write.run();
        return true;
    }
}
