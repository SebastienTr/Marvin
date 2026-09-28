// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot.mvrec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import marvin.host.adapter.robot.FrameDispatcher;
import marvin.host.adapter.robot.UdpRobotLink;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.RobotLinkProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A recording written by the Java link (the way {@code marvin.robot.record} does it) is read by the
 * Python host's {@code record.py}, which replays it to the brain events of the original.
 */
class PythonReadsJavaRecordingTest {

    @Test
    void thePythonHostReplaysAJavaRecording(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("room.mvrec.gz");
        UdpRobotLink link = new UdpRobotLink(new RobotLinkProcessor(Extrinsics.DEFAULT), new FrameDispatcher(x -> { }));
        link.record(RecordingWriter.create(f, "test", Map.of("scenario", "java-rerecorded")));
        try (InputStream in = new GZIPInputStream(getClass().getResourceAsStream(
                "/marvin/contracts/golden/recordings/room_loop.mvrec.gz"));
             RecordingReader r = new RecordingReader(in, "room_loop")) {
            long t0 = System.nanoTime();
            for (RecordingReader.Datagram d : r) {
                link.receive(d.data(), d.data().length, d.from(), t0 + d.tUs() * 1000);
            }
        }
        link.close();

        String out = marvin.host.adapter.robot.PythonHostAccess.run("""
                import json
                from marvin_host import record
                from marvin_host.brain import Brain
                path = %s
                s = record.summarize(path)
                b = Brain()
                record.replay(path, b, speed=None)
                s["events"] = [e.kind.value for e in b.events]
                print(json.dumps(s, default=str))
                """.formatted(pyStr(f)));
        JsonNode s = new ObjectMapper().readTree(out);
        assertThat(s.get("datagrams").asInt()).isEqualTo(4157);
        assertThat(s.get("truncated").asBoolean()).isFalse();
        assertThat(s.get("meta").get("scenario").asString()).isEqualTo("java-rerecorded");
        assertThat(s.get("devices").get(0).get("device_name").asString()).isEqualTo("marvin-53494d");
        assertThat(s.get("duration_s").asDouble()).isCloseTo(71.975, org.assertj.core.api.Assertions.within(0.5));
        JsonNode expected = new ObjectMapper().readTree(getClass().getResourceAsStream(
                "/marvin/contracts/golden/recordings/index.json")).get("scenarios").get(0).get("events");
        assertThat(s.get("events").toString()).isEqualTo(expected.toString());
    }

    private static String pyStr(Path p) {
        return "'" + p.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
