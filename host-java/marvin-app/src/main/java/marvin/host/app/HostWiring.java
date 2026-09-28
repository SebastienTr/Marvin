// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import marvin.host.adapter.robot.RobotLinkProperties;
import marvin.host.adapter.robot.RobotLinkRunner;
import marvin.host.adapter.robot.SystemHostClock;
import marvin.host.adapter.robot.UdpRobotLink;
import marvin.host.application.presence.PresenceService;
import marvin.host.application.robot.FaceLinkService;
import marvin.host.application.robot.RobotLinkService;
import marvin.host.application.system.HealthService;
import marvin.host.application.system.port.in.ReportHealth;
import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.domain.presence.BrainConfig;
import marvin.host.domain.robot.DeviceMonitor;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.SensorCalibration;
import marvin.host.domain.system.RunMode;

/**
 * Wires the plain-Java use cases to their adapters. The application layer knows nothing of Spring:
 * every use case is built here.
 */
@Configuration(proxyBeanMethods = false)
public class HostWiring {
    private static final Logger log = LoggerFactory.getLogger("marvin.host.robot");

    static String version(ObjectProvider<BuildProperties> build) {
        BuildProperties b = build.getIfAvailable();
        return b != null ? b.getVersion() : "dev";
    }

    @Bean
    public ReportHealth reportHealth(Environment env, ObjectProvider<BuildProperties> build,
                                     List<ComponentProbe> probes) {
        RunMode mode = RunMode.valueOf(env.getProperty("marvin.mode", "live").toUpperCase(Locale.ROOT));
        return new HealthService(version(build), mode, probes);
    }

    // ------------------------------------------------------------------ presence

    @Bean
    public PresenceEventBus presenceEventBus() {
        return new PresenceEventBus();
    }

    @Bean
    public PresenceService presenceService(SensorCalibration calibration, PresenceEventBus bus) {
        return new PresenceService(BrainConfig.DEFAULT.withRadarSpeedSign(calibration.radarSpeedSign()), bus);
    }

    // ------------------------------------------------------------------ robot

    @Bean
    public RobotLinkService robotLinkService(Extrinsics extrinsics, SystemHostClock clock, PresenceService presence) {
        return new RobotLinkService(extrinsics, clock, List.of(new PresenceFeed(presence)),
                List.of(HostWiring::logNotice));
    }

    @Bean
    public FaceLinkService faceLinkService(RobotLinkService link, PresenceService presence, UdpRobotLink udp,
                                           PresenceEventBus bus) {
        FaceLinkService face = new FaceLinkService(link, presence, udp);
        bus.subscribe(face::onPresenceEvent);
        return face;
    }

    @Bean
    public RobotLinkRunner robotLinkRunner(UdpRobotLink udp, RobotLinkProperties props, RobotLinkService link,
                                           FaceLinkService face, ObjectProvider<BuildProperties> build) {
        return new RobotLinkRunner(udp, props, link, face, version(build));
    }

    @Bean
    public RobotLinkProbe robotLinkProbe(UdpRobotLink udp, RobotLinkService link, RobotLinkProperties props) {
        return new RobotLinkProbe(udp, link, props);
    }

    private static void logNotice(DeviceMonitor.Notice n) {
        switch (n) {
            case DeviceMonitor.Notice.Connected c -> log.info("{} connected ({})", c.device().name(), c.device().role().label());
            case DeviceMonitor.Notice.Disconnected d -> log.info("{} went offline", d.device().name());
            case DeviceMonitor.Notice.Reconnected r -> log.info("{} is back online", r.device().name());
            case DeviceMonitor.Notice.Log l -> log.info("[{}] {}", l.device(), l.text());
        }
    }
}
