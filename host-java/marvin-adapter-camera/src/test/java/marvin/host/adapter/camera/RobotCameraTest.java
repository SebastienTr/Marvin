// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.vision.event.Frame;

class RobotCameraTest {
    HttpServer server;
    final AtomicInteger captures = new AtomicInteger();
    final AtomicInteger streams = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static DeviceStatus device(String name, boolean camera, boolean online, boolean simulated) {
        return new DeviceStatus("127.0.0.1:47100", name, Board.Role.ROBOT, "XIAO ESP32S3 Sense", "0.6.0", "127.0.0.1", -60, 3,
                100, simulated, camera, true, true, online, 0.1, 1_790_000_000.0, Map.of(), null, null, 0);
    }

    /** The firmware's camera server: /capture (unless {@code withCapture} is false) and /stream (MJPEG). */
    int serve(boolean withCapture, byte[] picture) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/capture", ex -> {
            if (!withCapture) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            captures.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, picture.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(picture);
            }
        });
        server.createContext("/stream", ex -> {
            streams.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) {
                for (int i = 0; i < 3; i++) {
                    o.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + picture.length
                            + "\r\nX-Marvin-Time-Us: 123\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    o.write(picture);
                    o.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    o.flush();
                }
            } catch (IOException e) {
                // the host hung up after its picture
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void aPictureFromTheRobotWithACamera() throws IOException {
        byte[] vga = Pictures.jpeg(640, 480);
        int port = serve(true, vga);
        RobotCamera cam = new RobotCamera(() -> List.of(device("marvin-000001", false, true, false),
                device("marvin-a1b2c3", true, true, false)), port);
        assertThat(cam.availability().available()).isTrue();
        assertThat(cam.availability().detail()).isEqualTo("The robot's camera (marvin-a1b2c3 at 127.0.0.1)");
        Frame f = cam.capture(5);
        assertThat(f.jpeg()).isEqualTo(vga);
        assertThat(f.width()).isEqualTo(640);
        assertThat(f.source()).isEqualTo("robot");
        assertThat(f.detail()).isEqualTo("marvin-a1b2c3 at 127.0.0.1");
        assertThat(captures.get()).isEqualTo(1);
        assertThat(streams.get()).isZero();
    }

    @Test
    void anOlderFirmwareGivesTheFirstPictureOfItsStream() throws IOException {
        byte[] big = Pictures.jpeg(1600, 1200);
        int port = serve(false, big);
        Frame f = new RobotCamera(() -> List.of(device("marvin-a1b2c3", true, true, false)), port).capture(5);
        assertThat(f.width()).isEqualTo(1280);
        assertThat(f.height()).isEqualTo(960);
        assertThat(streams.get()).isEqualTo(1);
    }

    @Test
    void noRobotWithACameraNoPicture() throws IOException {
        RobotCamera none = new RobotCamera(() -> List.of(device("marvin-a1b2c3", true, false, false),
                device("marvin-000001", false, true, false)), 9);
        assertThat(none.availability().available()).isFalse();
        assertThat(none.availability().detail()).isEqualTo("No robot with a camera is connected");
        assertThatThrownBy(() -> none.capture(1)).hasMessage("No robot with a camera is connected");
        // a robot that says it has a camera but does not answer
        java.net.ServerSocket closed = new java.net.ServerSocket(0);
        int port = closed.getLocalPort();
        closed.close();
        assertThatThrownBy(() -> new RobotCamera(() -> List.of(device("marvin-a1b2c3", true, true, false)), port).capture(2))
                .hasMessageStartingWith("marvin-a1b2c3's camera cannot be reached at 127.0.0.1:" + port);
    }

    @Test
    void aRealRobotComesBeforeASimulatedOne() {
        RobotCamera cam = new RobotCamera(() -> List.of(device("marvin-sim", true, true, true), device("marvin-zzz", true, true, false)), 81);
        assertThat(cam.robot()).hasValueSatisfying(d -> assertThat(d.name()).isEqualTo("marvin-zzz"));
    }

    @Test
    void theFirstPartOfAStreamWithoutLengths() throws IOException {
        byte[] a = Pictures.jpeg(64, 48);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.writeBytes("\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(a);
        o.writeBytes("\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(a);
        assertThat(RobotCamera.firstPart(new ByteArrayInputStream(o.toByteArray()))).isEqualTo(a);
    }
}
