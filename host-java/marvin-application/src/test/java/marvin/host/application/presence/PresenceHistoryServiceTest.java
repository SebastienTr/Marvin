// SPDX-License-Identifier: MIT
package marvin.host.application.presence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.presence.port.out.PresenceHistoryStore;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.presence.history.Sample;
import marvin.host.domain.presence.history.StoredEvent;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/** The history's own markers and samples, as the Python UIServer writes them. */
class PresenceHistoryServiceTest {

    static final class FakeClocks implements Clocks {
        double wall = 1_790_000_000;
        double mono = 100;

        void advance(double s) {
            wall += s;
            mono += s;
        }

        @Override
        public double wallSeconds() {
            return wall;
        }

        @Override
        public double monotonicSeconds() {
            return mono;
        }
    }

    static class MemoryStore implements PresenceHistoryStore {
        final List<StoredEvent> events = new ArrayList<>();
        final List<Sample> samples = new ArrayList<>();

        @Override
        public StoredEvent add(StoredEvent e, Long deviceTUs) {
            StoredEvent s = new StoredEvent(e.ts(), e.kind(), e.detail(), e.data(), events.size() + 1);
            events.add(s);
            return s;
        }

        @Override
        public List<StoredEvent> events(double start, double end) {
            return events.stream().filter(e -> e.ts() >= start && e.ts() < end).toList();
        }

        @Override
        public List<StoredEvent> recent(int limit, long sinceId, Collection<String> exclude) {
            return events.reversed().stream().filter(e -> e.id() > sinceId && !exclude.contains(e.kind())).limit(limit).toList();
        }

        @Override
        public void addSample(Sample s) {
            samples.add(s);
        }

        @Override
        public List<Sample> samples(double start, double end) {
            return samples.stream().filter(s -> s.ts() >= start && s.ts() < end).toList();
        }
    }

    static final class Presence implements PresenceQuery {
        PresenceState state = PresenceState.EMPTY;

        @Override
        public PresenceState state() {
            return state;
        }

        @Override
        public List<PresenceEvent> recentEvents() {
            return List.of();
        }
    }

    static PresenceState seated(long tUs, Double breath, Double heart) {
        return new PresenceState(tUs, true, true, null, null, 0.9, 0, 10, 60, breath, heart, true, true, 1);
    }

    @Test
    void marksStartOnlineOfflineStopAndKeepsMinuteSamples() {
        FakeClocks clocks = new FakeClocks();
        MemoryStore store = new MemoryStore();
        Presence presence = new Presence();
        List<String> heard = new ArrayList<>();
        PresenceHistoryService h = new PresenceHistoryService(presence, store, List.of(e -> heard.add(e.kind())), clocks,
                new LocalDays(ZoneId.of("UTC")), 15, HistoryWriter.inline());
        h.start();
        assertThat(h.online()).isFalse();
        assertThat(h.everOnline()).isFalse();

        // frames: the brain's clock moves, online, seated with vital signs for most of the minute
        for (int i = 1; i <= 50; i++) {
            presence.state = seated(i * 1_000_000L, i > 10 ? 14.0 : null, i > 10 ? 66.0 : null);
            clocks.advance(1);
            h.sample();
        }
        assertThat(h.online()).isTrue();
        h.onEvent(new PresenceEvent(EventKind.STOOD_UP, 51_000_000L, "after 1 min seated", Map.of("seated_s", 60.0)));
        // the minute changes: the first minute is kept
        for (int i = 0; i < 20; i++) {
            clocks.advance(1);
            h.sample();
        }
        assertThat(h.online()).isFalse();                      // no new frame for more than 15 s
        h.stop();

        assertThat(heard).containsExactly("host_started", "robot_online", "stood_up", "robot_offline", "host_stopped");
        assertThat(store.events.get(1).data()).containsEntry("present", true).containsEntry("seated", true);
        Sample first = store.samples.getFirst();
        assertThat(first.ts()).isEqualTo(Math.floor((1_790_000_000 + 1) / 60.0) * 60);      // the minute it started
        assertThat(first.seated()).isEqualTo(1.0);
        assertThat(first.breath()).isEqualTo(14.0);
        assertThat(h.recent(10, 0, false)).hasSize(5);
        assertThat(h.today().date().toString()).isEqualTo(new LocalDays(ZoneId.of("UTC")).dayOf(clocks.wall).toString());
    }

    /** A store that fails (the database is down) until {@code down} is cleared. */
    static final class FlakyStore extends MemoryStore {
        volatile boolean down = true;
        volatile int attempts;

        @Override
        public synchronized StoredEvent add(StoredEvent e, Long deviceTUs) {
            attempts++;
            if (down) {
                throw new IllegalStateException("Connection to 127.0.0.1:5433 refused");
            }
            return super.add(e, deviceTUs);
        }

        @Override
        public synchronized void addSample(Sample s) {
            attempts++;
            if (down) {
                throw new IllegalStateException("Connection to 127.0.0.1:5433 refused");
            }
            super.addSample(s);
        }
    }

    @Test
    void aStoreOutageNeitherHoldsUpTheBrainNorLosesEventsOrMixesMinutes() throws InterruptedException {
        FakeClocks clocks = new FakeClocks();
        FlakyStore store = new FlakyStore();
        Presence presence = new Presence();
        List<Long> ids = new java.util.concurrent.CopyOnWriteArrayList<>();
        HistoryWriter writer = HistoryWriter.start();
        PresenceHistoryService h = new PresenceHistoryService(presence, store, List.of(e -> ids.add(e.id())), clocks,
                new LocalDays(ZoneId.of("UTC")), 15, writer);
        h.start();
        long t0 = System.nanoTime();
        for (int i = 1; i <= 150; i++) {                    // two and a half minutes of frames, the store down
            presence.state = seated(i * 1_000_000L, 14.0, 66.0);
            clocks.advance(1);
            h.sample();
            if (i % 30 == 0) {
                h.onEvent(new PresenceEvent(EventKind.STOOD_UP, i * 1_000_000L, "up", Map.of()));
            }
        }
        assertThat((System.nanoTime() - t0) / 1e9).as("the caller never waits for the store").isLessThan(1.0);
        Thread.sleep(700);
        assertThat(store.attempts).as("retried").isGreaterThan(1);
        assertThat(store.events).isEmpty();
        assertThat(h.pendingWrites()).isGreaterThan(5);

        store.down = false;                                 // the database is back
        assertThat(writer.flush(10_000)).isTrue();
        assertThat(store.events).extracting(StoredEvent::kind).containsExactly("host_started", "robot_online",
                "stood_up", "stood_up", "stood_up", "stood_up", "stood_up");
        assertThat(ids).hasSize(7).isSorted();
        // one sample per minute, each with its own minute (not one averaged over the outage)
        assertThat(store.samples).extracting(Sample::ts).doesNotHaveDuplicates().isSorted().hasSize(2);
        assertThat(store.samples.get(1).ts() - store.samples.get(0).ts()).isEqualTo(60.0);
        h.stop();
    }
}
