// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import marvin.host.domain.presence.BrainConfig;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.LinkStats;
import marvin.host.domain.robot.RobotLinkProcessor;

/**
 * The Python simulator ({@code marvin-host sim}) against the Java link over real UDP on localhost,
 * as test_receiver.py does with the Python receiver: the handshake completes, lidar revolutions,
 * LD2450 targets and vitals arrive, and the brain sees the simulated person arrive.
 */
class SimulatorLinkIT {

    @Test
    void thePythonSimulatorTalksToTheJavaLink(@TempDir Path dir) throws Exception {
        GoldenRecordingsTest.Capture cap = new GoldenRecordingsTest.Capture(BrainConfig.DEFAULT, Extrinsics.DEFAULT);
        UdpRobotLink link = new UdpRobotLink(new RobotLinkProcessor(Extrinsics.DEFAULT), new FrameDispatcher(cap::accept));
        link.start("127.0.0.1", 0);
        Path log = dir.resolve("sim.log");
        try {
            Process sim = PythonHost.start(log, "sim", "--host", "127.0.0.1", "--port",
                    String.valueOf(link.boundPort()), "--seconds", "10");
            boolean done = sim.waitFor(60, TimeUnit.SECONDS);
            if (!done) {
                sim.destroyForcibly();
            }
            assertThat(done).as("the simulator ended").isTrue();
            assertThat(sim.exitValue()).as(Files.readString(log)).isZero();
        } finally {
            link.close();
        }
        assertThat(link.acksSent()).isPositive();
        assertThat(link.processor().devices()).singleElement().satisfies(d -> {
            assertThat(d.name()).isEqualTo("marvin-53494d");
            assertThat(d.hello().simulated()).isTrue();
            LinkStats.Snapshot s = d.stats().snapshot();
            assertThat(s.lidarPackets()).as("lidar packets (sent only once linked)").isGreaterThan(1000);
            assertThat(s.radarFrames()).isGreaterThan(50);
            assertThat(s.crcErrors()).isZero();
            assertThat(s.bad()).isZero();
        });
        assertThat(cap.hellos).hasSize(1);
        assertThat(cap.scans.size()).as("lidar revolutions").isGreaterThan(50);
        assertThat(cap.scans).allSatisfy(s -> assertThat(s[1]).isGreaterThan(100));
        assertThat(cap.presence.state().vitalsSensor()).isTrue();
        assertThat(cap.presence.state().simulated()).isTrue();
        assertThat(cap.events).extracting(e -> e.kind()).contains(EventKind.ARRIVED);
    }
}
