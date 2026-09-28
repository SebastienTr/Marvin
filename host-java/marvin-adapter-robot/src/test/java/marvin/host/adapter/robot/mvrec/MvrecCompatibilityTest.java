// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot.mvrec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.Endpoint;
import marvin.host.domain.robot.Hello;
import marvin.host.domain.robot.MessageType;
import marvin.host.domain.robot.ProtocolV1;
import marvin.host.domain.robot.Wire;

/** The {@code .mvrec} v1 files of both hosts are the same format, byte for byte. */
class MvrecCompatibilityTest {

    static byte[] golden(String name) throws IOException {
        try (InputStream in = new GZIPInputStream(
                MvrecCompatibilityTest.class.getResourceAsStream("/marvin/contracts/golden/recordings/" + name))) {
            return in.readAllBytes();
        }
    }

    @Test
    void rewritingAPythonRecordingGivesTheSameBytes() throws IOException {
        for (String name : List.of("room_loop.mvrec.gz", "damaged_link.mvrec.gz", "robot_reboot.mvrec.gz")) {
            byte[] python = golden(name);
            ByteArrayOutputStream java = new ByteArrayOutputStream();
            try (RecordingReader r = new RecordingReader(new ByteArrayInputStream(python), name);
                 RecordingWriter w = new RecordingWriter(java, false, r.metaBytes(), 0)) {
                for (RecordingReader.Datagram d : r) {
                    w.writeDatagramAt(d.data(), d.data().length, d.from(), d.tUs());
                }
            }
            assertThat(java.toByteArray()).as(name).isEqualTo(python);
        }
    }

    @Test
    void aFileCutShortEndsAtItsLastCompleteRecord() throws IOException {
        byte[] full = golden("damaged_link.mvrec.gz");
        int all = count(full);
        byte[] cut = Arrays.copyOf(full, full.length - 7);
        try (RecordingReader r = new RecordingReader(new ByteArrayInputStream(cut), "cut")) {
            int n = 0;
            for (RecordingReader.Datagram d : r) {
                n++;
            }
            assertThat(n).isEqualTo(all - 1);
            assertThat(r.truncated()).isTrue();
        }
        // the same, compressed and cut in the middle of the gzip stream
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
            g.write(full);
        }
        byte[] gzCut = Arrays.copyOf(gz.toByteArray(), gz.size() / 2);
        try (RecordingReader r = new RecordingReader(new ByteArrayInputStream(gzCut), "gz-cut")) {
            int n = 0;
            for (RecordingReader.Datagram d : r) {
                n++;
            }
            assertThat(n).isBetween(1, all - 1);
            assertThat(r.truncated()).isTrue();
        }
    }

    private static int count(byte[] file) {
        try (RecordingReader r = new RecordingReader(new ByteArrayInputStream(file), "f")) {
            int n = 0;
            for (RecordingReader.Datagram d : r) {
                n++;
            }
            return n;
        }
    }

    @Test
    void refusesWhatIsNotARecording() {
        assertThatThrownBy(() -> new RecordingReader(new ByteArrayInputStream("hello world".getBytes()), "x"))
                .isInstanceOf(RecordingException.class).hasMessageContaining("not a Marvin recording");
        byte[] v2 = {'M', 'V', 'R', 'E', 'C', 2, 2, 0, 0, 0, '{', '}'};
        assertThatThrownBy(() -> new RecordingReader(new ByteArrayInputStream(v2), "x"))
                .isInstanceOf(RecordingException.class).hasMessageContaining("unsupported recording version 2");
        // a datagram record from a sender never introduced
        ByteBuffer b = ByteBuffer.allocate(12 + 11 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(Mvrec.MAGIC).put((byte) 1).putInt(2).put("{}".getBytes());
        b.put((byte) Mvrec.DATAGRAM).putLong(0).putShort((short) 4).putShort((short) 7).putShort((short) 0);
        RecordingReader r = new RecordingReader(new ByteArrayInputStream(b.array()), "x");
        assertThatThrownBy(() -> r.iterator().hasNext()).isInstanceOf(RecordingException.class)
                .hasMessageContaining("unknown sender");
    }

    @Test
    void aJavaRecordingKeepsDevicesAndIsReadBack(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("java.mvrec.gz");
        Endpoint robot = new Endpoint("192.0.2.1", ProtocolV1.DEVICE_PORT);
        Hello hello = new Hello(new byte[] {2, 0x4d, 0x56, 0x53, 0x49, 0x4d}, 255, 1, -40, 2000, "sim-0.1.0");
        List<byte[]> sent = new ArrayList<>();
        try (RecordingWriter w = RecordingWriter.create(f, "test", Map.of("scenario", "java"))) {
            long t = System.nanoTime();
            byte[] h = Wire.pack(MessageType.HELLO, 0, 1000, hello.encode());
            w.writeDatagram(h, h.length, robot, t);
            w.writeDevice(new Device(hello, robot), t);
            sent.add(h);
            for (int i = 1; i < 50; i++) {
                byte[] log = Wire.pack(MessageType.LOG, i, 1000 + i, ("line " + i).getBytes());
                w.writeDatagram(log, log.length, robot, t + i * 1_000_000L);
                sent.add(log);
            }
        }
        assertThat(Files.readAllBytes(f)).startsWith(0x1f, 0x8b);
        try (RecordingReader r = new RecordingReader(f)) {
            List<byte[]> got = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            for (RecordingReader.Datagram d : r) {
                got.add(d.data());
                times.add(d.tUs());
                assertThat(d.from()).isEqualTo(robot);
            }
            assertThat(got).hasSize(50);
            for (int i = 0; i < 50; i++) {
                assertThat(got.get(i)).isEqualTo(sent.get(i));
            }
            assertThat(times.get(49) - times.get(0)).isEqualTo(49_000L);
            assertThat(r.meta()).containsEntry("scenario", "java").containsEntry("protocol", 1)
                    .containsEntry("marvin_host", "test").containsKey("host_start");
            assertThat(r.devices()).singleElement().satisfies(d -> assertThat(d)
                    .containsEntry("address", "192.0.2.1:47101").containsEntry("device_name", "marvin-53494d")
                    .containsEntry("board", 255).containsEntry("simulated", true));
        }
    }
}
