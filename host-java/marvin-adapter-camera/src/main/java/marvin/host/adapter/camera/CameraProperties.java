// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code marvin.camera.*}: how the cameras are opened.
 *
 * @param ffmpeg       the ffmpeg to run ({@code $MARVIN_FFMPEG}; empty: the {@code PATH}, then Homebrew's places)
 * @param device       the computer's camera: avfoundation's index or name on macOS ({@code 0}: the built-in one), a
 *                     V4L2 device on Linux; empty: the platform's default
 * @param videoSize    the size asked of the computer's camera (another one is used when it does not have it)
 * @param framerate    the frame rate asked of it (macOS cameras refuse a rate they do not have)
 * @param warmupFrames frames dropped before the picture, while the camera sets its exposure (15: half a second)
 * @param input        ffmpeg's input arguments instead of the platform's camera, for a test without a camera
 *                     ({@code -f lavfi -i testsrc2=size=1280x720:rate=30})
 * @param robotPort    the robot's camera port ({@code HELLO} flag camera: MJPEG on TCP 81)
 */
@ConfigurationProperties("marvin.camera")
public record CameraProperties(String ffmpeg, String device, @DefaultValue("1280x720") String videoSize,
                               @DefaultValue("30") String framerate, @DefaultValue("15") int warmupFrames,
                               String input, @DefaultValue("81") int robotPort) {

    public CameraProperties {
        ffmpeg = ffmpeg == null ? "" : ffmpeg.strip();
        device = device == null ? "" : device.strip();
        input = input == null ? "" : input.strip();
        warmupFrames = Math.max(0, warmupFrames);
    }
}
