// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import marvin.host.application.robot.port.out.HostClock;

/** The machine's clocks. */
public final class SystemHostClock implements HostClock {

    @Override
    public double monotonicSeconds() {
        return System.nanoTime() / 1e9;
    }

    @Override
    public double wallSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }
}
