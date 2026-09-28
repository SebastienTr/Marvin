// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code marvin.robot.*}: the robot link.
 *
 * @param enabled         listen for robots at all
 * @param bind            the address the UDP socket binds to
 * @param port            the UDP port (protocol v1: 47100); 0 picks a free one
 * @param tap             forward every datagram to this {@code host:port} (or port on 127.0.0.1) for the
 *                        Python viewer; empty: no tap
 * @param record          also record every datagram to this {@code .mvrec} file ({@code .gz}: compressed);
 *                        empty: no recording
 * @param replay          play this recording instead of listening on UDP; empty: live
 * @param replaySpeed     1 = real time; 0 = as fast as possible
 * @param replayLoop      start the replay over at the end
 * @param faceStateHz     {@code FACE_STATE} rate to robots with a screen
 * @param calibrationFile the calibration file; empty: the default place (as the Python host)
 */
@ConfigurationProperties("marvin.robot")
public record RobotLinkProperties(Boolean enabled, String bind, Integer port, String tap, String record, String replay,
                                  Double replaySpeed, Boolean replayLoop, Double faceStateHz, String calibrationFile) {

    public RobotLinkProperties {
        enabled = enabled == null || enabled;
        bind = bind == null || bind.isBlank() ? "0.0.0.0" : bind;
        port = port == null ? 47100 : port;
        tap = tap == null ? "" : tap.trim();
        record = record == null ? "" : record.trim();
        replay = replay == null ? "" : replay.trim();
        replaySpeed = replaySpeed == null ? 1.0 : replaySpeed;
        replayLoop = replayLoop == null || replayLoop;
        faceStateHz = faceStateHz == null || faceStateHz <= 0 ? 10.0 : faceStateHz;
        calibrationFile = calibrationFile == null ? "" : calibrationFile.trim();
    }
}
