// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.nio.file.Path;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.application.robot.port.in.RobotInbound;
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
        Path file = props.calibrationFile().isEmpty() ? CalibrationFile.defaultPath() : Path.of(props.calibrationFile());
        return CalibrationFile.load(file);
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
