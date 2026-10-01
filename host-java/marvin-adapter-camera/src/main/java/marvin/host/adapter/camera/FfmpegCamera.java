// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

import marvin.host.application.vision.port.out.FrameSource;
import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

/**
 * The computer's camera (a MacBook's built-in one, a USB webcam on Linux), through an {@code ffmpeg} process started
 * for one picture: it opens the camera (macOS AVFoundation, Linux V4L2), drops the first frames while the exposure
 * settles, writes one JPEG to its standard output and quits, so the camera is on (and its light lit) for about a second.
 *
 * <p>macOS asks once whether the app that started Marvin (Terminal, iTerm, the IDE) may use the camera; until it is
 * allowed (System Settings, Privacy &amp; Security, Camera), ffmpeg gets no picture and the error says so.
 */
public final class FfmpegCamera implements FrameSource {
    private static final Logger log = Logger.getLogger("marvin.camera");
    /** Where Homebrew puts it, for a host started without the shell's {@code PATH}. */
    static final List<String> USUAL = List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin");

    private final String platform;
    private final Path ffmpeg;
    private final CameraProperties props;
    private final ProcessRunner runner;

    /**
     * @param platform {@code darwin}, {@code linux} ... (Python's {@code sys.platform})
     * @param ffmpeg   the ffmpeg found ({@code null}: none)
     */
    FfmpegCamera(String platform, Path ffmpeg, CameraProperties props, ProcessRunner runner) {
        this.platform = platform;
        this.ffmpeg = ffmpeg;
        this.props = props;
        this.runner = runner;
    }

    /** The computer's camera with the ffmpeg of the settings or of the {@code PATH}. */
    public static FfmpegCamera of(String platform, CameraProperties props) {
        Path found = find(props.ffmpeg(), System.getenv("PATH")).orElse(null);
        if (found == null) {
            log.info("no ffmpeg: Marvin cannot take pictures with this computer's camera (" + install(platform) + ")");
        }
        return new FfmpegCamera(platform, found, props, ProcessRunner.SYSTEM);
    }

    /** {@code configured} when it is set, else {@code ffmpeg} in the {@code PATH}, then where Homebrew puts it. */
    static Optional<Path> find(String configured, String pathEnv) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.strip());
            return Files.isExecutable(p) ? Optional.of(p) : Optional.empty();
        }
        List<String> dirs = new ArrayList<>();
        if (pathEnv != null) {
            dirs.addAll(Arrays.asList(pathEnv.split(File.pathSeparator)));
        }
        dirs.addAll(USUAL);
        for (String d : dirs) {
            if (d.isBlank()) {
                continue;
            }
            Path p = Path.of(d, "ffmpeg");
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    static String install(String platform) {
        return "darwin".equals(platform) ? "brew install ffmpeg" : "linux".equals(platform) ? "sudo apt install ffmpeg"
                : "install ffmpeg";
    }

    @Override
    public String name() {
        return CameraChoice.COMPUTER;
    }

    private boolean supported() {
        return !props.input().isEmpty() || "darwin".equals(platform) || "linux".equals(platform);
    }

    private String device() {
        if (!props.device().isEmpty()) {
            return props.device();
        }
        return "linux".equals(platform) ? "/dev/video0" : "0";
    }

    private String label() {
        if (!props.input().isEmpty()) {
            return "This computer's camera (ffmpeg input " + props.input() + ")";
        }
        return "darwin".equals(platform) ? "This computer's camera (" + ("0".equals(device()) ? "the built-in one" : device()) + ")"
                : "This computer's camera (" + device() + ")";
    }

    @Override
    public CameraChoice.Source availability() {
        if (!supported()) {
            return new CameraChoice.Source(name(), false, "Pictures from this computer's camera need macOS or Linux", "");
        }
        if (ffmpeg == null) {
            return new CameraChoice.Source(name(), false, "ffmpeg is not installed: Marvin cannot open this computer's camera",
                    install(platform) + ", then ./marvin restart");
        }
        if ("linux".equals(platform) && props.input().isEmpty() && device().startsWith("/dev/") && !Files.exists(Path.of(device()))) {
            return new CameraChoice.Source(name(), false, "No camera at " + device(), "Plug in a webcam, or set MARVIN_CAMERA_DEVICE.");
        }
        return new CameraChoice.Source(name(), true, label(), "");
    }

    /** The ffmpeg command for one picture; {@code sized}: ask for the configured size and frame rate. */
    List<String> command(boolean sized) {
        List<String> c = new ArrayList<>(List.of(ffmpeg == null ? "ffmpeg" : ffmpeg.toString(), "-hide_banner", "-nostdin",
                "-loglevel", "error"));
        if (!props.input().isEmpty()) {
            c.addAll(Arrays.asList(props.input().split("\\s+")));
        } else if ("darwin".equals(platform)) {
            c.addAll(List.of("-f", "avfoundation"));
            if (sized) {
                c.addAll(List.of("-framerate", props.framerate(), "-video_size", props.videoSize()));
            }
            c.addAll(List.of("-i", device() + ":none"));
        } else {
            c.addAll(List.of("-f", "v4l2"));
            if (sized) {
                c.addAll(List.of("-video_size", props.videoSize()));
            }
            c.addAll(List.of("-i", device()));
        }
        if (props.warmupFrames() > 0) {
            // the first frames of a camera just opened are dark: its exposure settles in about half a second
            c.addAll(List.of("-vf", "select=gte(n\\," + props.warmupFrames() + ")"));
        }
        c.addAll(List.of("-frames:v", "1", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "3", "-"));
        return c;
    }

    @Override
    public Frame capture(double timeoutS) {
        CameraChoice.Source a = availability();
        if (!a.available()) {
            throw new CaptureFailed(a.detail(), a.fix());
        }
        long t0 = System.nanoTime();
        ProcessRunner.Result r = run(command(true), timeoutS);
        if (r.exit() != 0 && !r.timedOut() && unsupportedMode(r.stderr())) {
            // the camera has no 1280x720 at 30 fps: let it pick its own mode, the picture is made smaller below
            log.info("the camera refused " + props.videoSize() + " at " + props.framerate() + " fps: using its own mode");
            double left = timeoutS - (System.nanoTime() - t0) / 1e9;
            r = run(command(false), Math.max(2.0, left));
        }
        if (r.timedOut()) {
            throw new CaptureFailed("The camera gave no picture in " + Math.round(timeoutS) + " s",
                    "darwin".equals(platform) ? "The first time, macOS asks whether the app that runs ./marvin may use the "
                            + "camera: allow it, then try again." : "Is another program using the camera?");
        }
        byte[] jpeg = Jpegs.first(r.stdout());
        if (r.exit() != 0 || jpeg == null) {
            throw failure(r);
        }
        Jpegs.Sized s;
        try {
            s = Jpegs.fit(jpeg);
        } catch (Jpegs.NotAJpeg e) {
            throw new CaptureFailed("ffmpeg gave no picture (" + e.getMessage() + ")", "");
        }
        return new Frame(s.jpeg(), s.width(), s.height(), name(), label(), (System.nanoTime() - t0) / 1e9);
    }

    private ProcessRunner.Result run(List<String> command, double timeoutS) {
        try {
            return runner.run(command, timeoutS);
        } catch (IOException e) {
            throw new CaptureFailed("ffmpeg could not be started: " + e.getMessage(), install(platform));
        }
    }

    static boolean unsupportedMode(String stderr) {
        String s = stderr.toLowerCase(Locale.ROOT);
        return s.contains("is not supported by the device") || s.contains("selected framerate")
                || s.contains("selected video size");
    }

    /** What went wrong, in words, from ffmpeg's last lines. */
    CaptureFailed failure(ProcessRunner.Result r) {
        String err = r.stderr().strip();
        String low = err.toLowerCase(Locale.ROOT);
        if ("darwin".equals(platform) && (low.contains("authoriz") || low.contains("permission") || low.contains("not permitted")
                || low.contains("cannot use"))) {
            return new CaptureFailed("macOS does not let Marvin use the camera",
                    "System Settings > Privacy & Security > Camera: allow the app that runs ./marvin (Terminal, iTerm, "
                            + "your IDE), then ./marvin restart.");
        }
        if (low.contains("no such file") || low.contains("no such device") || low.contains("video device not found")
                || low.contains("could not find video device") || low.contains("input/output error") && !low.contains("supported")) {
            return new CaptureFailed("No camera was found (" + device() + ")",
                    "darwin".equals(platform) ? "List the cameras: ffmpeg -f avfoundation -list_devices true -i \"\"; "
                            + "then set MARVIN_CAMERA_DEVICE to the one to use." : "Plug in a webcam, or set MARVIN_CAMERA_DEVICE.");
        }
        if (low.contains("device or resource busy")) {
            return new CaptureFailed("The camera is busy: another program is using it", "Close it and try again.");
        }
        String last = err.isEmpty() ? "no picture" : err.lines().reduce((a, b) -> b).orElse(err);
        return new CaptureFailed("ffmpeg could not take a picture (" + (r.exit() == 0 ? "no picture" : last) + ")", "");
    }
}
