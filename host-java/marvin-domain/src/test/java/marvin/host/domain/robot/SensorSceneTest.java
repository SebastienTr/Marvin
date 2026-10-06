// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The app's lidar and radar views with several devices: real sensors beat simulated ones. */
class SensorSceneTest {
    private final SensorScene scene = new SensorScene(Extrinsics.DEFAULT);
    private final Device usb = device(Board.USB_BRIDGE, 0, 47131);
    private final Device bench = device(Board.ESP32_DEVKIT, ProtocolV1.FLAG_SIMULATED, 47101);

    private static Device device(int board, int flags, int port) {
        return new Device(new Hello(new byte[] {2, 0, 0, 0, 0, (byte) port}, board, flags, 0, 1, "x"),
                new Endpoint("127.0.0.1", port));
    }

    /** One revolution of {@code n} returns, all at {@code mm}. */
    private static SensorFrame.LidarRevolution scan(Device d, int n, float mm) {
        double[] a = new double[n];
        float[] dist = new float[n];
        int[] inten = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = 360.0 * i / n;
            dist[i] = mm;
            inten[i] = 200;
        }
        return new SensorFrame.LidarRevolution(d, 0, a, dist, inten, n, 3600);
    }

    private static SensorFrame.RadarTargets targets(Device d, double y) {
        return new SensorFrame.RadarTargets(d, 0, List.of(new Ld2450.Target(0, (int) y, 0, 320)),
                List.<double[]>of(new double[] {0, -y, 160}));
    }

    @Test
    void aRealScanIsNotReplacedByASimulatedOneWhileItIsFresh() {
        scene.record(scan(bench, 450, 3000), 0.0);
        assertThat(scene.view(0.0, false).lidar().points()).isEqualTo(450);
        scene.record(scan(usb, 2160, 1000), 0.1);           // real replaces simulated at once
        assertThat(scene.view(0.1, false).lidar().points()).isEqualTo(2160);
        scene.record(scan(bench, 450, 3000), 0.2);           // simulated, while the real one is fresh: not shown
        assertThat(scene.view(0.2, false).lidar().points()).isEqualTo(2160);
        scene.record(scan(usb, 2100, 1000), 1.0);
        assertThat(scene.view(1.0, false).lidar().points()).isEqualTo(2100);
        scene.record(scan(bench, 450, 3000), 3.1);           // the real lidar stopped 2 s ago: the simulated is back
        assertThat(scene.view(3.1, false).lidar().points()).isEqualTo(450);
    }

    @Test
    void realTargetsBeatSimulatedOnes() {
        scene.record(targets(usb, 1200), 0.0);
        scene.record(targets(bench, 2500), 0.1);
        assertThat(scene.view(0.1, false).targets()).singleElement().satisfies(t -> assertThat(t[1]).isEqualTo(120));
        scene.record(targets(bench, 2500), 2.5);
        assertThat(scene.view(2.5, false).targets()).singleElement().satisfies(t -> assertThat(t[1]).isEqualTo(250));
    }

    @Test
    void theRadarsLastFrameHasAnAgeEvenWithoutTargets() {
        assertThat(scene.view(0.0, false).radarAgeS()).isNull();
        scene.record(new SensorFrame.RadarTargets(usb, 0, List.of(), List.of()), 1.0);
        assertThat(scene.view(1.25, false).radarAgeS()).isEqualTo(0.25);
        assertThat(scene.view(1.25, false).targets()).isEmpty();
    }

    @Test
    void theLastHeadIsShownWhileFreshThenDropped() {
        assertThat(scene.view(0.0, false).head()).isNull();
        Head h = new Head(Head.AUTO, -21.46, -24.0, -25.3, 1318, true, false, true);
        scene.record(new SensorFrame.HeadPose(bench, 0, h), 10.0);
        SensorScene.HeadView v = scene.view(10.5, false).head();
        assertThat(v).isNotNull();
        assertThat(v.angleDeg()).isEqualTo(-21.5);
        assertThat(v.goalDeg()).isEqualTo(-24.0);
        assertThat(v.targetDeg()).isEqualTo(-25.3);
        assertThat(v.targetDistanceCm()).isEqualTo(132);
        assertThat(v.mode()).isEqualTo("auto");
        assertThat(v.tracking()).isTrue();
        assertThat(v.servoOn()).isTrue();
        assertThat(v.ageS()).isEqualTo(0.5);
        assertThat(scene.view(12.0, false).head()).as("2 s old").isNotNull();
        assertThat(scene.view(12.1, false).head()).as("stale").isNull();
    }

    @Test
    void aHeadWithoutTargetOrWithAnUnknownModeHasNulls() {
        scene.record(new SensorFrame.HeadPose(bench, 0, new Head(9, 3.0, 0.0, null, null, false, true, false)), 1.0);
        SensorScene.HeadView v = scene.view(1.0, false).head();
        assertThat(v.mode()).isNull();
        assertThat(v.targetDeg()).isNull();
        assertThat(v.targetDistanceCm()).isNull();
        assertThat(v.glancing()).isTrue();
    }
}
