// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Sensitivity;

class LogWriterTest {

    static MemoryEvent event(int i) {
        return MemoryEvent.draft(Instant.EPOCH.plusSeconds(i), "conversation", "heard", Sensitivity.NORMAL, "c:" + i, "line " + i,
                Map.of());
    }

    @Test
    void anEventCountsAsPendingFromItsSubmissionUntilItIsWritten() throws Exception {
        CountDownLatch inAppend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<MemoryEvent> written = new CopyOnWriteArrayList<>();
        try (LogWriter w = LogWriter.start(batch -> {
            inAppend.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            written.addAll(batch);
        })) {
            w.submit(event(1));
            assertThat(w.pending()).isEqualTo(1);           // queued
            assertThat(inAppend.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(w.pending()).isEqualTo(1);           // taken from the queue, not written yet
            assertThat(w.flush(100)).isFalse();
            release.countDown();
            assertThat(w.flush(2000)).isTrue();
            assertThat(written).hasSize(1);
        }
    }

    @Test
    void closingWritesTheLastEventsInsteadOfInterruptingThem() {
        List<MemoryEvent> written = new CopyOnWriteArrayList<>();
        LogWriter w = LogWriter.start(batch -> {
            try {
                Thread.sleep(50);                       // a slow database
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted in the middle of a write");
            }
            written.addAll(batch);
        });
        for (int i = 0; i < 5; i++) {
            w.submit(event(i));
        }
        w.close(5000);
        assertThat(written).hasSize(5);
    }
}
