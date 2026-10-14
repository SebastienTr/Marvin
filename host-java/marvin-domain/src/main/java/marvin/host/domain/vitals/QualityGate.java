// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The vitals quality gate, for one seat (one MR60BHA2).
 *
 * <p><b>One seat.</b> Readings may come from several devices (the robot's simulated radar, the MR60BHA2 bridge), but
 * one person sits in front of one radar: the gate follows one source. A real source takes the seat from a simulated
 * one at once; otherwise the source keeps it until it is silent for {@link GateConfig#sourceSilentS()}, then the next
 * source to speak takes it. Readings from the other sources are ignored; changing source starts the window over.
 *
 * <p><b>The gate.</b> A reading passes when the sender calls it valid, the presence radar does not see the person
 * move, the sender's own signal-quality flag (when it has one) is steady, the distance (when known) is within the
 * radar's vital-signs zone, and both rates are plausible for a person at rest.
 *
 * <p><b>Quality</b> over the last {@link GateConfig#windowS()} seconds: <i>good</i> once readings that passed cover
 * {@link GateConfig#goodSpanS()} and make {@link GateConfig#goodFraction()} of the window's readings with the newest one
 * passing; it stays good while {@link GateConfig#keepFraction()} pass and one passed in the last
 * {@link GateConfig#keepFreshS()}. <i>Unstable</i> while there are valid readings that are not good (yet).
 * <i>None</i> without a valid reading in the last {@link GateConfig#keepFreshS()} (the person left, the radar lost
 * them): the rates are not carried on from older readings. Rates are the medians of the readings that passed (when
 * unstable and none passed: of the valid, plausible ones), rounded to 0.1/min; the distance to the centimetre.
 *
 * <p>Times are the host's: monotonic seconds for the windows, wall-clock seconds for {@link VitalsCurrent#t()}. Not
 * thread-safe: the application service serializes the calls.
 */
public final class QualityGate {

    private record Reading(double t, double wall, boolean valid, boolean passed, double br, double hr, int distanceMm) {
    }

    private final GateConfig c;
    private final Deque<Reading> window = new ArrayDeque<>();
    private String source;
    private boolean sourceSimulated;
    private double sourceT = Double.NEGATIVE_INFINITY;
    private double lastAnyT = Double.NEGATIVE_INFINITY;
    private double lastWall;
    private boolean good;

    public QualityGate(GateConfig config) {
        this.c = Objects.requireNonNull(config, "config");
    }

    public QualityGate() {
        this(GateConfig.DEFAULT);
    }

    /** The source the gate follows now, {@code null} before the first reading. */
    public String source() {
        return source;
    }

    /**
     * One reading.
     *
     * @param t      host monotonic seconds
     * @param wall   host wall-clock seconds
     * @param still  what the presence radar says about the person now
     * @return whether the reading was taken (it came from the seat's source)
     */
    public boolean onReading(double t, double wall, VitalsInput in, Stillness still) {
        lastAnyT = t;
        boolean silent = t - sourceT > c.sourceSilentS();
        boolean realOverSim = sourceSimulated && !in.simulated();
        if (!in.source().equals(source)) {
            if (source != null && !silent && !realOverSim) {
                return false;
            }
            source = in.source();
            window.clear();
            good = false;
        }
        sourceSimulated = in.simulated();
        sourceT = t;
        lastWall = wall;
        window.addLast(new Reading(t, wall, in.valid(), passes(in, still), in.breathRate(), in.heartRate(),
                in.distanceMm()));
        trim(t);
        good = evaluateGood(t);
        return true;
    }

    /** Whether one reading passes the gate. */
    boolean passes(VitalsInput in, Stillness still) {
        return in.valid() && still != Stillness.MOVING && !Boolean.FALSE.equals(in.steady())
                && (in.distanceMm() <= 0 || in.distanceMm() <= c.maxDistanceM() * 1000)
                && plausible(in.breathRate(), in.heartRate());
    }

    private boolean plausible(double br, double hr) {
        return c.breathMin() <= br && br <= c.breathMax() && c.heartMin() <= hr && hr <= c.heartMax();
    }

    /**
     * The current vital signs at host monotonic time {@code t}, {@code null} when there is no vitals sensor (no
     * reading from any source for {@link GateConfig#noSensorAfterS()}).
     */
    public VitalsCurrent current(double t) {
        if (source == null || t - lastAnyT > c.noSensorAfterS()) {
            return null;
        }
        trim(t);
        if (t - sourceT > c.sourceSilentS() || window.isEmpty()) {
            return new VitalsCurrent(null, null, VitalsQuality.NONE, null, lastWall);
        }
        good = good && keepGood(t);
        List<Reading> passed = new ArrayList<>();
        List<Reading> valid = new ArrayList<>();
        for (Reading r : window) {
            if (r.passed()) {
                passed.add(r);
            }
            if (r.valid() && plausible(r.br(), r.hr())) {
                valid.add(r);
            }
        }
        Reading newest = window.getLast();
        double lastValid = valid.isEmpty() ? Double.NEGATIVE_INFINITY : valid.getLast().t();
        if (t - lastValid > c.keepFreshS()) {
            good = false;
            return new VitalsCurrent(null, null, VitalsQuality.NONE, distance(List.of(), newest), newest.wall());
        }
        if (passed.isEmpty() && valid.isEmpty()) {
            return new VitalsCurrent(null, null, VitalsQuality.NONE, distance(List.of(), newest), newest.wall());
        }
        List<Reading> used = passed.isEmpty() ? valid : passed;
        Double hr = Medians.round(Medians.of(used.stream().map(Reading::hr).toList()), 1);
        Double br = Medians.round(Medians.of(used.stream().map(Reading::br).toList()), 1);
        return new VitalsCurrent(hr, br, good ? VitalsQuality.GOOD : VitalsQuality.UNSTABLE, distance(used, newest),
                used.getLast().wall());
    }

    /** The median known distance of {@code used}, else the newest reading's, metres; {@code null} when unknown. */
    private static Double distance(List<Reading> used, Reading newest) {
        List<Double> known = used.stream().filter(r -> r.distanceMm() > 0).map(r -> r.distanceMm() / 1000.0).toList();
        if (!known.isEmpty()) {
            return Medians.round(Medians.of(known), 2);
        }
        return newest.distanceMm() > 0 ? Medians.round(newest.distanceMm() / 1000.0, 2) : null;
    }

    private void trim(double t) {
        while (!window.isEmpty() && t - window.getFirst().t() > c.windowS()) {
            window.removeFirst();
        }
    }

    private boolean evaluateGood(double t) {
        if (good) {
            return keepGood(t);
        }
        Reading newest = window.getLast();
        if (!newest.passed()) {
            return false;
        }
        Reading oldestPassed = null;
        int passed = 0;
        for (Reading r : window) {
            if (r.passed()) {
                passed++;
                if (oldestPassed == null) {
                    oldestPassed = r;
                }
            }
        }
        return t - oldestPassed.t() >= c.goodSpanS() && passed >= c.goodFraction() * window.size();
    }

    private boolean keepGood(double t) {
        int passed = 0;
        double lastPassed = Double.NEGATIVE_INFINITY;
        for (Reading r : window) {
            if (r.passed()) {
                passed++;
                lastPassed = r.t();
            }
        }
        return !window.isEmpty() && passed >= c.keepFraction() * window.size() && t - lastPassed <= c.keepFreshS();
    }
}
