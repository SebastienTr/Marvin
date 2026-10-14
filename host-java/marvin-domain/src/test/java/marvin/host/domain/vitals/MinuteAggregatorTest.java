// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Per-minute rows from once-a-second samples. */
class MinuteAggregatorTest {
    static final double M0 = 1_789_982_880.0;                  // a minute's start

    static VitalsCurrent good(double hr, double br) {
        return new VitalsCurrent(hr, br, VitalsQuality.GOOD, 0.8, 0);
    }

    @Test
    void mediansOfTheGoodSecondsAndTheirCount() {
        MinuteAggregator a = new MinuteAggregator();
        for (int s = 0; s < 60; s++) {
            VitalsCurrent c = s < 40 ? good(s % 2 == 0 ? 66 : 67, 14)
                    : new VitalsCurrent(70.0, 15.0, VitalsQuality.UNSTABLE, 0.8, 0);
            assertThat(a.sample(M0 + s + 0.3, c)).isNull();
        }
        assertThat(a.inProgress()).isEqualTo(new VitalsMinute(M0, 66.5, 14.0, 40));
        VitalsMinute ended = a.sample(M0 + 60.2, null);
        assertThat(ended).isEqualTo(new VitalsMinute(M0, 66.5, 14.0, 40));
        assertThat(a.inProgress()).isNull();                           // nothing yet in the new minute
    }

    @Test
    void aMinuteWithReadingsButNoGoodSecondHasNoRates() {
        MinuteAggregator a = new MinuteAggregator();
        a.sample(M0 + 1, new VitalsCurrent(70.0, 15.0, VitalsQuality.UNSTABLE, 0.8, 0));
        assertThat(a.sample(M0 + 61, null)).isEqualTo(new VitalsMinute(M0, null, null, 0));
    }

    @Test
    void aMinuteWithNothingHasNoRow() {
        MinuteAggregator a = new MinuteAggregator();
        a.sample(M0 + 1, null);
        a.sample(M0 + 2, new VitalsCurrent(null, null, VitalsQuality.NONE, null, 0));
        assertThat(a.sample(M0 + 61, null)).isNull();
    }

    @Test
    void aSecondIsSampledOnce() {
        MinuteAggregator a = new MinuteAggregator();
        a.sample(M0 + 1.1, good(60, 12));
        a.sample(M0 + 1.9, good(90, 20));
        assertThat(a.inProgress().n()).isEqualTo(1);
        assertThat(a.inProgress().hr()).isEqualTo(60.0);
    }

    @Test
    void minuteOfFloors() {
        assertThat(MinuteAggregator.minuteOf(M0 + 59.99)).isEqualTo(M0);
        assertThat(MinuteAggregator.minuteOf(M0 + 60)).isEqualTo(M0 + 60);
    }
}
