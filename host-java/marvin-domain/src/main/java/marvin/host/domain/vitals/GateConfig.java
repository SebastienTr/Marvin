// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

/**
 * The quality gate's thresholds.
 *
 * @param windowS        the medians and the quality look at the readings of this many seconds (10)
 * @param goodSpanS      good needs readings that passed the gate for at least this long...
 * @param goodFraction   ...making at least this fraction of the window's readings...
 * @param keepFraction   ...and stays good while at least this fraction pass...
 * @param keepFreshS     ...and one passed in the last this many seconds; no valid reading for this long: none
 * @param breathMin      plausible breaths per minute, low end (the brain's)
 * @param breathMax      plausible breaths per minute, high end
 * @param heartMin       plausible beats per minute, low end
 * @param heartMax       plausible beats per minute, high end
 * @param maxDistanceM   the MR60BHA2's vital-signs zone (Seeed: the chest within 1.5 m)
 * @param sourceSilentS  a source silent this long is dropped (another one may take the seat); quality none
 * @param noSensorAfterS no reading from any source for this long: no vitals sensor (current is absent)
 */
public record GateConfig(double windowS, double goodSpanS, double goodFraction, double keepFraction, double keepFreshS,
                         double breathMin, double breathMax, double heartMin, double heartMax, double maxDistanceM,
                         double sourceSilentS, double noSensorAfterS) {

    public static final GateConfig DEFAULT = new GateConfig(10.0, 8.0, 0.8, 0.6, 2.0, 6.0, 30.0, 40.0, 140.0, 1.5,
            3.0, 30.0);

    public GateConfig {
        if (!(windowS > 0) || goodSpanS > windowS || goodFraction < keepFraction || keepFraction <= 0 || goodFraction > 1) {
            throw new IllegalArgumentException("inconsistent vitals gate");
        }
    }
}
