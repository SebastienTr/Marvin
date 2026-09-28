// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The supervisor with stand-in sidecars (shell scripts): output, restarts with a backoff, stopping. */
@DisabledOnOs(OS.WINDOWS)
class SupervisedProcessTest {

    static void waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(cond.getAsBoolean()).isTrue();
    }

    static SupervisedProcess sh(String script, List<String> out) {
        return new SupervisedProcess("test", () -> new ProcessBuilder("sh", "-c", script), out::add);
    }

    @Test
    void aSidecarThatStopsIsRestartedWithABackoff() throws InterruptedException {
        List<String> out = new CopyOnWriteArrayList<>();
        List<Integer> exits = new CopyOnWriteArrayList<>();
        SupervisedProcess p = sh("echo READY port=1234; echo '2026-09-28 10:00:00,000 WARNING marvin.sidecar.voice: no mic' >&2; exit 3", out);
        p.onExit(exits::add);
        p.start();
        waitFor(() -> exits.size() >= 2, 8000);             // 1 s, then 2 s of backoff
        p.stop();
        assertThat(out).startsWith("READY port=1234", "READY port=1234");
        assertThat(exits).startsWith(3, 3);
        assertThat(p.lastExit()).isEqualTo(3);
        assertThat(p.restarts()).isGreaterThanOrEqualTo(2);
        assertThat(p.lastLines()).contains("2026-09-28 10:00:00,000 WARNING marvin.sidecar.voice: no mic");
        assertThat(p.state()).isEqualTo(SupervisedProcess.State.STOPPED);
    }

    @Test
    void stoppingTerminatesItAndKillsItIfItDoesNotListen() throws InterruptedException {
        List<String> out = new CopyOnWriteArrayList<>();
        SupervisedProcess polite = sh("echo up; exec sleep 60", out);
        polite.start();
        waitFor(polite::alive, 5000);
        // its pipes are read on platform threads: blocked in native reads, virtual ones would pin the carriers
        // the web server's requests run on (with two CPUs, one sidecar was enough to starve them)
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName)
                .contains("sidecar-test", "sidecar-test-stderr");
        long t = System.nanoTime();
        polite.stop();
        assertThat(polite.alive()).isFalse();
        assertThat((System.nanoTime() - t) / 1e9).isLessThan(3);

        SupervisedProcess stubborn = sh("trap '' TERM; echo up; while true; do sleep 0.1; done", out);
        stubborn.start();
        waitFor(stubborn::alive, 5000);
        Thread.sleep(200);
        long t2 = System.nanoTime();
        stubborn.stop();
        assertThat(stubborn.alive()).isFalse();
        assertThat((System.nanoTime() - t2) / 1e9).isBetween(4.5, 9.0);
    }
}
