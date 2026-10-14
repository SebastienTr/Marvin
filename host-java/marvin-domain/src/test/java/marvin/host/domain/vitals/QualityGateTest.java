// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The quality gate: what passes, when the quality is good, unstable or none, and which source holds the seat. */
class QualityGateTest {
    static final double WALL0 = 1_789_983_000.0;

    final QualityGate gate = new QualityGate();

    static VitalsInput bridge(boolean valid, double br, double hr, int mm, Boolean steady) {
        return new VitalsInput("bridge", false, valid, br, hr, mm, steady);
    }

    static VitalsInput sim(boolean valid, double br, double hr) {
        return new VitalsInput("robot", true, valid, br, hr, 820, null);
    }

    /** Readings at 10 Hz from {@code from} to {@code to} (seconds), each made by {@code at}. */
    void feed(double from, double to, java.util.function.DoubleFunction<VitalsInput> at, Stillness still) {
        for (double t = from; t < to - 1e-9; t += 0.1) {
            gate.onReading(t, WALL0 + t, at.apply(t), still);
        }
    }

    @Test
    void noReadingNoSensor() {
        assertThat(gate.current(0)).isNull();
    }

    @Test
    void goodAfterEightSecondsOfPassingReadings() {
        feed(0, 5, t -> bridge(true, 14, 66 + (Math.round(t * 10) % 2 == 0 ? 1 : -1), 820, true), Stillness.STILL);
        VitalsCurrent early = gate.current(5);
        assertThat(early.quality()).isEqualTo(VitalsQuality.UNSTABLE);
        assertThat(early.hr()).isEqualTo(66.0);                    // provisional medians are shown, flagged unstable
        feed(5, 9, t -> bridge(true, 14, 66, 820, true), Stillness.STILL);
        VitalsCurrent c = gate.current(9);
        assertThat(c.quality()).isEqualTo(VitalsQuality.GOOD);
        assertThat(c.hr()).isEqualTo(66.0);
        assertThat(c.br()).isEqualTo(14.0);
        assertThat(c.distanceM()).isEqualTo(0.82);
        assertThat(c.t()).isEqualTo(WALL0 + 8.9, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void theMedianIgnoresAFewOutliers() {
        feed(0, 10, t -> bridge(true, 14, (Math.round(t * 10) % 10 == 0) ? 130 : 64, 820, true), Stillness.STILL);
        assertThat(gate.current(10).hr()).isEqualTo(64.0);
    }

    @Test
    void movementBreaksIt() {
        feed(0, 10, t -> bridge(true, 14, 66, 820, true), Stillness.STILL);
        assertThat(gate.current(10).quality()).isEqualTo(VitalsQuality.GOOD);
        feed(10, 15, t -> bridge(true, 14, 66, 820, true), Stillness.MOVING);
        assertThat(gate.current(15).quality()).isEqualTo(VitalsQuality.UNSTABLE);
    }

    @Test
    void goodHoldsThroughABriefDropout() {
        feed(0, 10, t -> bridge(true, 14, 66, 820, true), Stillness.STILL);
        feed(10, 11, t -> bridge(false, 0, 0, 820, true), Stillness.STILL);   // one second of nothing
        assertThat(gate.current(11).quality()).isEqualTo(VitalsQuality.GOOD);
        feed(11, 13, t -> bridge(true, 14, 66, 820, true), Stillness.STILL);
        assertThat(gate.current(13).quality()).isEqualTo(VitalsQuality.GOOD);
        feed(13, 16, t -> bridge(false, 0, 0, 820, true), Stillness.STILL);   // three seconds: gone
        assertThat(gate.current(16).quality()).isEqualTo(VitalsQuality.NONE);
        assertThat(gate.current(16).hr()).isNull();
    }

    @Test
    void theSendersQualityFlagCounts() {
        feed(0, 10, t -> bridge(true, 14, 66, 820, false), Stillness.UNKNOWN);
        VitalsCurrent c = gate.current(10);
        assertThat(c.quality()).isEqualTo(VitalsQuality.UNSTABLE);
        assertThat(c.hr()).isEqualTo(66.0);
    }

    @Test
    void outOfRangeOrImplausibleDoesNotPass() {
        assertThat(gate.passes(bridge(true, 14, 66, 1600, true), Stillness.STILL)).isFalse();     // beyond 1.5 m
        assertThat(gate.passes(bridge(true, 14, 66, 0, true), Stillness.STILL)).isTrue();         // distance unknown
        assertThat(gate.passes(bridge(true, 14, 170, 800, true), Stillness.STILL)).isFalse();
        assertThat(gate.passes(bridge(true, 3, 66, 800, true), Stillness.STILL)).isFalse();
        assertThat(gate.passes(bridge(false, 14, 66, 800, true), Stillness.STILL)).isFalse();
        assertThat(gate.passes(bridge(true, 14, 66, 800, null), Stillness.UNKNOWN)).isTrue();
    }

    @Test
    void noValidReadingIsNoneWithoutRates() {
        feed(0, 5, t -> bridge(false, 0, 0, 1900, false), Stillness.UNKNOWN);
        VitalsCurrent c = gate.current(5);
        assertThat(c.quality()).isEqualTo(VitalsQuality.NONE);
        assertThat(c.hr()).isNull();
        assertThat(c.br()).isNull();
        assertThat(c.distanceM()).isEqualTo(1.9);
    }

    @Test
    void aSilentSensorIsNoneThenGone() {
        feed(0, 10, t -> bridge(true, 14, 66, 820, true), Stillness.STILL);
        assertThat(gate.current(14).quality()).isEqualTo(VitalsQuality.NONE);
        assertThat(gate.current(14).hr()).isNull();
        assertThat(gate.current(41)).isNull();
    }

    @Test
    void oneSeatARealRadarTakesItFromASimulatedOne() {
        feed(0, 10, t -> sim(true, 20, 90), Stillness.STILL);
        assertThat(gate.source()).isEqualTo("robot");
        assertThat(gate.current(10).hr()).isEqualTo(90.0);
        // the bridge starts talking: it takes the seat at once, and the window starts over
        for (double t = 10; t < 20; t += 0.1) {
            gate.onReading(t, WALL0 + t, sim(true, 20, 90), Stillness.STILL);
            gate.onReading(t + 0.05, WALL0 + t, bridge(true, 14, 66, 820, true), Stillness.STILL);
        }
        assertThat(gate.source()).isEqualTo("bridge");
        VitalsCurrent c = gate.current(20);
        assertThat(c.hr()).isEqualTo(66.0);
        assertThat(c.quality()).isEqualTo(VitalsQuality.GOOD);
    }

    @Test
    void twoSourcesOfTheSameKindTheFirstKeepsTheSeatUntilSilent() {
        for (double t = 0; t < 10; t += 0.1) {
            gate.onReading(t, WALL0 + t, sim(true, 14, 70), Stillness.STILL);
            gate.onReading(t, WALL0 + t, new VitalsInput("robot2", true, true, 14, 99, 820, null), Stillness.STILL);
        }
        assertThat(gate.current(10).hr()).isEqualTo(70.0);
        feed(14, 15, t -> new VitalsInput("robot2", true, true, 14, 99, 820, null), Stillness.STILL);
        assertThat(gate.source()).isEqualTo("robot2");
        assertThat(gate.current(15).hr()).isEqualTo(99.0);
    }
}
