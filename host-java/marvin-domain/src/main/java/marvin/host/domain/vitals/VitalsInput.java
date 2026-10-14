// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

/**
 * One MR60BHA2 reading as the vitals context takes it.
 *
 * @param source     the device it came from (one seat, one radar: the gate follows one source at a time)
 * @param simulated  the device said its sensor data is simulated
 * @param valid      the sender says a still person is measured
 * @param breathRate breaths per minute (0 when none)
 * @param heartRate  beats per minute (0 when none)
 * @param distanceMm the radar's distance to the person, 0 when unknown
 * @param steady     the sender's own signal-quality flag, {@code null} when it does not report one
 */
public record VitalsInput(String source, boolean simulated, boolean valid, double breathRate, double heartRate,
                          int distanceMm, Boolean steady) {
}
