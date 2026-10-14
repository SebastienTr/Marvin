// SPDX-License-Identifier: MIT
package marvin.host.domain.presence;

/**
 * Checks {@link BrainConfig#radarSpeedSign()} on real frames: the LD2450's own speed against how fast the nearest
 * person's distance actually changes. The brain wants the state's speed positive moving away; the simulator sends
 * the LD2450 speed positive approaching (sign -1, the default), while ESPHome's ld2450 component reads a real one as
 * positive moving away (sign +1). Feed it a recording's frames ({@code MARVIN_RECORD}, H1) and read
 * {@link #sign()}. The sign only changes how the speed is shown: every brain rule uses its absolute value.
 *
 * <p>Only clear movements count: both frames within {@link #MAX_DT_S}, the distance changing faster than
 * {@link #MIN_RATE_CMS} and the LD2450 reporting more than {@link #MIN_SPEED_CMS}. Not thread-safe.
 */
public final class BrainSpeedSignCheck {
    static final double MAX_DT_S = 0.3;
    static final double MIN_RATE_CMS = 15.0;
    static final double MIN_SPEED_CMS = 5.0;
    /** Fewer counted frames than this: no verdict. */
    public static final int MIN_SAMPLES = 20;

    private Double lastT;
    private double lastDistanceMm;
    private int agree;
    private int disagree;

    /**
     * One frame's nearest target ({@code null}: nobody).
     *
     * @param nearest the nearest person, its speed the LD2450's as decoded (before any sign)
     */
    public void add(double t, TargetSighting nearest) {
        if (nearest == null) {
            lastT = null;
            return;
        }
        double d = Math.hypot(nearest.x(), nearest.y());
        if (lastT != null && t > lastT && t - lastT <= MAX_DT_S) {
            double rate = (d - lastDistanceMm) / 10.0 / (t - lastT);       // cm/s, positive moving away
            if (Math.abs(rate) >= MIN_RATE_CMS && Math.abs(nearest.speedCms()) >= MIN_SPEED_CMS) {
                if (Math.signum(rate) == Math.signum(nearest.speedCms())) {
                    agree++;
                } else {
                    disagree++;
                }
            }
        }
        lastT = t;
        lastDistanceMm = d;
    }

    /** Frames counted so far. */
    public int samples() {
        return agree + disagree;
    }

    /** The share of counted frames whose raw speed is positive moving away (0..1; NaN: none). */
    public double movingAwayShare() {
        return samples() == 0 ? Double.NaN : (double) agree / samples();
    }

    /**
     * The {@code radar_speed_sign} the frames say: +1 when the raw speed is positive moving away, -1 when positive
     * approaching, 0 when there are fewer than {@link #MIN_SAMPLES} or no clear majority (under 75 %).
     */
    public int sign() {
        if (samples() < MIN_SAMPLES) {
            return 0;
        }
        double share = movingAwayShare();
        return share >= 0.75 ? 1 : share <= 0.25 ? -1 : 0;
    }
}
