// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import marvin.host.application.vision.port.out.FrameSource;
import marvin.host.domain.vision.event.Frame;

class FfmpegCameraTest {
    static final Path FFMPEG = Path.of("/usr/local/bin/ffmpeg");

    static CameraProperties props(String input) {
        return new CameraProperties("", "", "1280x720", "30", 15, input, 81);
    }

    /** Answers each run with the next result, and keeps the commands. */
    static final class Script implements ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<Result> results;

        Script(Result... results) {
            this.results = new ArrayList<>(List.of(results));
        }

        @Override
        public Result run(List<String> command, double timeoutS) {
            commands.add(command);
            return results.remove(0);
        }
    }

    @Test
    void onAMacItAsksAvFoundationForOneSettledPictureAndNoSound() {
        Script runner = new Script(new ProcessRunner.Result(0, Pictures.jpeg(1280, 720), "", false));
        FfmpegCamera cam = new FfmpegCamera("darwin", FFMPEG, props(""), runner);
        assertThat(cam.availability().available()).isTrue();
        assertThat(cam.availability().detail()).isEqualTo("This computer's camera (the built-in one)");
        Frame f = cam.capture(10);
        assertThat(f.width()).isEqualTo(1280);
        assertThat(f.height()).isEqualTo(720);
        assertThat(f.source()).isEqualTo("computer");
        assertThat(runner.commands.get(0)).containsExactly("/usr/local/bin/ffmpeg", "-hide_banner", "-nostdin", "-loglevel",
                "error", "-f", "avfoundation", "-framerate", "30", "-video_size", "1280x720", "-i", "0:none",
                "-vf", "select=gte(n\\,15)", "-frames:v", "1", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "3", "-");
    }

    @Test
    void aCameraWithoutThatModeChoosesItsOwnAndALargerPictureIsMadeSmaller() {
        Script runner = new Script(
                new ProcessRunner.Result(1, new byte[0], "[avfoundation] Selected video size (1280x720) is not supported by "
                        + "the device.\n0:none: Input/output error", false),
                new ProcessRunner.Result(0, Pictures.jpeg(1920, 1080), "", false));
        Frame f = new FfmpegCamera("darwin", FFMPEG, props(""), runner).capture(10);
        assertThat(runner.commands.get(1)).doesNotContain("-video_size", "-framerate");
        assertThat(f.width()).isEqualTo(1280);
        assertThat(f.height()).isEqualTo(720);
    }

    @Test
    void whatWentWrongIsSaidWithWhatToDo() {
        FfmpegCamera denied = new FfmpegCamera("darwin", FFMPEG, props(""), new Script(new ProcessRunner.Result(1, new byte[0],
                "[avfoundation @ 0x1] Failed to create AV capture input device: Cannot use FaceTime HD Camera\n"
                        + "0:none: Input/output error", false)));
        assertThatThrownBy(() -> denied.capture(10)).isInstanceOfSatisfying(FrameSource.CaptureFailed.class, e -> {
            assertThat(e.getMessage()).isEqualTo("macOS does not let Marvin use the camera");
            assertThat(e.fix()).contains("Privacy & Security > Camera");
        });
        FfmpegCamera waiting = new FfmpegCamera("darwin", FFMPEG, props(""), new Script(new ProcessRunner.Result(-1, new byte[0], "", true)));
        assertThatThrownBy(() -> waiting.capture(10)).isInstanceOfSatisfying(FrameSource.CaptureFailed.class, e -> {
            assertThat(e.getMessage()).isEqualTo("The camera gave no picture in 10 s");
            assertThat(e.fix()).contains("macOS asks");
        });
        FfmpegCamera none = new FfmpegCamera("linux", FFMPEG, props("-f lavfi -i nothing"),
                new Script(new ProcessRunner.Result(1, new byte[0], "/dev/video0: No such file or directory", false)));
        assertThatThrownBy(() -> none.capture(10)).hasMessageStartingWith("No camera was found");
        FfmpegCamera empty = new FfmpegCamera("linux", FFMPEG, props("-f lavfi -i x"), new Script(new ProcessRunner.Result(0, "?".getBytes(), "", false)));
        assertThatThrownBy(() -> empty.capture(10)).hasMessage("ffmpeg could not take a picture (no picture)");
    }

    @Test
    void withoutFfmpegOrOnAnotherSystemThereIsNoComputerCamera() {
        FfmpegCamera missing = new FfmpegCamera("darwin", null, props(""), new Script());
        assertThat(missing.availability().available()).isFalse();
        assertThat(missing.availability().fix()).isEqualTo("brew install ffmpeg, then ./marvin restart");
        assertThatThrownBy(() -> missing.capture(10)).hasMessageContaining("ffmpeg is not installed");
        assertThat(new FfmpegCamera("win32", FFMPEG, props(""), new Script()).availability().available()).isFalse();
        assertThat(new FfmpegCamera("linux", FFMPEG, new CameraProperties("", "/dev/no-such-video", "1280x720", "30", 15, "", 81),
                new Script()).availability().detail()).isEqualTo("No camera at /dev/no-such-video");
    }

    @Test
    void ffmpegIsFoundInThePathOrWhereHomebrewPutsIt(@TempDir Path dir) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path ff = fakeFfmpeg(bin, "exit 0");
        assertThat(FfmpegCamera.find("", bin + java.io.File.pathSeparator + "/nowhere")).contains(ff);
        assertThat(FfmpegCamera.find(ff.toString(), "")).contains(ff);
        assertThat(FfmpegCamera.find(dir.resolve("nope").toString(), bin.toString())).isEqualTo(Optional.empty());
    }

    @Test
    void aFakeFfmpegProcessGivesItsPictureThroughTheRealRunner(@TempDir Path dir) throws IOException {
        Path picture = dir.resolve("p.jpg");
        Files.write(picture, Pictures.jpeg(1280, 720));
        Path ff = fakeFfmpeg(dir, "cat '" + picture + "'");
        Frame f = new FfmpegCamera("darwin", ff, props(""), ProcessRunner.SYSTEM).capture(10);
        assertThat(f.width()).isEqualTo(1280);
        assertThat(f.seconds()).isPositive();
        Path slow = fakeFfmpeg(Files.createDirectories(dir.resolve("slow")), "sleep 30");
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> new FfmpegCamera("darwin", slow, props(""), ProcessRunner.SYSTEM).capture(1))
                .hasMessage("The camera gave no picture in 1 s");
        assertThat((System.nanoTime() - t0) / 1e9).as("killed, not waited for").isLessThan(5);
    }

    /** With a real ffmpeg (CI, a developer's machine): its test pattern as the camera. */
    @Test
    void aRealFfmpegWithItsTestPattern() {
        Optional<Path> ff = FfmpegCamera.find("", System.getenv("PATH"));
        assumeTrue(ff.isPresent(), "no ffmpeg here");
        FfmpegCamera cam = new FfmpegCamera("linux", ff.get(), props("-f lavfi -i testsrc2=size=1920x1080:rate=30"),
                ProcessRunner.SYSTEM);
        Frame f = cam.capture(20);
        assertThat(f.width()).isEqualTo(1280);
        assertThat(f.height()).isEqualTo(720);
        assertThat(cam.availability().detail()).startsWith("This computer's camera (ffmpeg input -f lavfi");
    }

    static Path fakeFfmpeg(Path dir, String body) throws IOException {
        Path p = dir.resolve("ffmpeg");
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }
}
