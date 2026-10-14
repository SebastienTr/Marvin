// SPDX-License-Identifier: MIT
package marvin.host.application.vitals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import marvin.host.application.vitals.port.out.VitalsStore;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.vitals.GateConfig;
import marvin.host.domain.vitals.Stillness;
import marvin.host.domain.vitals.VitalsInput;
import marvin.host.domain.vitals.VitalsMinute;
import marvin.host.domain.vitals.VitalsQuality;

/** The vitals service: readings, the once-a-second tick, the stored minutes, the presence radar's stillness. */
class VitalsServiceTest {
    static final double WALL0 = 1_789_982_880.0;              // a minute's start

    static final class FakeClocks implements Clocks {
        double t;

        @Override
        public double wallSeconds() {
            return WALL0 + t;
        }

        @Override
        public double monotonicSeconds() {
            return t;
        }
    }

    static final class MemoryStore implements VitalsStore {
        final TreeMap<Double, VitalsMinute> rows = new TreeMap<>();
        boolean down;
        final List<Double> deletedBefore = new ArrayList<>();

        @Override
        public void add(VitalsMinute m) {
            if (down) {
                throw new IllegalStateException("database down");
            }
            rows.put(m.t(), m);
        }

        @Override
        public List<VitalsMinute> minutes(double start, double end) {
            return List.copyOf(rows.subMap(start, true, end, false).values());
        }

        @Override
        public int deleteBefore(double t) {
            deletedBefore.add(t);
            int n = rows.headMap(t, false).size();
            rows.headMap(t, false).clear();
            return n;
        }
    }

    final FakeClocks clocks = new FakeClocks();
    final MemoryStore store = new MemoryStore();
    Stillness still = Stillness.STILL;
    final VitalsService service = new VitalsService(GateConfig.DEFAULT, store, () -> still, clocks, 90);

    static VitalsInput reading(double hr) {
        return new VitalsInput("192.0.2.4:47101", false, true, 14, hr, 820, true);
    }

    /** Readings at 10 Hz and the tick every second, until {@code to}. */
    void run(double to, double hr) {
        while (clocks.t < to - 1e-9) {
            service.onReading(reading(hr));
            if (Math.abs(clocks.t - Math.rint(clocks.t)) < 1e-6) {
                service.tick();
            }
            clocks.t = Math.rint((clocks.t + 0.1) * 10) / 10;
        }
    }

    @Test
    void noSensorNoCurrent() {
        assertThat(service.current()).isEmpty();
        service.tick();
        assertThat(service.minutes(24)).isEmpty();
    }

    @Test
    void minutesAreStoredWhenTheyEndAndTheOpenOneIsShown() {
        run(90, 64);
        assertThat(service.current()).get().extracting(c -> c.quality()).isEqualTo(VitalsQuality.GOOD);
        assertThat(store.rows).hasSize(1);
        VitalsMinute first = store.rows.firstEntry().getValue();
        assertThat(first.t()).isEqualTo(WALL0);
        assertThat(first.hr()).isEqualTo(64.0);
        assertThat(first.n()).isEqualTo(52);                   // good from 8 s (the gate needs 8 s of readings)
        List<VitalsMinute> minutes = service.minutes(24);
        assertThat(minutes).extracting(VitalsMinute::t).containsExactly(WALL0, WALL0 + 60);
        assertThat(minutes.get(1).n()).isEqualTo(30);          // the minute in progress
    }

    @Test
    void aMinuteTheStoreRefusesIsOfferedAgain() {
        store.down = true;
        run(70, 64);
        assertThat(store.rows).isEmpty();
        assertThat(service.minutes(24)).extracting(VitalsMinute::t).containsExactly(WALL0, WALL0 + 60);
        store.down = false;
        run(72, 64);
        assertThat(store.rows).containsKey(WALL0);
    }

    @Test
    void oldMinutesArePrunedOnceAnHour() {
        run(2, 64);
        assertThat(store.deletedBefore).containsExactly(WALL0 - 90 * 86400.0);
        run(5, 64);
        assertThat(store.deletedBefore).hasSize(1);
    }

    @Test
    void movingCountsOnlyWhileAPresenceRadarIsLive() {
        still = Stillness.MOVING;
        run(10, 64);                                           // no presence radar: the MR60's word only
        assertThat(service.current()).get().extracting(c -> c.quality()).isEqualTo(VitalsQuality.GOOD);
        while (clocks.t < 15) {
            service.onPresenceRadar();
            service.onReading(reading(64));
            clocks.t += 0.1;
        }
        assertThat(service.current()).get().extracting(c -> c.quality()).isEqualTo(VitalsQuality.UNSTABLE);
    }

    @Test
    void theHoursLimitTheHistory() {
        store.add(new VitalsMinute(WALL0 - 2 * 3600, 60.0, 12.0, 60));
        store.add(new VitalsMinute(WALL0 - 1800, 61.0, 12.0, 60));
        assertThat(service.minutes(1)).extracting(VitalsMinute::hr).containsExactly(61.0);
        assertThat(service.minutes(24)).extracting(VitalsMinute::hr).containsExactly(60.0, 61.0);
    }
}
