// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

/**
 * One minute of vital signs (contract C3, {@code minutes}).
 *
 * @param t  wall-clock seconds of the minute's start
 * @param hr the median heart rate of the minute's seconds of good quality, {@code null} when there were none
 * @param br the median breathing rate, the same way
 * @param n  how many seconds of the minute passed the quality gate (0 to 60)
 */
public record VitalsMinute(double t, Double hr, Double br, int n) {
}
