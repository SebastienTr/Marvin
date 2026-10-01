// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.adapter.camera.CameraProperties;
import marvin.host.adapter.camera.FfmpegCamera;
import marvin.host.adapter.camera.RobotCamera;
import marvin.host.adapter.persistence.VoiceSettingsFile;
import marvin.host.adapter.web.EventHub;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.system.port.in.HostLog;
import marvin.host.application.vision.CameraService;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.shared.Clocks;

/**
 * Marvin's eyes (docs/vision.md): the computer's camera through ffmpeg, the robot's over HTTP, and the vision context
 * that picks one with the {@code camera} voice setting and takes a picture when asked. The app is told of each picture.
 */
@Configuration(proxyBeanMethods = false)
public class CameraWiring {

    @Bean
    public FfmpegCamera computerCamera(CameraProperties props) {
        return FfmpegCamera.of(VoiceWiring.platform(), props);
    }

    @Bean
    public RobotCamera robotCamera(RobotLinkQuery robot, CameraProperties props) {
        return new RobotCamera(robot::devices, props.robotPort());
    }

    @Bean
    public CameraService cameraService(FfmpegCamera computer, RobotCamera robot, VoiceSettingsFile settings, Clocks clocks,
                                       EventHub hub, HostLog hostLog) {
        CameraService camera = new CameraService(List.of(computer, robot),
                () -> VoiceSettings.config(settings.load()).camera(), clocks);
        camera.addListener(hub);                            // the app shows each picture taken (never the picture)
        camera.addListener(p -> hostLog.add("voice", "info", logLine(p), Map.of()));
        return camera;
    }

    /** The Log panel's line for a picture taken: which camera, why, its size. */
    static String logLine(Map<String, Object> p) {
        String camera = "robot".equals(p.get("source")) ? "the robot's camera" : "this computer's camera";
        String why = "look".equals(p.get("purpose")) ? "Marvin looked" : "Camera test";
        return why + ": one picture with " + camera + " (" + p.get("width") + " × " + p.get("height") + ", not kept)";
    }
}
