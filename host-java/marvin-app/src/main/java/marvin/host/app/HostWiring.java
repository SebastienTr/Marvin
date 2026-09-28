// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import marvin.host.adapter.persistence.SqliteImporter;
import marvin.host.adapter.robot.RobotLinkProperties;
import marvin.host.adapter.robot.RobotLinkRunner;
import marvin.host.adapter.robot.SystemHostClock;
import marvin.host.adapter.robot.UdpRobotLink;
import marvin.host.adapter.sidecar.PythonRuntime;
import marvin.host.adapter.sidecar.SidecarProperties;
import marvin.host.adapter.web.EventHub;
import marvin.host.adapter.web.Views;
import marvin.host.application.conversation.ConversationService;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.face.FaceService;
import marvin.host.application.presence.HistoryWriter;
import marvin.host.application.presence.PresenceHistoryService;
import marvin.host.application.presence.port.out.PresenceHistoryStore;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.settings.SettingsService;
import marvin.host.application.settings.port.out.SettingsStore;
import marvin.host.application.system.HostLogService;
import marvin.host.application.system.port.in.HostLog;
import marvin.host.domain.settings.AppSettings;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.system.ComponentHealth;
import marvin.host.domain.shared.LocalDays;
import marvin.host.application.presence.PresenceService;
import marvin.host.application.robot.FaceLinkService;
import marvin.host.application.robot.RobotAudioService;
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

    @Bean
    public PresenceHistoryService presenceHistoryService(StartupImport imported, PresenceService presence,
                                                         PresenceHistoryStore store, EventHub hub, HostLog hostLog,
                                                         Clocks clocks, LocalDays days, PresenceEventBus bus,
                                                         @Value("${marvin.history.offline-after-s:15}") double offlineAfterS) {
        PresenceHistoryService history = new PresenceHistoryService(presence, store,
                List.of(hub, new HistoryLog(hostLog)), clocks, days, offlineAfterS, HistoryWriter.start());
        bus.subscribe(history::onEvent);
        return history;
    }

    /** The Python host's history (or the demo's), imported before the settings and the history are read. */
    @Bean
    public StartupImport startupImport(SqliteImporter importer, Environment env, SidecarProperties sidecars,
                                       Clocks clocks, LocalDays days) {
        boolean demo = "demo".equalsIgnoreCase(env.getProperty("marvin.mode", "live"));
        String file = env.getProperty("marvin.import.sqlite", "");
        PythonRuntime python = demo && sidecars.seed() ? PythonRuntime.find(sidecars).orElse(null) : null;
        return new StartupImport(importer, demo || file.isBlank() ? null : Path.of(file), python, clocks, days.zone());
    }

    @Bean
    public HistoryLifecycle historyLifecycle(PresenceHistoryService history) {
        return new HistoryLifecycle(history);
    }

    // ------------------------------------------------------------------ the app's other contexts

    @Bean
    public Clocks clocks() {
        return new SystemClocks();
    }

    @Bean
    public LocalDays localDays(@Value("${marvin.time-zone:}") String zone) {
        return new LocalDays(zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone));
    }

    @Bean
    public SettingsService settingsService(StartupImport imported, SettingsStore store, PresenceService presence,
                                           EventHub hub) {
        SettingsService settings = new SettingsService(store, AppSettings.defaults(presence.stillLongS()));
        settings.ignored().forEach(s -> log.warn("ignoring stored setting {}", s));
        presence.setStillLongS(settings.current().breakIntervalS());
        settings.addListener(s -> presence.setStillLongS(s.breakIntervalS()));
        settings.addListener(hub);
        return settings;
    }

    @Bean
    public ConversationService conversationService(ConversationStore store, LocalDays days) {
        return new ConversationService(store, days);
    }

    @Bean(destroyMethod = "")
    public HostLogService hostLogService(Clocks clocks, EventHub hub) {
        HostLogService hostLog = new HostLogService(clocks);
        hostLog.addListener(hub);
        return hostLog;
    }

    @Bean(destroyMethod = "detach")
    public LogPanelAppender logPanelAppender(HostLogService hostLog) {
        return LogPanelAppender.attach(hostLog);
    }

    @Bean
    public FaceService faceService(PresenceService presence, Clocks clocks, PresenceEventBus bus) {
        FaceService face = new FaceService(presence, clocks);
        bus.subscribe(face::onPresenceEvent);
        return face;
    }

    // ------------------------------------------------------------------ robot

    @Bean
    public RobotLinkService robotLinkService(Extrinsics extrinsics, SystemHostClock clock, PresenceService presence,
                                             HostLog hostLog, EventHub hub, ObjectProvider<RobotLinkQuery> query,
                                             ObjectProvider<RobotAudioService> audio) {
        DeviceNotices notices = new DeviceNotices(hostLog, hub, () -> Views.devicesOf(query.getObject()));
        return new RobotLinkService(extrinsics, clock, List.of(new PresenceFeed(presence)),
                List.of(HostWiring::logNotice, notices, n -> audio.getObject().onNotice(n)));
    }

    @Bean
    @ConditionalOnExpression("'${marvin.mode:live}' == 'demo' and ${marvin.sidecar.simulator:true}")
    public DemoRobot demoRobot(SidecarProperties sidecars, UdpRobotLink link) {
        return new DemoRobot(sidecars, link);
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

    // ------------------------------------------------------------------ health

    /**
     * Each component probe as an Actuator health indicator, under {@code /actuator/health/marvin/<name>}, so
     * health groups can include them. A disabled component is {@code UNKNOWN}: it does not make the host down.
     */
    @Bean
    public org.springframework.boot.health.contributor.CompositeHealthContributor marvinHealthContributor(
            List<ComponentProbe> probes) {
        Map<String, org.springframework.boot.health.contributor.HealthIndicator> indicators = new java.util.LinkedHashMap<>();
        for (ComponentProbe p : probes) {
            indicators.put(p.name(), () -> {
                ComponentHealth h = p.check();
                var b = switch (h.state()) {
                    case UP -> org.springframework.boot.health.contributor.Health.up();
                    case DOWN -> org.springframework.boot.health.contributor.Health.down();
                    case DISABLED -> org.springframework.boot.health.contributor.Health.unknown();
                };
                return b.withDetail("detail", h.detail()).build();
            });
        }
        return org.springframework.boot.health.contributor.CompositeHealthContributor.fromMap(indicators);
    }
}
