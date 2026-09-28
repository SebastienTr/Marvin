// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.out;

/** The host's clocks, for link statistics (sensor time always comes from the robot). */
public interface HostClock {

    /** Monotonic seconds, from an arbitrary origin. */
    double monotonicSeconds();

    /** Wall clock, seconds since the epoch. */
    double wallSeconds();
}
