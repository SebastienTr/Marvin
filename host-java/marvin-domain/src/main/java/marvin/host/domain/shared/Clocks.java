// SPDX-License-Identifier: MIT
package marvin.host.domain.shared;

/**
 * The host's clocks, given to whoever needs the time (the domain never reads the system clock; sensor
 * time always comes from the robot's frames).
 */
public interface Clocks {

    /** Wall clock, seconds since the epoch. */
    double wallSeconds();

    /** Monotonic seconds, from an arbitrary origin. */
    double monotonicSeconds();
}
