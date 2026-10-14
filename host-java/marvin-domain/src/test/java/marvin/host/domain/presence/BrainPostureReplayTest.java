// SPDX-License-Identifier: MIT
package marvin.host.domain.presence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;

/**
 * The posture's hysteresis on a seated session as a real LD2450 reports it a short way from the person: what was seen
 * on Sébastien's desk (October 2026, LD2450 at 0.3 to 0.7 m: {@code sat_down} / {@code stood_up} every 2 to 10 s with
 * brain.py's rules) rebuilt as a seeded session, 10 Hz: a walk in, two minutes seated at 0.5 m with position jitter
 * (σ 50 mm), the point jumping 25 to 45 cm to another part of the body now and then for up to a second with a speed
 * spike, then a real stand-up and walk out. With {@link BrainConfig#PYTHON} it flaps; with {@link BrainConfig#DEFAULT}
 * it sits once (within 5 s for the median session, 7 s at worst), and stands once, when the person really does.
 *
 * <p>When H1 records a real session ({@code MARVIN_RECORD=...mvrec.gz ./marvin up}), replay it the same way (docs/voice.md "Presence
 * tuning") and add it here with its expected events.
 */
class BrainPostureReplayTest {

    /** One LD2450 frame: when, and the nearest person (null: nobody). */
    record Frame(double t, TargetSighting seen) {
    }

    static final double SIT_AT = 8.0;           // the person reaches the chair and sits
    static final double STAND_AT = SIT_AT + 120.0;

    /** The session, seeded: the same frames every run. */
    static List<Frame> seatedSession(long seed) {
        Random r = new Random(seed);
        List<Frame> frames = new ArrayList<>();
        double t = 0;
        for (; t < 3.0; t += 0.1) {
            frames.add(new Frame(t, null));                                  // nobody
        }
        for (; t < SIT_AT; t += 0.1) {                                       // walks in from 3 m to the chair
            double k = (t - 3.0) / (SIT_AT - 3.0);
            double y = -3000 + k * 2500;
            frames.add(new Frame(t, new TargetSighting(50 + r.nextGaussian() * 30, y + r.nextGaussian() * 30, 160,
                    -50 + r.nextGaussian() * 8)));
        }
        double jumpUntil = -1;
        double jumpDy = 0;
        for (; t < STAND_AT; t += 0.1) {                                     // seated at 0.5 m, as the LD2450 sees it
            if (t > jumpUntil && r.nextDouble() < 0.04) {
                jumpUntil = t + 0.2 + r.nextDouble() * 0.8;
                jumpDy = (r.nextBoolean() ? 1 : -1) * (250 + r.nextDouble() * 130);
            }
            boolean jump = t <= jumpUntil;
            double y = -500 + (jump ? jumpDy : 0) + r.nextGaussian() * 20;
            double x = 50 + r.nextGaussian() * 20;
            double speed = jump ? (r.nextBoolean() ? 1 : -1) * (25 + r.nextDouble() * 35) : r.nextGaussian() * 6;
            frames.add(new Frame(t, new TargetSighting(x, y, 160, speed)));
        }
        for (; t < STAND_AT + 3.0; t += 0.1) {                               // stands up and walks out to 3 m
            double k = (t - STAND_AT) / 3.0;
            frames.add(new Frame(t, new TargetSighting(50 + r.nextGaussian() * 30, -500 - k * 2500 + r.nextGaussian() * 30,
                    160, 80 + r.nextGaussian() * 8)));
        }
        for (; t < STAND_AT + 10.0; t += 0.1) {
            frames.add(new Frame(t, null));                                  // gone
        }
        return frames;
    }

    static List<PresenceEvent> replay(BrainConfig config, List<Frame> frames) {
        Brain brain = new Brain(config);
        List<PresenceEvent> events = new ArrayList<>();
        for (Frame f : frames) {
            events.addAll(brain.onTargets(Math.round(f.t() * 1e6), false, f.seen() == null ? List.of() : List.of(f.seen())));
        }
        return events;
    }

    static long count(List<PresenceEvent> events, EventKind kind) {
        return events.stream().filter(e -> e.kind() == kind).count();
    }

    @Test
    void brainPyFlapsOnTheRecordedJitter() {
        for (long seed = 1; seed <= 5; seed++) {
            List<PresenceEvent> events = replay(BrainConfig.PYTHON, seatedSession(seed));
            assertThat(count(events, EventKind.SAT_DOWN)).as("seed %d: sat_down with brain.py's rules", seed)
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void withTheHysteresisItSitsOnceAndStandsOnce() {
        List<Double> sitDelays = new ArrayList<>();
        for (long seed = 1; seed <= 8; seed++) {
            List<PresenceEvent> events = replay(BrainConfig.DEFAULT, seatedSession(seed));
            assertThat(events).extracting(PresenceEvent::kind).as("seed %d", seed).containsExactly(EventKind.ARRIVED,
                    EventKind.APPROACHED, EventKind.SAT_DOWN, EventKind.STOOD_UP, EventKind.LEFT);
            double sat = events.get(2).tUs() / 1e6;
            assertThat(sat - SIT_AT).as("seed %d: seated within 7 s", seed).isBetween(0.0, 7.0);
            sitDelays.add(sat - SIT_AT);
            double stood = events.get(3).tUs() / 1e6;
            assertThat(stood - STAND_AT).as("seed %d: the real stand-up within 3 s", seed).isBetween(0.0, 3.0);
        }
        sitDelays.sort(null);
        assertThat(sitDelays.get(sitDelays.size() / 2)).as("median sit-down delay (the greeting's 5 s)").isLessThanOrEqualTo(5.0);
    }
}
