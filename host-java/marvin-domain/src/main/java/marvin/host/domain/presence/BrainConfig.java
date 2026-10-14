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
 * @param motionHoldS      a PIR motion sensor as the presence (no live radar): no motion this long, {@code left}
 * @param clockResetS      the device clock going back more than this is a restart
 * @param maxEvents        events kept in memory
 * @param standConfirmS    {@code stood_up} only once its condition held this long (0: at once, as brain.py)
 * @param seatTauS         while seated, the position compared with the seat is averaged over this (0: the
 *                         position itself, as brain.py)
 * @param resitAfterS      no {@code sat_down} this soon after a {@code stood_up} (0: no wait, as brain.py)
 * @param stillGraceS      a break in stillness shorter than this does not restart {@code still_s} (0: any break
 *                         does, as brain.py)
 *
 * <p>The last four are the posture's hysteresis, added in Java because a real LD2450 a short way from a seated
 * person (0.3 to 0.7 m) jitters by tens of centimetres and in speed: with brain.py's rules it flapped between
 * {@code sat_down} and {@code stood_up} every 2 to 10 s. {@link #PYTHON} has them off (the golden recordings replay
 * brain.py); {@link #DEFAULT} has them on. docs/voice.md "Presence tuning" says what to measure to tune them.
 */
public record BrainConfig(double arriveConfirmS, double leaveAfterS, double approachM, double approachRearmM,
                          double positionTauS, double speedTauS, int radarSpeedSign, double stillWindowS,
                          double stillMoveMm, double stillSpeedCms, double sitMaxM, double sitStillS,
                          double standMoveMm, double standAwayM, double standSpeedCms, double standSpeedS,
                          double stillLongS, double vitalsAcquireS, double vitalsLoseS, double breathMin,
                          double breathMax, double heartMin, double heartMax, double headZSeatedMm,
                          double headZStandingMm, double motionHoldS, double clockResetS, int maxEvents,
                          double standConfirmS, double seatTauS, double resitAfterS, double stillGraceS) {

    /** The Python host's defaults: brain.py exactly, no posture hysteresis (what the golden recordings replay). */
    public static final BrainConfig PYTHON = new BrainConfig(0.5, 3.0, 0.6, 0.9, 0.2, 0.5, -1, 1.0, 120.0, 10.0,
            1.3, 3.0, 300.0, 0.3, 30.0, 0.5, 50 * 60, 3.0, 1.0, 6.0, 30.0, 40.0, 140.0, 550.0, 1000.0, 120.0, 1.0, 200,
            0.0, 0.0, 0.0, 0.0);

    /**
     * The host's defaults: brain.py's, with the posture's hysteresis (a stand-up confirmed for 1.5 s, the seat
     * compared with a 1 s average, no new sit-down within 3 s, stillness surviving breaks under 2 s). Estimates until
     * H1 measures a real LD2450.
     */
    public static final BrainConfig DEFAULT = PYTHON.withPosture(2.0, 1.5, 3.0, 3.0).with(java.util.Map.of("sit_still_s", 2.5));

    /** A brain.py config ({@link #PYTHON}'s field order), without the posture's hysteresis. */
    public BrainConfig(double arriveConfirmS, double leaveAfterS, double approachM, double approachRearmM,
                       double positionTauS, double speedTauS, int radarSpeedSign, double stillWindowS,
                       double stillMoveMm, double stillSpeedCms, double sitMaxM, double sitStillS,
                       double standMoveMm, double standAwayM, double standSpeedCms, double standSpeedS,
                       double stillLongS, double vitalsAcquireS, double vitalsLoseS, double breathMin,
                       double breathMax, double heartMin, double heartMax, double headZSeatedMm,
                       double headZStandingMm, double motionHoldS, double clockResetS, int maxEvents) {
        this(arriveConfirmS, leaveAfterS, approachM, approachRearmM, positionTauS, speedTauS, radarSpeedSign,
                stillWindowS, stillMoveMm, stillSpeedCms, sitMaxM, sitStillS, standMoveMm, standAwayM, standSpeedCms,
                standSpeedS, stillLongS, vitalsAcquireS, vitalsLoseS, breathMin, breathMax, heartMin, heartMax,
                headZSeatedMm, headZStandingMm, motionHoldS, clockResetS, maxEvents, 0.0, 0.0, 0.0, 0.0);
    }

    /** The posture's hysteresis (see the class comment). */
    public BrainConfig withPosture(double confirmS, double tauS, double resitS, double graceS) {
        return new BrainConfig(arriveConfirmS, leaveAfterS, approachM, approachRearmM, positionTauS, speedTauS,
                radarSpeedSign, stillWindowS, stillMoveMm, stillSpeedCms, sitMaxM, sitStillS, standMoveMm, standAwayM,
                standSpeedCms, standSpeedS, stillLongS, vitalsAcquireS, vitalsLoseS, breathMin, breathMax, heartMin,
                heartMax, headZSeatedMm, headZStandingMm, motionHoldS, clockResetS, maxEvents, confirmS, tauS, resitS, graceS);
    }

    /**
     * This config with {@code overrides} applied, keyed as brain.py names the fields ({@code sit_max_m},
     * {@code stand_confirm_s}...; numbers only): how {@code calibration.json}'s {@code brain} block tunes it.
     *
     * @throws IllegalArgumentException for an unknown key or a value that is not a finite number
     */
    public BrainConfig with(java.util.Map<String, ?> overrides) {
        java.util.Map<String, Double> v = new java.util.LinkedHashMap<>(values());
        for (var e : overrides.entrySet()) {
            if (!v.containsKey(e.getKey())) {
                throw new IllegalArgumentException("unknown brain setting " + e.getKey());
            }
            if (!(e.getValue() instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                throw new IllegalArgumentException("brain setting " + e.getKey() + " is not a number");
            }
            v.put(e.getKey(), n.doubleValue());
        }
        return new BrainConfig(v.get("arrive_confirm_s"), v.get("leave_after_s"), v.get("approach_m"),
                v.get("approach_rearm_m"), v.get("position_tau_s"), v.get("speed_tau_s"),
                v.get("radar_speed_sign") < 0 ? -1 : 1,
                v.get("still_window_s"), v.get("still_move_mm"), v.get("still_speed_cms"), v.get("sit_max_m"),
                v.get("sit_still_s"), v.get("stand_move_mm"), v.get("stand_away_m"), v.get("stand_speed_cms"),
                v.get("stand_speed_s"), v.get("still_long_s"), v.get("vitals_acquire_s"), v.get("vitals_lose_s"),
                v.get("breath_min"), v.get("breath_max"), v.get("heart_min"), v.get("heart_max"),
                v.get("head_z_seated_mm"), v.get("head_z_standing_mm"), v.get("motion_hold_s"), v.get("clock_reset_s"),
                v.get("max_events").intValue(), v.get("stand_confirm_s"), v.get("seat_tau_s"), v.get("resit_after_s"),
                v.get("still_grace_s"));
    }

    /** The fields by brain.py's names (and the hysteresis's), for {@link #with} and the logs. */
    public java.util.Map<String, Double> values() {
        java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
        m.put("arrive_confirm_s", arriveConfirmS);
        m.put("leave_after_s", leaveAfterS);
        m.put("approach_m", approachM);
        m.put("approach_rearm_m", approachRearmM);
        m.put("position_tau_s", positionTauS);
        m.put("speed_tau_s", speedTauS);
        m.put("radar_speed_sign", (double) radarSpeedSign);
        m.put("still_window_s", stillWindowS);
        m.put("still_move_mm", stillMoveMm);
        m.put("still_speed_cms", stillSpeedCms);
        m.put("sit_max_m", sitMaxM);
        m.put("sit_still_s", sitStillS);
        m.put("stand_move_mm", standMoveMm);
        m.put("stand_away_m", standAwayM);
        m.put("stand_speed_cms", standSpeedCms);
        m.put("stand_speed_s", standSpeedS);
        m.put("still_long_s", stillLongS);
        m.put("vitals_acquire_s", vitalsAcquireS);
        m.put("vitals_lose_s", vitalsLoseS);
        m.put("breath_min", breathMin);
        m.put("breath_max", breathMax);
        m.put("heart_min", heartMin);
        m.put("heart_max", heartMax);
        m.put("head_z_seated_mm", headZSeatedMm);
        m.put("head_z_standing_mm", headZStandingMm);
        m.put("motion_hold_s", motionHoldS);
        m.put("clock_reset_s", clockResetS);
        m.put("max_events", (double) maxEvents);
        m.put("stand_confirm_s", standConfirmS);
        m.put("seat_tau_s", seatTauS);
        m.put("resit_after_s", resitAfterS);
        m.put("still_grace_s", stillGraceS);
        return m;
    }

    public BrainConfig withRadarSpeedSign(int sign) {
        return new BrainConfig(arriveConfirmS, leaveAfterS, approachM, approachRearmM, positionTauS, speedTauS, sign,
                stillWindowS, stillMoveMm, stillSpeedCms, sitMaxM, sitStillS, standMoveMm, standAwayM, standSpeedCms,
                standSpeedS, stillLongS, vitalsAcquireS, vitalsLoseS, breathMin, breathMax, heartMin, heartMax,
                headZSeatedMm, headZStandingMm, motionHoldS, clockResetS, maxEvents, standConfirmS, seatTauS,
                resitAfterS, stillGraceS);
    }

    public BrainConfig withStillLongS(double s) {
        return new BrainConfig(arriveConfirmS, leaveAfterS, approachM, approachRearmM, positionTauS, speedTauS,
                radarSpeedSign, stillWindowS, stillMoveMm, stillSpeedCms, sitMaxM, sitStillS, standMoveMm, standAwayM,
                standSpeedCms, standSpeedS, s, vitalsAcquireS, vitalsLoseS, breathMin, breathMax, heartMin, heartMax,
                headZSeatedMm, headZStandingMm, motionHoldS, clockResetS, maxEvents, standConfirmS, seatTauS,
                resitAfterS, stillGraceS);
    }
}
