// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The receiving rules of the Python receiver, one by one. */
class RobotLinkProcessorTest {
    private final RobotLinkProcessor rx = new RobotLinkProcessor(Extrinsics.DEFAULT);
    private final Endpoint from = new Endpoint("10.0.0.2", 47101);
    private final Hello hello = new Hello(new byte[] {2, 0x4d, 0x56, 0x53, 0x49, 0x4d}, 255, 1, -40, 1, "sim");

    private RobotLinkProcessor.Reception send(MessageType t, long seq, long tUs, byte[] payload) {
        return rx.handle(Wire.pack(t, seq, tUs, payload), from);
    }

    private static byte[] lidar(double... starts) {
        byte[] out = new byte[1 + Ldrobot.SIZE * starts.length];
        out[0] = 1;
        int[] d = new int[12];
        java.util.Arrays.fill(d, 1000);
        for (int i = 0; i < starts.length; i++) {
            byte[] p = Ldrobot.build(3600, starts[i], starts[i] + 11, d, new int[12], 0);
            System.arraycopy(p, 0, out, 1 + i * Ldrobot.SIZE, Ldrobot.SIZE);
        }
        return out;
    }

    @Test
    void ignoresEverythingBeforeHelloAndAnswersEveryHello() {
        assertThat(send(MessageType.LOG, 0, 0, "early".getBytes()).device()).isNull();
        RobotLinkProcessor.Reception r = send(MessageType.HELLO, 1, 0, hello.encode());
        assertThat(r.acknowledge()).isTrue();
        assertThat(r.frames()).singleElement().isInstanceOf(SensorFrame.Connected.class);
        RobotLinkProcessor.Reception again = send(MessageType.HELLO, 2, 0, hello.encode());
        assertThat(again.acknowledge()).isTrue();
        assertThat(again.frames()).isEmpty();
        assertThat(send(MessageType.HELLO, 3, 0, new byte[3]).acknowledge()).isFalse();   // unreadable HELLO
        assertThat(rx.device(from).stats().snapshot().datagrams()).isEqualTo(3);
        assertThat(rx.handle(new byte[] {'X', 'X'}, from).error()).isEqualTo("datagram shorter than the header");
    }

    @Test
    void countsSequenceGapsButNotRestarts() {
        send(MessageType.HELLO, 100, 0, hello.encode());
        send(MessageType.LOG, 103, 0, new byte[0]);          // 2 lost
        send(MessageType.LOG, 5, 0, new byte[0]);            // restarted: a huge gap, not counted
        send(MessageType.LOG, 0xFFFFFFFFL, 0, new byte[0]);
        send(MessageType.LOG, 1, 0, new byte[0]);            // wrapped: 1 lost (0)
        assertThat(rx.device(from).stats().lost()).isEqualTo(3);
    }

    @Test
    void closesARevolutionWhenTheStartAngleGoesBack() {
        send(MessageType.HELLO, 0, 0, hello.encode());
        assertThat(send(MessageType.LIDAR, 1, 1000, lidar(300, 312, 324)).frames()).isEmpty();
        List<SensorFrame> f = send(MessageType.LIDAR, 2, 2000, lidar(336, 348, 0, 12)).frames();
        assertThat(f).singleElement().satisfies(x -> {
            SensorFrame.LidarRevolution r = (SensorFrame.LidarRevolution) x;
            assertThat(r.tUs()).isEqualTo(2000);
            assertThat(r.count()).isEqualTo(5 * 12);
            assertThat(r.points(Extrinsics.DEFAULT)).hasSize(5 * 12 * 3);
        });
    }

    @Test
    void badFramesAreCounted() {
        send(MessageType.HELLO, 0, 0, hello.encode());
        byte[] l = lidar(10, 20);
        l[1 + Ldrobot.SIZE + 5] ^= 1;
        send(MessageType.LIDAR, 1, 0, l);
        send(MessageType.LD2450, 2, 0, new byte[30]);
        send(MessageType.VITALS, 3, 0, new byte[4]);
        send(MessageType.AUDIO_IN, 4, 0, new byte[5]);
        LinkStats.Snapshot s = rx.device(from).stats().snapshot();
        assertThat(s.crcErrors()).isEqualTo(1);
        assertThat(s.lidarPackets()).isEqualTo(1);
        assertThat(s.bad()).isEqualTo(3);
    }

    @Test
    void lidarPointsAreInTheDeviceFrame() {
        float[] p = Extrinsics.DEFAULT.lidarToDevice(new double[] {0, 90, 45}, new float[] {1000, 2000, 10}, 3);
        assertThat(p).hasSize(6);
        assertThat(p[0]).isCloseTo(0f, org.assertj.core.api.Assertions.within(1e-3f));
        assertThat(p[1]).isEqualTo(-1000f);        // straight ahead is -Y
        assertThat(p[2]).isEqualTo(133f);
        assertThat(p[3]).isEqualTo(-2000f);        // the robot's right is -X
        double[] back = Extrinsics.DEFAULT.deviceToLidar(p[3], p[4]);
        assertThat(back[0]).isCloseTo(90, org.assertj.core.api.Assertions.within(1e-3));
    }
}
