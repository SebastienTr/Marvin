// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.nio.file.Path;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.application.robot.port.in.RobotInbound;
import marvin.host.domain.presence.BrainConfig;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.RobotLinkProcessor;
import marvin.host.domain.robot.SensorCalibration;

/**
 * The robot adapter's beans. The use cases it drives ({@link RobotInbound} and friends) are built by
 * the application wiring; the {@link RobotLinkRunner} is declared there too, once they exist.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RobotLinkProperties.class)
public class RobotAdapterConfiguration {

    @Bean
    public SensorCalibration sensorCalibration(RobotLinkProperties props) {
        return CalibrationFile.load(calibrationFile(props));
    }

    /**
     * The brain's thresholds: the host's defaults, the calibrated LD2450 speed sign, and the {@code brain} block of
     * {@code calibration.json} (docs/voice.md "Presence tuning").
     */
    @Bean
    public BrainConfig brainConfig(RobotLinkProperties props, SensorCalibration calibration) {
        return CalibrationFile.brain(calibrationFile(props), BrainConfig.DEFAULT.withRadarSpeedSign(calibration.radarSpeedSign()));
    }

    private static Path calibrationFile(RobotLinkProperties props) {
        return props.calibrationFile().isEmpty() ? CalibrationFile.defaultPath() : Path.of(props.calibrationFile());
    }

    @Bean
    public Extrinsics extrinsics(SensorCalibration calibration) {
        return Extrinsics.DEFAULT.calibrated(calibration);
    }

    @Bean
    public SystemHostClock hostClock() {
        return new SystemHostClock();
    }

    @Bean
    public UdpRobotLink udpRobotLink(Extrinsics extrinsics, RobotInbound inbound) {
        return new UdpRobotLink(new RobotLinkProcessor(extrinsics), new FrameDispatcher(inbound::accept));
    }
}
