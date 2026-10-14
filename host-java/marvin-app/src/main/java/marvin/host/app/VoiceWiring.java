// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.tracing.Tracer;

import marvin.host.adapter.llm.OllamaLanguageModel;
import marvin.host.adapter.persistence.VoiceSettingsFile;
import marvin.host.adapter.persistence.VoiceprintsFile;
import marvin.host.adapter.robot.UdpRobotLink;
import marvin.host.adapter.sidecar.GrpcVoiceSidecar;
import marvin.host.adapter.sidecar.PythonRuntime;
import marvin.host.adapter.sidecar.SidecarProperties;
import marvin.host.adapter.web.EventHub;
import marvin.host.application.conversation.VoiceEnrollmentService;
import marvin.host.application.conversation.VoiceService;
import marvin.host.application.conversation.port.out.JsonFetcher;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.presence.PresenceService;
import marvin.host.application.robot.BuzzerChimeService;
import marvin.host.application.robot.RobotAudioService;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.settings.SettingsService;
import marvin.host.application.system.port.in.HostLog;
import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.application.vision.port.in.Camera;
import marvin.host.domain.conversation.Greeting;
import marvin.host.domain.settings.AppSettings;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.system.ComponentHealth;

/**
 * The voice: the sidecar process and its gRPC session, the conversation service, the robot's audio relay,
 * the log lines and the health report.
 */
@Configuration(proxyBeanMethods = false)
public class VoiceWiring {

    /** The platform as Python names it ({@code sys.platform}): the settings panel offers macOS voices on {@code darwin}. */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? "darwin" : os.contains("win") ? "win32" : os.contains("linux") ? "linux" : os;
    }

    /** The owner's {@code voice.json}; in demo mode a copy of it in the data directory, so the demo keeps nothing. */
    @Bean
    public VoiceSettingsFile voiceSettingsFile(@Value("${marvin.config-dir:}") String configDir,
                                               @Value("${marvin.mode:live}") String mode,
                                               @Value("${marvin.data-dir}") String dataDir) {
        VoiceSettingsFile owner = VoiceSettingsFile.inConfigDir(configDir);
        return "demo".equals(mode) ? VoiceSettingsFile.demoCopy(owner, java.nio.file.Path.of(dataDir, "demo")) : owner;
    }

    /** The voices Marvin knows ({@code voiceprints.json} beside voice.json); the demo starts with none of the owner's. */
    @Bean
    public VoiceprintsFile voiceprintsFile(@Value("${marvin.config-dir:}") String configDir,
                                           @Value("${marvin.mode:live}") String mode,
                                           @Value("${marvin.data-dir}") String dataDir) {
        if ("demo".equals(mode)) {
            java.nio.file.Path demo = java.nio.file.Path.of(dataDir, "demo", VoiceprintsFile.NAME);
            try {
                java.nio.file.Files.deleteIfExists(demo);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot reset " + demo + ": " + e.getMessage(), e);
            }
            return new VoiceprintsFile(demo);
        }
        return VoiceprintsFile.inConfigDir(configDir);
    }

    /** The voice calibration: the device's thresholds, the voices Marvin knows. */
    @Bean
    public VoiceEnrollmentService voiceEnrollment(VoiceService voice, VoiceSettingsFile settingsFile,
                                                  VoiceprintsFile prints, Clocks clocks) {
        VoiceEnrollmentService enrollment = new VoiceEnrollmentService(voice, settingsFile, prints, clocks);
        voice.setVoiceprints(prints::load);
        voice.setEnrollment(enrollment::onProgress);
        return enrollment;
    }

    @Bean(destroyMethod = "")
    public GrpcVoiceSidecar grpcVoiceSidecar(SidecarProperties props) {
        PythonRuntime python = props.voice() ? PythonRuntime.find(props).orElse(null) : null;
        return new GrpcVoiceSidecar(python, props.voiceArgs());
    }

    @Bean(destroyMethod = "")
    public VoiceService voiceService(StartupImport imported, GrpcVoiceSidecar sidecar, OllamaLanguageModel model,
                                     VoiceSettingsFile settingsFile, JsonFetcher fetch, ConversationStore store,
                                     PresenceService presence, Clocks clocks, LocalDays days, EventHub hub,
                                     HostLog hostLog, PresenceEventBus bus, ObjectProvider<Tracer> tracer,
                                     BuzzerChimeService chimes, RobotLinkQuery robot, Camera camera,
                                     SettingsService settings, @Value("${marvin.data-dir}") String dataDir) {
        VoiceService voice = new VoiceService(sidecar, model, settingsFile, fetch, store, presence, clocks, days,
                platform(), sidecar::audioRobot);
        tracer.ifAvailable(t -> voice.setTracing(new MicrometerTracing(t)));
        voice.setRoom(robot::room);                         // the room sensors' readings in the question's context
        voice.setCamera(camera);                            // the look tool, while a camera can take a picture
        // the greeting on sitting down names the owner (C5); nothing on Marvin's own in quiet hours
        voice.setOwner(() -> owner(settings.current().owner()));
        voice.setLatencyLog(new LatencyFile(java.nio.file.Path.of(dataDir, LatencyFile.NAME)));     // C6
        voice.setQuiet(() -> settings.current().quietHours().contains(days.minuteOfDay(clocks.wallSeconds())));
        voice.addListener(hub);
        voice.addListener((kind, payload) -> logLine(hostLog, kind, payload));
        voice.addListener(new VoiceChimes(chimes, voice::earcon));      // the voice's earcons, on one device
        bus.subscribe(voice::onPresenceEvent);
        return voice;
    }

    /** The app's owner setting as the voice sees it. */
    static Greeting.Owner owner(AppSettings.Owner o) {
        return o == null ? Greeting.Owner.NONE : new Greeting.Owner(o.name(), o.languages());
    }

    /** As the Python host's app: the voice's errors and notes in the Log panel. */
    static void logLine(HostLog hostLog, String kind, Map<String, Object> payload) {
        if ("voice".equals(kind) && "error".equals(payload.get("state")) && payload.get("error") instanceof String e
                && !e.isEmpty()) {
            String fix = payload.get("fix") instanceof String f && !f.isEmpty() ? ". " + f : "";
            hostLog.add("voice", "warning", e + fix, Map.of());
        } else if ("transcript".equals(kind) && "note".equals(payload.get("kind"))) {
            hostLog.add("voice", "info", String.valueOf(payload.get("text")), Map.of());
        }
    }

    @Bean
    public RobotAudioService robotAudioService(UdpRobotLink link, RobotLinkQuery devices, GrpcVoiceSidecar sidecar,
                                               ObjectProvider<VoiceService> voice) {
        RobotAudioService audio = new RobotAudioService(link, devices, sidecar, () -> voice.getObject().robotAudioChanged());
        link.addAudioListener(audio::heard);
        return audio;
    }

    /** Starts the sidecar with the host, and the voice if it was on; stops both, last. */
    @Bean
    public SmartLifecycle voiceLifecycle(GrpcVoiceSidecar sidecar, VoiceService voice, SettingsService settings) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
                sidecar.start();
                if (settings.current().voice()) {
                    voice.start();                  // it was on when the host stopped
                }
            }

            @Override
            public void stop() {
                running = false;
                voice.close();
                sidecar.shutdown();
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE / 2 + 200;
            }
        };
    }

    @Bean
    public ComponentProbe voiceProbe(GrpcVoiceSidecar sidecar) {
        return new ComponentProbe() {
            @Override
            public String name() {
                return "voice";
            }

            @Override
            public ComponentHealth check() {
                if (sidecar.serving()) {
                    return ComponentHealth.up("voice sidecar on port " + sidecar.port());
                }
                String why = sidecar.unavailableReason();
                return ComponentHealth.disabled(why.isEmpty() ? "voice sidecar starting" : why);
            }
        };
    }
}
