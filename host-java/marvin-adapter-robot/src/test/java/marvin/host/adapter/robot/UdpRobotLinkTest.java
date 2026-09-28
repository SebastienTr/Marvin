// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.robot.FaceLinkService;
import marvin.host.application.robot.RobotLinkService;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.FaceState;
import marvin.host.domain.robot.Header;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.Ld2450;
import marvin.host.domain.robot.MessageType;
import marvin.host.domain.robot.RobotLinkProcessor;
import marvin.host.domain.robot.SensorFrame;
import marvin.host.domain.robot.Vitals;
import marvin.host.domain.robot.Wire;

/** The UDP link over real sockets on localhost: handshake, frames, face link, tap, shedding. */
class UdpRobotLinkTest {
    private final List<SensorFrame> frames = Collections.synchronizedList(new ArrayList<>());
    private UdpRobotLink link;

    private UdpRobotLink start() {
        link = new UdpRobotLink(new RobotLinkProcessor(Extrinsics.DEFAULT), new FrameDispatcher(frames::add));
        link.start("127.0.0.1", 0);
        return link;
    }

    @AfterEach
    void stop() {
        if (link != null) {
            link.close();
        }
    }

    @Test
    void answersEveryHelloWithAHostAckAndHandsOnTheFrames() throws Exception {
        start();
        try (FakeRobot robot = new FakeRobot(link.boundPort())) {
            robot.send(MessageType.LD2450, 5, Ld2450.build(List.of(new Ld2450.Target(100, 900, 0, 320))));   // before HELLO
            robot.send(MessageType.HELLO, 10, FakeRobot.hello(Board.D1_MINI, 1, 1).encode());
            byte[] ack = robot.receive(2000);
            assertThat(ack).isNotNull();
            Header h = Wire.header(ack);
            assertThat(h).isEqualTo(new Header(MessageType.HOST_ACK.code(), 0, 0));
            assertThat(HostMessages.hostAckClock(Wire.payload(ack))).isBetween(0L, 60_000_000L);

            robot.send(MessageType.LD2450, 20, Ld2450.build(List.of(new Ld2450.Target(100, 900, -5, 320))));
            robot.send(MessageType.VITALS, 30, new Vitals(true, 14.5, 62.0, 0.1, -0.1, 800).encode());
            robot.send(MessageType.LOG, 40, "hello from the robot".getBytes());
            robot.send(MessageType.HELLO, 50, FakeRobot.hello(Board.D1_MINI, 1, 1).encode());
            assertThat(robot.receive(2000)).isNotNull();          // every HELLO is answered
            waitFor(() -> frames.size() >= 4);
        }
        assertThat(frames).extracting(SensorFrame::kind).containsExactly("hello", "targets", "vitals", "log");
        SensorFrame.RadarTargets t = (SensorFrame.RadarTargets) frames.get(1);
        assertThat(t.points().get(0)[1]).isLessThan(-800);   // in front of the robot: -Y
        assertThat(link.processor().devices()).singleElement()
                .satisfies(d -> assertThat(d.stats().snapshot().datagrams()).isEqualTo(5));
    }

    @Test
    void theFaceLinkFeedsOnlyRobotsWithAScreen() throws Exception {
        start();
        PresenceState seated = new PresenceState(1_000_000, true, true, new double[] {130.4, -850.6, 160},
                new double[] {130.4, -850.6, 550}, 0.8606, 0, 4, 2, 14.0, 67.254, true, false, 1);
        PresenceQuery presence = new PresenceQuery() {
            @Override
            public PresenceState state() {
                return seated;
            }

            @Override
            public List<PresenceEvent> recentEvents() {
                return List.of();
            }
        };
        RobotLinkService service = new RobotLinkService(Extrinsics.DEFAULT, new SystemHostClock(), List.of(), List.of());
        link = new UdpRobotLink(new RobotLinkProcessor(Extrinsics.DEFAULT), new FrameDispatcher(service::accept));
        link.start("127.0.0.1", 0);
        FaceLinkService face = new FaceLinkService(service, presence, link);
        try (FakeRobot screen = new FakeRobot(link.boundPort()); FakeRobot d1 = new FakeRobot(link.boundPort())) {
            screen.send(MessageType.HELLO, 1, FakeRobot.hello(Board.ESP32_S3_DEVKITC, 6, 2).encode());
            d1.send(MessageType.HELLO, 1, FakeRobot.hello(Board.D1_MINI, 1, 3).encode());
            assertThat(screen.receive(2000)).isNotNull();
            assertThat(d1.receive(2000)).isNotNull();
            waitFor(() -> service.connected().size() == 2);
            assertThat(face.screens()).singleElement().satisfies(d -> assertThat(d.hello().board()).isEqualTo(2));

            face.sendState();
            face.sendState();
            face.onPresenceEvent(new PresenceEvent(EventKind.SAT_DOWN, 1_000_000, "0.86 m away", Map.of()));
            byte[] s1 = screen.receive(2000);
            byte[] s2 = screen.receive(2000);
            byte[] ev = screen.receive(2000);
            assertThat(Wire.header(s1).type()).isEqualTo(MessageType.FACE_STATE.code());
            assertThat(Wire.header(s1).seq()).isEqualTo(0);
            assertThat(Wire.header(s2).seq()).isEqualTo(1);
            assertThat(Wire.payload(s1)).isEqualTo(FaceLinkService.faceState(seated).encode());
            FaceState back = FaceState.decode(Wire.payload(s1));
            assertThat(back.head()).containsExactly(130.0, -851.0, 550.0);
            assertThat(back.heartRate()).isEqualTo(67.25);
            assertThat(Wire.header(ev).type()).isEqualTo(MessageType.FACE_EVENT.code());
            assertThat(Wire.header(ev).seq()).isEqualTo(2);
            assertThat(Wire.payload(ev)).containsExactly(4);
            assertThat(d1.receive(300)).as("no face messages for a D1 mini").isNull();
            assertThat(face.statesSent()).isEqualTo(2);
            assertThat(face.eventsSent()).isEqualTo(1);
        }
    }

    @Test
    void theTapForwardsEachRobotFromASocketOfItsOwn() throws Exception {
        try (DatagramSocket viewer = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            viewer.setSoTimeout(2000);
            start();
            link.tap(new DatagramTap(new InetSocketAddress("127.0.0.1", viewer.getLocalPort())));
            try (FakeRobot a = new FakeRobot(link.boundPort()); FakeRobot b = new FakeRobot(link.boundPort())) {
                a.send(MessageType.HELLO, 1, FakeRobot.hello(Board.D1_MINI, 1, 1).encode());
                byte[] sent = Wire.pack(MessageType.HELLO, 0, 1, FakeRobot.hello(Board.MR60BHA2_KIT, 0, 9).encode());
                b.socket.send(new DatagramPacket(sent, sent.length, InetAddress.getLoopbackAddress(), link.boundPort()));
                byte[] buf = new byte[2048];
                DatagramPacket p1 = new DatagramPacket(buf.clone(), buf.length);
                DatagramPacket p2 = new DatagramPacket(buf.clone(), buf.length);
                viewer.receive(p1);
                viewer.receive(p2);
                assertThat(p1.getPort()).isNotEqualTo(p2.getPort());
                byte[] got1 = java.util.Arrays.copyOf(p1.getData(), p1.getLength());
                byte[] got2 = java.util.Arrays.copyOf(p2.getData(), p2.getLength());
                assertThat(List.of(got1, got2)).anySatisfy(g -> assertThat(g).isEqualTo(sent));
                // junk is not forwarded
                a.socket.send(new DatagramPacket(new byte[] {'X', 'X', 1}, 3, InetAddress.getLoopbackAddress(),
                        link.boundPort()));
                viewer.setSoTimeout(300);
                assertThat(receives(viewer)).isFalse();
            }
        }
    }

    private static boolean receives(DatagramSocket s) {
        try {
            s.receive(new DatagramPacket(new byte[64], 64));
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Test
    void aSlowConsumerLosesOldLidarRevolutionsFirst() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> handled = Collections.synchronizedList(new ArrayList<>());
        FrameDispatcher d = new FrameDispatcher(f -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handled.add(f.kind());
        }, 2000, 0.3, 10);
        d.start();
        RobotLinkProcessor rx = new RobotLinkProcessor(Extrinsics.DEFAULT);
        var from = new marvin.host.domain.robot.Endpoint("192.0.2.9", 47101);
        byte[] hello = Wire.pack(MessageType.HELLO, 0, 0, FakeRobot.hello(Board.SIMULATOR, 1, 7).encode());
        rx.handle(hello, from).frames().forEach(d::submit);
        var dev = rx.device(from);
        for (int i = 0; i < 40; i++) {
            d.submit(new SensorFrame.LidarRevolution(dev, i, new double[0], new float[0], new int[0], 0, 3600));
            d.submit(new SensorFrame.LogLine(dev, i, "x"));
        }
        release.countDown();
        d.stop(3000);
        assertThat(dev.stats().snapshot().shed()).containsKey("scan").doesNotContainKey("log");
        assertThat(handled.stream().filter("log"::equals).count()).isEqualTo(40);
        assertThat(handled.stream().filter("scan"::equals).count()).isLessThanOrEqualTo(11);
    }

    static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ok.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(10);
        }
    }
}
