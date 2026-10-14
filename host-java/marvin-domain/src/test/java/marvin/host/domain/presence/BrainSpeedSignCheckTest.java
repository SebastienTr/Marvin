// SPDX-License-Identifier: MIT
package marvin.host.domain.presence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The LD2450 speed sign, found from how the distance changes. */
class BrainSpeedSignCheckTest {

    /** Walks 3 m away and back at 60 cm/s; the LD2450's speed is {@code rawSign} × the speed moving away. */
    private static BrainSpeedSignCheck walk(int rawSign) {
        BrainSpeedSignCheck check = new BrainSpeedSignCheck();
        double y = -500;
        for (int i = 0; i < 100; i++) {
            double v = i < 50 ? 60 : -60;                   // cm/s, positive moving away
            y -= v * 10 * 0.1;                              // mm, the person is at negative y
            check.add(i * 0.1, new TargetSighting(0, y, 160, rawSign * v));
        }
        return check;
    }

    @Test
    void positiveMovingAwayIsPlusOneAndApproachingMinusOne() {
        assertThat(walk(1).sign()).isEqualTo(1);
        assertThat(walk(-1).sign()).isEqualTo(-1);
        assertThat(walk(1).movingAwayShare()).isEqualTo(1.0);
    }

    @Test
    void tooFewOrNoClearMovementIsNoVerdict() {
        BrainSpeedSignCheck check = new BrainSpeedSignCheck();
        for (int i = 0; i < 100; i++) {
            check.add(i * 0.1, new TargetSighting(0, -800, 160, 2));     // sitting still
        }
        assertThat(check.samples()).isZero();
        assertThat(check.sign()).isZero();
    }
}
