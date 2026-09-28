// SPDX-License-Identifier: MIT
package marvin.host.app;

import marvin.host.domain.shared.Clocks;

/** The system clocks. */
public final class SystemClocks implements Clocks {
    private final long origin = System.nanoTime();

    @Override
    public double wallSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    @Override
    public double monotonicSeconds() {
        return (System.nanoTime() - origin) / 1e9;
    }
}
