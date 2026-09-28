// SPDX-License-Identifier: MIT
package marvin.host.domain.presence;

/**
 * The brain's thresholds (brain.py {@code BrainConfig}). Distances are horizontal, from the robot's
 * axis; times in seconds.
 *
 * @param arriveConfirmS   someone must be seen this long before {@code arrived}
 * @param leaveAfterS      nobody seen this long: {@code left}
 * @param approachM        {@code approached} fires below this...
 * @param approachRearmM   ...and is re-armed beyond this
 * @param positionTauS     moving average of the position
 * @param speedTauS        moving average of the radar speed
 * @param radarSpeedSign   the LD2450 speed is positive approaching; the state's is positive moving away
 * @param stillWindowS     stillness: the position spread over this window...
 * @param stillMoveMm      ...stays below this...
 * @param stillSpeedCms    ...and the speed below this
 * @param sitMaxM          {@code sat_down} only this close...
 * @param sitStillS        ...after being still this long
 * @param standMoveMm      {@code stood_up}: moved this far from the seat...
 * @param standAwayM       ...or this much further from the robot...
 * @param standSpeedCms    ...or faster than this...
 * @param standSpeedS      ...for this long
 * @param stillLongS       {@code still_long} after sitting this long (once per sitting)
 * @param vitalsAcquireS   valid, plausible readings on a still person for this long
 * @param vitalsLoseS      bad readings this long: {@code vitals_lost}
 * @param breathMin        plausible breaths per minute, low end
 * @param breathMax        plausible breaths per minute, high end
 * @param heartMin         plausible beats per minute, low end
 * @param heartMax         plausible beats per minute, high end
 * @param headZSeatedMm    head height estimate, seated (Z = 0 on the table top)
 * @param headZStandingMm  head height estimate, standing
 * @param clockResetS      the device clock going back more than this is a restart
 * @param maxEvents        events kept in memory
 */
public record BrainConfig(double arriveConfirmS, double leaveAfterS, double approachM, double approachRearmM,
                          double positionTauS, double speedTauS, int radarSpeedSign, double stillWindowS,
                          double stillMoveMm, double stillSpeedCms, double sitMaxM, double sitStillS,
                          double standMoveMm, double standAwayM, double standSpeedCms, double standSpeedS,
                          double stillLongS, double vitalsAcquireS, double vitalsLoseS, double breathMin,
                          double breathMax, double heartMin, double heartMax, double headZSeatedMm,
                          double headZStandingMm, double clockResetS, int maxEvents) {

    /** The Python host's defaults. */
    public static final BrainConfig DEFAULT = new BrainConfig(0.5, 3.0, 0.6, 0.9, 0.2, 0.5, -1, 1.0, 120.0, 10.0,
            1.3, 3.0, 300.0, 0.3, 30.0, 0.5, 50 * 60, 3.0, 1.0, 6.0, 30.0, 40.0, 140.0, 550.0, 1000.0, 1.0, 200);

    public BrainConfig withRadarSpeedSign(int sign) {
        return new BrainConfig(arriveConfirmS, leaveAfterS, approachM, approachRearmM, positionTauS, speedTauS, sign,
                stillWindowS, stillMoveMm, stillSpeedCms, sitMaxM, sitStillS, standMoveMm, standAwayM, standSpeedCms,
                standSpeedS, stillLongS, vitalsAcquireS, vitalsLoseS, breathMin, breathMax, heartMin, heartMax,
                headZSeatedMm, headZStandingMm, clockResetS, maxEvents);
    }

    public BrainConfig withStillLongS(double s) {
        return new BrainConfig(arriveConfirmS, leaveAfterS, approachM, approachRearmM, positionTauS, speedTauS,
                radarSpeedSign, stillWindowS, stillMoveMm, stillSpeedCms, sitMaxM, sitStillS, standMoveMm, standAwayM,
                standSpeedCms, standSpeedS, s, vitalsAcquireS, vitalsLoseS, breathMin, breathMax, heartMin, heartMax,
                headZSeatedMm, headZStandingMm, clockResetS, maxEvents);
    }
}
