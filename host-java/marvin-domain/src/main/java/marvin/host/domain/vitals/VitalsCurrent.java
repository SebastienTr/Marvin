// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

/**
 * The current vital signs (contract C3, {@code current}).
 *
 * @param hr        beats per minute: the median over the gate's window, {@code null} when the quality is none
 * @param br        breaths per minute, the same way
 * @param quality   how far to trust them
 * @param distanceM the radar's distance to the person, metres, {@code null} when unknown
 * @param t         wall-clock seconds of the newest reading behind these values
 */
public record VitalsCurrent(Double hr, Double br, VitalsQuality quality, Double distanceM, double t) {
}
