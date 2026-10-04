// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** The voice first: a task waits while a conversation goes on and its request is cut when one starts. */
class ModelLaneTest {
    double now = 100;

    @Test
    void oneRequestAtATimeTheVoiceComesFirst() throws Exception {
        ModelLane lane = new ModelLane(1, () -> now);
        assertThat(lane.yieldsToVoice()).isTrue();
        ModelLane.Lease lease = lane.acquire(() -> false, null);
        assertThat(lease).isNotNull();
        lane.voice(true);
        assertThat(lease.preempted()).isTrue();             // the task's request stops
        lease.close();

        AtomicInteger waited = new AtomicInteger();
        CompletableFuture<ModelLane.Lease> next = CompletableFuture.supplyAsync(() -> {
            try {
                return lane.acquire(() -> false, waited::incrementAndGet);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(250);
        assertThat(next).isNotDone();
        assertThat(waited.get()).isEqualTo(1);              // told once that it waits
        lane.voice(false);
        Thread.sleep(250);
        assertThat(next).isNotDone();                       // a follow-up question comes fast: the grace
        now += ModelLane.GRACE_S;
        ModelLane.Lease again = next.get(2, TimeUnit.SECONDS);
        assertThat(again.preempted()).isFalse();
        again.close();
    }

    @Test
    void aCancelledTaskStopsWaiting() throws Exception {
        ModelLane lane = new ModelLane(1, () -> now);
        lane.voice(true);
        assertThat(lane.acquire(() -> true, null)).isNull();
    }

    @Test
    void severalAtOnceNeverWaitNorCut() throws Exception {
        ModelLane lane = new ModelLane(2, () -> now);
        lane.voice(true);
        ModelLane.Lease lease = lane.acquire(() -> false, null);
        assertThat(lease).isNotNull();
        lane.voice(false);
        lane.voice(true);
        assertThat(lease.preempted()).isFalse();
        assertThat(lane.free()).isTrue();
    }
}
