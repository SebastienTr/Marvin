// SPDX-License-Identifier: MIT
package marvin.host.application.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import marvin.host.application.vision.port.in.Camera;
import marvin.host.application.vision.port.out.FrameSource;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

class CameraServiceTest {

    /** A camera that says what it is told, and counts its pictures. */
    static class FakeSource implements FrameSource {
        final String name;
        volatile boolean available = true;
        volatile RuntimeException failure;
        final AtomicInteger pictures = new AtomicInteger();
        final AtomicInteger open = new AtomicInteger();
        volatile int most;

        FakeSource(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CameraChoice.Source availability() {
            return new CameraChoice.Source(name, available, available ? "the " + name + " camera" : "no " + name + " camera",
                    available ? "" : "plug it in");
        }

        @Override
        public Frame capture(double timeoutS) {
            int now = open.incrementAndGet();
            most = Math.max(most, now);
            try {
                Thread.sleep(20);
                if (failure != null) {
                    throw failure;
                }
                pictures.incrementAndGet();
                return new Frame(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}, 640, 480, name,
                        "the " + name + " camera", 0);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            } finally {
                open.decrementAndGet();
            }
        }
    }

    static final Clocks CLOCK = new Clocks() {
        double mono = 10;

        @Override
        public double wallSeconds() {
            return 1_790_000_000.0;
        }

        @Override
        public double monotonicSeconds() {
            return mono += 0.25;
        }
    };

    final FakeSource computer = new FakeSource("computer");
    final FakeSource robot = new FakeSource("robot");
    volatile String setting = "auto";
    final CameraService camera = new CameraService(List.of(computer, robot), () -> setting, CLOCK);

    @Test
    void theSettingPicksTheCameraAndAPictureIsToldButNotKept() {
        List<Map<String, Object>> told = new CopyOnWriteArrayList<>();
        camera.addListener(told::add);
        robot.available = false;
        assertThat(camera.status().choice().source()).isEqualTo("computer");
        assertThat(camera.status().sources()).extracting(CameraChoice.Source::name).containsExactly("computer", "robot");
        assertThat(camera.status().last()).isNull();
        Frame f = camera.take(Camera.LOOK);
        assertThat(f.source()).isEqualTo("computer");
        assertThat(f.seconds()).as("timed by the service when the camera does not say").isPositive();
        assertThat(told).hasSize(1);
        assertThat(told.get(0)).containsEntry("source", "computer").containsEntry("purpose", "look")
                .containsEntry("width", 640).containsEntry("t", 1_790_000_000.0).doesNotContainKey("jpeg");
        assertThat(camera.status().last()).isEqualTo(told.get(0));

        robot.available = true;                                 // a robot with a camera connected
        assertThat(camera.take(Camera.TEST).source()).isEqualTo("robot");
        setting = "computer";
        assertThat(camera.take(Camera.TEST).source()).isEqualTo("computer");
        assertThat(computer.pictures.get()).isEqualTo(2);
        assertThat(robot.pictures.get()).isEqualTo(1);
    }

    @Test
    void noCameraNoPictureWithWhyAndWhatToDo() {
        setting = "off";
        assertThat(camera.available()).isFalse();
        assertThatThrownBy(() -> camera.take(Camera.LOOK)).isInstanceOfSatisfying(Camera.NoPicture.class, e -> {
            assertThat(e.unavailable()).isTrue();
            assertThat(e.getMessage()).isEqualTo("The camera is off");
            assertThat(e.fix()).contains("Marvin > Voice & models > Camera");
        });
        setting = "robot";
        robot.available = false;
        assertThatThrownBy(() -> camera.take(Camera.LOOK)).hasMessage("no robot camera");
        setting = "auto";
        computer.failure = new FrameSource.CaptureFailed("macOS does not let Marvin use the camera", "allow it");
        assertThatThrownBy(() -> camera.take(Camera.LOOK)).isInstanceOfSatisfying(Camera.NoPicture.class, e -> {
            assertThat(e.unavailable()).isFalse();
            assertThat(e.fix()).isEqualTo("allow it");
        });
        assertThat(camera.status().last()).as("a failed picture is no picture").isNull();
        assertThat(computer.pictures.get()).isZero();
    }

    @Test
    void onePictureAtATime() throws InterruptedException {
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofVirtual().start(() -> camera.take(Camera.LOOK)));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(robot.pictures.get()).isEqualTo(4);
        assertThat(robot.most).as("never two at once").isEqualTo(1);
    }

    @Test
    void aBrokenSettingOrCameraIsNoCameraNotACrash() {
        CameraService broken = new CameraService(List.of(new FakeSource("computer") {
            @Override
            public CameraChoice.Source availability() {
                throw new IllegalStateException("boom");
            }
        }), () -> {
            throw new IllegalStateException("no file");
        }, CLOCK);
        assertThat(broken.status().choice().setting()).isEqualTo("auto");
        assertThat(broken.available()).isFalse();
        assertThat(broken.status().sources().get(0).detail()).contains("boom");
    }
}
