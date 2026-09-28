// SPDX-License-Identifier: MIT
package marvin.host.domain.presence;

/**
 * One MR60BHA2 reading, as the brain needs it.
 *
 * @param valid      the radar says a still person is measured
 * @param breathRate per minute
 * @param heartRate  per minute
 */
public record VitalsReading(boolean valid, double breathRate, double heartRate) {
}
