// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.out.BackfillSource;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.MemoryEvent;

class MemoryLogServiceTest {
    static final Instant T = Instant.parse("2026-09-28T12:00:00Z");
    final MemoryFixture m = new MemoryFixture(T, new FakeMemoryModel());

    static MemoryEvent heard(long id, String text) {
        return EventFeeds.conversation(id, 1_790_000_000 + id, "heard", text, Map.of()).orElseThrow();
    }

    @Test
    void eventsAreWrittenOffTheCallersThreadRedactedAndFilteredBySource() {
        try (MemoryLogService log = new MemoryLogService(m.store.log, m.store.state, m.settings, m.clock, false)) {
            log.record(heard(1, "Mon code PIN est 4821"));
            log.record(EventFeeds.presence(1, 1_790_000_000, "arrived", "You came in", Map.of()).orElseThrow());
            m.settings.update(Map.of("collect_brain", false));
            log.record(EventFeeds.presence(2, 1_790_000_100, "sat_down", "You sat down", Map.of()).orElseThrow());
            log.record(heard(1, "Mon code PIN est 4821"));           // the same record again: kept once
            assertThat(log.flush(5000)).isTrue();
        }
        assertThat(m.store.log.rows.values()).extracting(MemoryEvent::body).containsExactly("Mon code PIN est [redacted]", "You came in");
    }

    /** What the voice's thread pays per line of conversation: mapping, redaction and a queue offer (printed). */
    @Test
    void recordingCostsTheVoiceMicrosecondsNotADatabaseRoundTrip() {
        m.store.log.failure = new IllegalStateException("database down");        // the writer thread is stuck retrying
        try (MemoryLogService log = new MemoryLogService(m.store.log, m.store.state, m.settings, m.clock, false)) {
            for (int i = 0; i < 2000; i++) {                                     // warm the code up
                EventFeeds.conversation(i, 1_790_000_000, "heard", "Quel temps fera-t-il demain à Lyon ?", Map.of("language", "fr"))
                        .ifPresent(log::record);
            }
            int n = 5000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                EventFeeds.conversation(10_000 + i, 1_790_000_000, "reply", "Demain à Lyon, 18 degrés et du soleil l'après-midi.",
                        Map.of("language", "fr", "latency", Map.of("endpoint", 0.55))).ifPresent(log::record);
            }
            double microseconds = (System.nanoTime() - t0) / 1e3 / n;
            System.out.printf(java.util.Locale.ROOT, "memory: %.1f µs per conversation line on the caller's thread%n", microseconds);
            assertThat(microseconds).isLessThan(500);
            m.store.log.failure = null;
        }
    }

    @Test
    void aDatabaseOutageIsRetriedNotLost() throws Exception {
        m.store.log.failure = new IllegalStateException("database down");
        try (MemoryLogService log = new MemoryLogService(m.store.log, m.store.state, m.settings, m.clock, false)) {
            log.record(heard(1, "a"));
            log.record(heard(2, "b"));
            Thread.sleep(300);
            assertThat(log.pending()).isEqualTo(2);
            m.store.log.failure = null;
            assertThat(log.flush(5000)).isTrue();
        }
        assertThat(m.store.log.count()).isEqualTo(2);
    }

    @Test
    void theCatchUpReadsAfterItsMarkAndNeverDuplicates() {
        MemoryLogService log = new MemoryLogService(m.store.log, m.store.state, m.settings, m.clock, true);
        log.record(heard(2, "already fed live"));
        List<Long> asked = new ArrayList<>();
        BackfillSource source = new BackfillSource() {
            @Override
            public String name() {
                return "conversation";
            }

            @Override
            public Page next(long afterId, int limit) {
                asked.add(afterId);
                if (afterId == 0) {
                    return new Page(List.of(heard(1, "first"), heard(2, "already fed live")), 3, false);   // id 3 was a note
                }
                if (afterId == 3) {
                    return new Page(List.of(heard(4, "last")), 4, false);
                }
                return new Page(List.of(), afterId, true);
            }
        };
        assertThat(log.catchUp(source)).isEqualTo(2);
        assertThat(asked).containsExactly(0L, 3L, 4L);
        assertThat(m.store.log.rows.values()).extracting(MemoryEvent::body).containsExactly("already fed live", "first", "last");
        // the next catch-up starts after the high-water mark: nothing read twice
        assertThat(log.catchUp(source)).isZero();
        assertThat(asked).containsExactly(0L, 3L, 4L, 4L);
        assertThat(m.store.state.get("feed:conversation")).containsEntry("after", 4L);
    }

    @Test
    void whatTheOwnerForgotIsNeverCaughtUpAgain() {
        MemoryLogService log = new MemoryLogService(m.store.log, m.store.state, m.settings, m.clock, true);
        log.record(heard(1, "fed live, then forgotten by range"));
        log.record(heard(2, "fed live, then forgotten alone"));
        log.record(heard(3, "kept"));
        java.time.Instant t1 = heard(1, "x").ts();
        m.admin.forgetEvents(t1, t1.plusMillis(500));
        long two = m.store.log.rows.values().stream().filter(e -> e.body().contains("alone")).findFirst().orElseThrow().id();
        m.admin.forgetEvent(two);
        BackfillSource source = new BackfillSource() {
            @Override
            public String name() {
                return "conversation";
            }

            @Override
            public Page next(long afterId, int limit) {
                return afterId == 0 ? new Page(List.of(heard(1, "fed live, then forgotten by range"),
                        heard(2, "fed live, then forgotten alone"), heard(3, "kept"), heard(4, "missed by the live feed")), 4, true)
                        : new Page(List.of(), afterId, true);
            }
        };
        assertThat(log.catchUp(source)).isEqualTo(1);
        assertThat(m.store.log.rows.values()).extracting(MemoryEvent::body)
                .contains("kept", "missed by the live feed").doesNotContain("fed live, then forgotten by range",
                        "fed live, then forgotten alone");
    }
}
