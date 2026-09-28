// SPDX-License-Identifier: MIT
package marvin.host.domain.presence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;

/** The brain's rules on hand-made frames (the golden recordings cover whole scenes). */
class BrainTest {
    private final Brain brain = new Brain(BrainConfig.DEFAULT.withStillLongS(60));
    private final List<PresenceEvent> events = new ArrayList<>();

    private void seen(double t, double x, double y) {
        events.addAll(brain.onTargets((long) (t * 1e6), false, List.of(new TargetSighting(x, y, 160, 0))));
    }

    private void nobody(double t) {
        events.addAll(brain.onTargets((long) (t * 1e6), false, List.of()));
    }

    private List<EventKind> kinds() {
        return events.stream().map(PresenceEvent::kind).toList();
    }

    @Test
    void arrivesAfterHalfASecondAndLeavesAfterThreeSeconds() {
        seen(0.0, 0, -2000);
        seen(0.4, 0, -2000);
        assertThat(kinds()).isEmpty();
        seen(0.5, 0, -2000);
        assertThat(kinds()).containsExactly(EventKind.ARRIVED);
        assertThat(events.get(0).detail()).isEqualTo("2.00 m away");
        assertThat(events.get(0).data()).containsEntry("distance_m", 2.0);
        assertThat(brain.state().head()).containsExactly(0.0, -2000.0, 1000.0);
        for (double t = 0.6; t < 3.65; t += 0.1) {
            nobody(t);
        }
        assertThat(kinds()).containsExactly(EventKind.ARRIVED, EventKind.LEFT);
        assertThat(events.get(1).detail()).isEqualTo("not seen for 3 s");
        assertThat(brain.state().present()).isFalse();
        assertThat(brain.state().head()).isNull();
    }

    @Test
    void sitsDownWhenStillAndCloseThenStandsUp() {
        double t = 0;
        for (; t < 5; t += 0.1) {
            seen(t, 100, -800);
        }
        assertThat(kinds()).containsExactly(EventKind.ARRIVED, EventKind.SAT_DOWN);
        assertThat(brain.state().seated()).isTrue();
        assertThat(brain.state().head()[2]).isEqualTo(550.0);
        for (; t < 70; t += 0.1) {
            seen(t, 100, -800);
        }
        assertThat(kinds()).containsExactly(EventKind.ARRIVED, EventKind.SAT_DOWN, EventKind.STILL_LONG);
        for (int i = 0; i < 5; i++, t += 0.1) {
            seen(t, 600, -800);         // moved half a metre (the position is smoothed)
        }
        assertThat(kinds()).endsWith(EventKind.STOOD_UP);
        assertThat(events.getLast().detail()).matches("after 6\\d s seated");
    }

    @Test
    void approachesWithHysteresis() {
        double t = 0;
        for (; t < 1; t += 0.1) {
            seen(t, 0, -2000);
        }
        for (int i = 0; i < 20; i++, t += 0.1) {
            seen(t, 0, -500);
        }
        long approached = kinds().stream().filter(EventKind.APPROACHED::equals).count();
        assertThat(approached).isEqualTo(1);
    }

    @Test
    void vitalSignsNeedAStillPresentPersonForThreeSeconds() {
        double t = 0;
        for (; t < 1; t += 0.1) {
            seen(t, 0, -900);
        }
        for (; t < 5; t += 0.1) {
            seen(t, 0, -900);
            events.addAll(brain.onVitals((long) (t * 1e6) + 1, false, new VitalsReading(true, 15.5, 64.5)));
        }
        assertThat(kinds()).contains(EventKind.VITALS_ACQUIRED);
        PresenceEvent acquired = events.stream().filter(e -> e.kind() == EventKind.VITALS_ACQUIRED).findFirst()
                .orElseThrow();
        assertThat(acquired.detail()).isEqualTo("breath 16/min, heart 64/min");   // Python rounds halves to even
        assertThat(brain.state().heartRate()).isEqualTo(64.5);
        for (double end = t + 1.5; t < end; t += 0.1) {
            events.addAll(brain.onVitals((long) (t * 1e6), false, new VitalsReading(false, 0, 0)));
        }
        assertThat(events.getLast().kind()).isEqualTo(EventKind.VITALS_LOST);
        assertThat(events.getLast().detail()).isEqualTo("no valid reading");
        assertThat(brain.state().heartRate()).isNull();
    }

    @Test
    void dropsFramesSlightlyOutOfOrderAndRestartsWhenTheRobotReboots() {
        for (double t = 10; t < 11; t += 0.1) {
            seen(t, 0, -1500);
        }
        assertThat(kinds()).containsExactly(EventKind.ARRIVED);
        long before = brain.state().tUs();
        seen(10.5, 0, -1500);                   // 0.5 s back: dropped
        assertThat(brain.state().tUs()).isEqualTo(before);
        seen(0.1, 0, -1500);                    // 10 s back: a reboot
        assertThat(kinds()).containsExactly(EventKind.ARRIVED, EventKind.LEFT);
        assertThat(events.getLast().detail()).isEqualTo("sensor restarted");
        assertThat(brain.state().present()).isFalse();
        assertThat(brain.state().tUs()).isEqualTo(100_000);
    }

    @Test
    void formatsNumbersLikePython() {
        assertThat(Brain.fmt(0.125, 2)).isEqualTo("0.12");
        assertThat(Brain.fmt(0.375, 2)).isEqualTo("0.38");
        assertThat(Brain.fmt(16.5, 0)).isEqualTo("16");
        assertThat(Brain.fmt(17.5, 0)).isEqualTo("18");
        assertThat(Brain.fmt(2.675, 2)).isEqualTo("2.67");      // 2.67499999... in binary
        assertThat(Brain.fmt(3.0, 0)).isEqualTo("3");
        assertThat(Brain.duration(28.145)).isEqualTo("28 s");
        assertThat(Brain.duration(3000)).isEqualTo("50 min");
        assertThat(Brain.duration(3 * 3600 + 900)).isEqualTo("3.2 h");
    }
}
