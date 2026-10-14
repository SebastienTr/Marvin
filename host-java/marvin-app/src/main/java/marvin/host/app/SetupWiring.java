// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.adapter.persistence.JdbcSetupStore;
import marvin.host.application.conversation.VoiceService;
import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.in.VoiceEnrollment;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.settings.port.in.ManageSettings;
import marvin.host.application.system.port.in.ReportHealth;
import marvin.host.application.system.port.in.RunSetup;
import marvin.host.application.system.port.out.SetupProbe;
import marvin.host.application.system.setup.SetupService;
import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.settings.AppSettings;
import marvin.host.domain.system.ComponentHealth;

/**
 * The first-run setup (docs/ui.md "Setting Marvin up"): the setup service, its store, and what it checks on this
 * host: Java, the database and the disk; Ollama and the voice's model; the voice sidecar's models; the robot and the
 * MR60BHA2 kit among the linked devices; the voices Marvin knows; the speaker test; the owner's settings.
 */
@Configuration(proxyBeanMethods = false)
public class SetupWiring {

    @Bean
    public RunSetup runSetup(SetupProbe probe, JdbcSetupStore store) {
        return new SetupService(probe, store);
    }

    @Bean
    public SetupProbe setupProbe(ReportHealth health, ManageSettings settings, ObjectProvider<VoiceService> voice,
                                 ObjectProvider<VoiceEnrollment> enrollment, RobotLinkQuery robot,
                                 @Value("${marvin.data-dir:}") String dataDir) {
        return new HostChecks(health, settings, voice::getIfAvailable, enrollment::getIfAvailable, robot,
                dataDir.isBlank() ? null : Path.of(dataDir));
    }

    /** Below this, the setup says the disk is nearly full (models, the database and the log need room). */
    static final long LOW_DISK_BYTES = 2L << 30;

    /** The ears' models, as the app names them (ears.js). */
    static final Map<String, String> EARS = Map.of("silero-vad", "Silero VAD", "smart-turn", "Smart Turn",
            "speaker", "speaker recognition", "parakeet", "Parakeet");

    /** The checks, on this host's services. */
    static final class HostChecks implements SetupProbe {
        private final ReportHealth health;
        private final ManageSettings settings;
        private final Supplier<VoiceService> voice;
        private final Supplier<VoiceEnrollment> enrollment;
        private final RobotLinkQuery robot;
        private final Path dataDir;

        HostChecks(ReportHealth health, ManageSettings settings, Supplier<VoiceService> voice,
                   Supplier<VoiceEnrollment> enrollment, RobotLinkQuery robot, Path dataDir) {
            this.health = health;
            this.settings = settings;
            this.voice = voice;
            this.enrollment = enrollment;
            this.robot = robot;
            this.dataDir = dataDir;
        }

        @Override
        public Check host() {
            String java = "Java " + Runtime.version().feature();
            ComponentHealth db = health.health().components().get("database");
            if (db != null && !db.isUp()) {
                return Check.missing(java + "; the database does not answer: " + db.detail(),
                        "./marvin doctor (PostgreSQL in Docker: start Docker Desktop)");
            }
            // "PostgreSQL 18.6 (Debian 18.6-1.pgdg12+2), pgvector 0.8.6": without the build in brackets
            String database = db == null ? "the database" : db.detail().isBlank() ? "PostgreSQL"
                    : db.detail().replaceAll(" \\([^)]*\\)", "");
            String disk = "";
            if (dataDir != null) {
                try {
                    Path p = Files.exists(dataDir) ? dataDir : dataDir.toAbsolutePath().getRoot();
                    long free = Files.getFileStore(p).getUsableSpace();
                    if (free < LOW_DISK_BYTES) {
                        return Check.missing(java + ", " + database + ", only " + gigabytes(free) + " free on the disk",
                                "Free some disk space: the models and the database need a few GB");
                    }
                    disk = ", " + gigabytes(free) + " free";
                } catch (IOException | RuntimeException e) {
                    disk = "";
                }
            }
            return Check.ok(java + ", " + database + disk);
        }

        static String gigabytes(long bytes) {
            double gb = bytes / (double) (1L << 30);
            return gb < 10 ? String.format(java.util.Locale.ROOT, "%.1f GB", gb) : Math.round(gb) + " GB";
        }

        @Override
        public Check model() {
            VoiceService v = voice.get();
            if (v == null || !v.available()) {
                return Check.missing("Marvin cannot reach a language model: the voice is not available here",
                        v == null ? null : v.unavailableFix());
            }
            Map<String, Object> options = v.options();
            Map<?, ?> ollama = (Map<?, ?>) options.get("ollama");
            if (ollama == null || !Boolean.TRUE.equals(ollama.get("ok"))) {
                return Check.missing(ollama == null ? "Ollama does not answer" : String.valueOf(ollama.get("error")),
                        "Install Ollama (https://ollama.com/download), then start it: ollama serve, or the Ollama app");
            }
            String model = String.valueOf(v.appSettings().getOrDefault("llm_model", ""));
            List<?> models = (List<?>) options.getOrDefault("llm_models", List.of());
            if (model.isBlank()) {
                return Check.missing("No language model chosen", "Choose one in Marvin > Voice & models");
            }
            if (!models.contains(model) && !models.contains(model + ":latest")) {
                return Check.missing(model + " is not in Ollama yet", "ollama pull " + model);
            }
            return Check.ok(model + " installed in Ollama");
        }

        @Override
        public Check voice() {
            VoiceService v = voice.get();
            if (v == null || !v.available()) {
                return Check.missing(v == null ? "The voice is not available here" : v.unavailableReason(),
                        v == null ? null : v.unavailableFix());
            }
            List<?> ears = (List<?>) v.options().getOrDefault("ears", List.of());
            List<String> missing = ears.stream().map(e -> (Map<?, ?>) e)
                    .filter(e -> !Boolean.TRUE.equals(e.get("installed")))
                    .map(e -> EARS.getOrDefault(String.valueOf(e.get("name")), String.valueOf(e.get("name")))).toList();
            if (!missing.isEmpty()) {
                return Check.missing("The voice sidecar's models are not all here: missing " + String.join(", ", missing),
                        "./marvin up downloads them (./marvin doctor says what is missing)");
            }
            var s = v.snapshot();
            if ("error".equals(s.state())) {
                return Check.missing("The voice did not start: " + s.error(), s.fix());
            }
            return Check.ok(ears.isEmpty()
                    ? "The voice " + ("on".equals(s.state()) ? "runs" : "is ready (off: turn it on in Talk)")
                    : "Speech recognition, voice activity and end-of-turn models are here; the voice "
                    + ("on".equals(s.state()) ? "runs" : "is off: turn it on in Talk"));
        }

        @Override
        public Check robot() {
            List<DeviceStatus> devices = robot.devices();
            for (DeviceStatus d : devices) {
                if (d.online() && d.role() == Board.Role.ROBOT) {
                    return Check.ok(d.name() + " (" + d.board() + "), firmware " + d.firmware()
                            + (d.simulated() ? ", simulated sensors" : ""));
                }
            }
            for (DeviceStatus d : devices) {
                if (d.online() && d.role() == Board.Role.SIMULATOR) {
                    return Check.ok("A simulated robot (the demo), firmware " + d.firmware());
                }
            }
            for (DeviceStatus d : devices) {
                if (d.role() == Board.Role.ROBOT) {
                    return Check.missing(d.name() + " went offline",
                            "Check its USB-C cable and that it is on the same Wi-Fi as this computer");
                }
            }
            return Check.missing("No robot has said hello yet",
                    "Plug the robot in (USB-C): it joins the Wi-Fi from its secrets.h and finds this computer by itself");
        }

        @Override
        public Check mr60() {
            List<DeviceStatus> devices = robot.devices();
            for (DeviceStatus d : devices) {
                if (d.online() && d.role() == Board.Role.VITALS) {
                    return Check.ok(d.name() + " (" + d.board() + "), firmware " + d.firmware());
                }
            }
            boolean simulated = devices.stream().anyMatch(d -> d.online() && d.role() == Board.Role.SIMULATOR);
            if (simulated && robot.scene(false).vitals() != null) {
                return Check.ok("Simulated heart rate and breathing (the demo)");
            }
            for (DeviceStatus d : devices) {
                if (d.role() == Board.Role.VITALS) {
                    return Check.missing(d.name() + " went offline", "Check the kit's USB-C power");
                }
            }
            return Check.missing("No MR60BHA2 kit has said hello",
                    "Flash firmware/mr60_bridge on the kit once over USB, then power it near Marvin");
        }

        @Override
        public Check voiceprint() {
            VoiceEnrollment e = enrollment.get();
            if (e == null) {
                return Check.missing("Voice calibration is not available here", null);
            }
            List<?> people = (List<?>) e.state().getOrDefault("people", List.of());
            if (people.isEmpty()) {
                return Check.missing("Read three sentences so Marvin knows your voice", null);
            }
            List<String> names = people.stream().map(p -> String.valueOf(((Map<?, ?>) p).get("name"))).toList();
            return Check.ok("Marvin knows " + String.join(", ", names) + (names.size() == 1 ? "'s voice" : "'s voices"));
        }

        @Override
        public Check playSpeakerTest() {
            VoiceService v = voice.get();
            if (v == null || !v.available()) {
                return Check.missing("The voice is not available here: nothing can play", null);
            }
            var s = v.snapshot();
            if (!"on".equals(s.state())) {
                return Check.missing("The voice is off: Marvin's speaker plays only while it runs",
                        "Turn the voice on in Talk, then play it again");
            }
            // a sentence needs the voice's TTS (VoiceControl has no "say" yet); until then, the earcon on its device
            if (v.earcon("done")) {
                String where = s.device() == null || s.device().isBlank() || "computer".equals(s.device())
                        ? "this computer" : "the robot " + s.device();
                return Check.ok("Marvin played a chime on " + where);
            }
            return Check.missing("The voice's device has no speaker for sounds, or sounds are off",
                    "Turn Marvin > Preferences > sounds on, and check that the robot's audio is on");
        }

        @Override
        public Owner owner() {
            AppSettings.Owner o = settings.current().owner();
            return new Owner(o.name(), o.languages());
        }

        @Override
        public QuietHours quietHours() {
            AppSettings.QuietHours q = settings.current().quietHours();
            return new QuietHours(q.enabled(), q.start(), q.end());
        }

        @Override
        public void saveOwner(Map<String, ?> owner) {
            AppSettings.Owner now = settings.current().owner();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", now.name());
            o.put("languages", now.languages());
            o.putAll(owner);
            settings.update(Map.of("owner", o));
        }

        @Override
        public void saveQuietHours(Map<String, ?> quietHours) {
            settings.update(Map.of("quiet_hours", quietHours));
        }
    }
}
