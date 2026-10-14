// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the current vital signs, sampled once a second, into one row per wall-clock minute: the medians of the
 * seconds of good quality and how many there were. A minute in which the sensor reported something (good or
 * unstable) gets a row, with {@code n = 0} and no rates when none of its seconds was good; a minute with nothing
 * (nobody, no sensor) gets none. Not thread-safe: the application service serializes the calls.
 */
public final class MinuteAggregator {
    private double minute = Double.NaN;
    private final List<Double> hr = new ArrayList<>();
    private final List<Double> br = new ArrayList<>();
    private boolean seen;
    private double lastSecond = Double.NaN;

    /** The start of the minute holding wall-clock second {@code wall}. */
    public static double minuteOf(double wall) {
        return Math.floor(wall / 60.0) * 60.0;
    }

    /**
     * One sample (call about once a second; a second already sampled is ignored).
     *
     * @param wall    wall-clock seconds now
     * @param current the current vital signs, {@code null} without a sensor
     * @return the minute that just ended, when this sample is the first of a new one and the ended one has a row
     */
    public VitalsMinute sample(double wall, VitalsCurrent current) {
        double second = Math.floor(wall);
        if (second == lastSecond) {
            return null;
        }
        lastSecond = second;
        VitalsMinute ended = null;
        double m = minuteOf(wall);
        if (m != minute) {
            ended = row();
            minute = m;
            hr.clear();
            br.clear();
            seen = false;
        }
        if (current != null && current.quality() != VitalsQuality.NONE) {
            seen = true;
            if (current.quality() == VitalsQuality.GOOD && current.hr() != null && current.br() != null) {
                hr.add(current.hr());
                br.add(current.br());
            }
        }
        return ended;
    }

    /** The minute in progress, {@code null} when it has no row (yet). */
    public VitalsMinute inProgress() {
        return row();
    }

    private VitalsMinute row() {
        if (Double.isNaN(minute) || !seen) {
            return null;
        }
        return new VitalsMinute(minute, Medians.round(Medians.of(hr), 1), Medians.round(Medians.of(br), 1), hr.size());
    }
}
