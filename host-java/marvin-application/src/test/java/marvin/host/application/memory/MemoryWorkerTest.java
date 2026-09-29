// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.out.VoiceActivity;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Sensitivity;

class MemoryWorkerTest {
    /** Monday 28 September 2026, 14:00 in Paris. */
    static final Instant NOW = ZonedDateTime.of(2026, 9, 28, 14, 0, 0, 0, MemoryFixture.ZONE).toInstant();
    final FakeMemoryModel model = new FakeMemoryModel();
    final MemoryFixture m = new MemoryFixture(NOW, model);
    final AtomicBoolean busy = new AtomicBoolean();
    volatile double lastActivity;
    final AtomicInteger warmUps = new AtomicInteger();
    final MemoryWorker worker = new MemoryWorker(m.consolidator, m.nightly, m.store.log, m.settings, new VoiceActivity() {
        @Override
        public boolean busy() {
            return busy.get();
        }

        @Override
        public double lastActivity() {
            return lastActivity;
        }
    }, warmUps::incrementAndGet, m.store.state, m.days, m.clock, m.config);

    @AfterEach
    void close() {
        worker.close();
    }

    void markNightDone(Instant at) {
        m.store.state.put("worker", Map.of("last_night_at", at.getEpochSecond() + 0.0));
    }

    @Test
    void anIdlePassWaitsForTenQuietMinutes() throws Exception {
        markNightDone(NOW.minusSeconds(3600));
        m.say(NOW.minusSeconds(120), "heard", "J'habite à Lyon");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 9));
        lastActivity = m.clock.wallSeconds() - 120;
        assertThat(worker.tick()).isNull();
        m.clock.advance(7 * 60);
        assertThat(worker.tick()).isNull();                // 9 quiet minutes
        m.clock.advance(60);
        busy.set(true);
        assertThat(worker.tick()).isNull();
        busy.set(false);
        ConsolidateMemory.Report r = worker.tick().get(5, TimeUnit.SECONDS);
        assertThat(r.pass()).isEqualTo(ConsolidateMemory.Pass.IDLE);
        assertThat(r.outcome()).isEqualTo("done");
        assertThat(r.counts()).containsEntry("added", 1).containsEntry("events", 1);
        assertThat(warmUps.get()).isEqualTo(1);            // the voice's own model was used: its prompt cache is gone
        assertThat(worker.tick()).isNull();                // nothing new
        assertThat(worker.status().last().outcome()).isEqualTo("done");
        assertThat(m.store.state.get("worker")).containsKey("last_idle_at");
    }

    @Test
    void aLargerNightModelDoesNotNeedAWarmUp() throws Exception {
        markNightDone(NOW.minusSeconds(3600));
        m.settings.update(Map.of("memory_model", "qwen3:27b"));
        m.say(NOW.minusSeconds(3600), "heard", "J'habite à Lyon");
        ConsolidateMemory.Report r = worker.consolidateNow(ConsolidateMemory.Pass.IDLE).get(5, TimeUnit.SECONDS);
        assertThat(r.model()).isEqualTo("qwen3:27b");
        assertThat(warmUps.get()).isZero();
    }

    @Test
    void theNightlyPassIsDueAtTheNightHourOrTheFirstIdleMomentAfter() throws Exception {
        markNightDone(ZonedDateTime.of(2026, 9, 27, 3, 5, 0, 0, MemoryFixture.ZONE).toInstant());
        assertThat(worker.nextNight()).isEqualTo(ZonedDateTime.of(2026, 9, 28, 3, 0, 0, 0, MemoryFixture.ZONE).toEpochSecond());
        m.brain(NOW.minusSeconds(86_400), "arrived", "You came in", Sensitivity.NORMAL);
        m.say(NOW.minusSeconds(86_000), "heard", "Je pars à Oslo mardi prochain");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner flies to Oslo on 2026-09-29.", 6));
        ConsolidateMemory.Report r = worker.tick().get(5, TimeUnit.SECONDS);
        assertThat(r.pass()).isEqualTo(ConsolidateMemory.Pass.NIGHTLY);
        assertThat(r.outcome()).isEqualTo("done");
        assertThat(r.counts()).containsEntry("added", 1).containsEntry("days", 1).containsEntry("profile", 1);
        assertThat(r.steps()).extracting(s -> s.get("step"))
                .containsExactly("extract", "embeddings", "days", "roll-ups", "profile", "decay", "retention");
        assertThat(m.admin.profile().orElseThrow().content()).isEqualTo("The owner flies to Oslo on 2026-09-29.");
        assertThat(worker.nextNight()).isEqualTo(ZonedDateTime.of(2026, 9, 29, 3, 0, 0, 0, MemoryFixture.ZONE).toEpochSecond());
        assertThat(worker.tick()).isNull();
    }

    @Test
    void aNewInstallRunsItsFirstNightAtTheFirstIdleMoment() {
        assertThat(worker.nextNight()).isEqualTo(ZonedDateTime.of(2026, 9, 28, 3, 0, 0, 0, MemoryFixture.ZONE).toEpochSecond());
    }

    @Test
    void thePassYieldsToTheVoiceAndResumesLater() throws Exception {
        markNightDone(NOW.minusSeconds(3600));
        m.say(NOW.minusSeconds(4000), "heard", "J'habite à Lyon");
        m.say(NOW.minusSeconds(3000), "heard", "J'ai un chat");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner " + r.lines().getFirst().text() + ".", 5));
        model.beforeCall = () -> busy.set(model.requests.size() == 2);   // someone speaks during the second batch
        ConsolidateMemory.Report r = worker.consolidateNow(ConsolidateMemory.Pass.IDLE).get(5, TimeUnit.SECONDS);
        assertThat(r.outcome()).isEqualTo("yielded");
        assertThat(r.counts()).containsEntry("added", 1);
        assertThat(m.store.log.unconsolidatedCount()).isEqualTo(1);
        model.beforeCall = () -> { };
        busy.set(false);
        ConsolidateMemory.Report again = worker.consolidateNow(ConsolidateMemory.Pass.IDLE).get(5, TimeUnit.SECONDS);
        assertThat(again.outcome()).isEqualTo("done");
        assertThat(m.store.facts.rows).hasSize(2);
    }

    @Test
    void aMissingModelFailsThePassWithItsFix() throws Exception {
        markNightDone(NOW.minusSeconds(3600));
        m.say(NOW.minusSeconds(4000), "heard", "J'habite à Lyon");
        model.extract = r -> {
            throw new marvin.host.application.memory.port.out.MemoryModel.Unavailable("Ollama has no model 'x'", "Run `ollama pull x`.");
        };
        ConsolidateMemory.Report r = worker.consolidateNow(ConsolidateMemory.Pass.IDLE).get(5, TimeUnit.SECONDS);
        assertThat(r.outcome()).isEqualTo("failed");
        assertThat(r.fix()).isEqualTo("Run `ollama pull x`.");
        assertThat(m.store.log.unconsolidatedCount()).isEqualTo(1);
        // scheduled passes then wait a minute (doubling) before trying again
        assertThat(worker.tick()).isNull();
        m.clock.advance(61);
        assertThat(worker.tick().get(5, TimeUnit.SECONDS).outcome()).isEqualTo("failed");
        m.clock.advance(61);
        assertThat(worker.tick()).isNull();
        m.clock.advance(60);
        assertThat(worker.tick()).isNotNull();
    }

    @Test
    void theWorkerCanBeSwitchedOff() {
        m.settings.update(Map.of("worker", false));
        m.say(NOW.minusSeconds(4000), "heard", "J'habite à Lyon");
        assertThat(worker.tick()).isNull();
        assertThat(worker.status().state()).isEqualTo("off");
        assertThat(worker.status().pending()).isEqualTo(1);
    }
}
