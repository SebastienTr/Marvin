// SPDX-License-Identifier: MIT
package marvin.host.application.system.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.application.system.port.in.RunSetup;
import marvin.host.application.system.port.out.SetupProbe;
import marvin.host.application.system.port.out.SetupStore;

class SetupServiceTest {

    /** A computer with everything but the MR60BHA2 kit, the speaker and the voiceprint. */
    static final class FakeProbe implements SetupProbe {
        Check host = Check.ok("Java 25, PostgreSQL up, 312 GB free");
        Check model = Check.ok("qwen3.8:27b-mxfp8 installed in Ollama");
        Check voice = Check.ok("The voice's models are here");
        Check robot = Check.ok("marvin-345678 (XIAO ESP32S3 Sense), firmware 0.7.0");
        Check mr60 = Check.missing("No MR60BHA2 kit has said hello", "Flash firmware/mr60_bridge");
        Check voiceprint = Check.missing("Nobody calibrated", null);
        Check play = Check.ok("Marvin played a chime on the robot");
        int plays;
        Owner owner = new Owner("", List.of());
        QuietHours quiet = new QuietHours(false, "22:00", "07:00");

        public Check host() { return host; }
        public Check model() { return model; }
        public Check voice() { return voice; }
        public Check robot() { return robot; }
        public Check mr60() { return mr60; }
        public Check voiceprint() { return voiceprint; }

        public Check playSpeakerTest() {
            plays++;
            return play;
        }

        public Owner owner() { return owner; }
        public QuietHours quietHours() { return quiet; }

        @SuppressWarnings("unchecked")
        public void saveOwner(Map<String, ?> o) {
            if (o.get("name") instanceof String n && n.length() > 40) {
                throw new IllegalArgumentException("owner.name is too long");
            }
            owner = new Owner((String) o.get("name"), (List<String>) o.get("languages"));
        }

        public void saveQuietHours(Map<String, ?> q) {
            quiet = new QuietHours((Boolean) q.get("enabled"), (String) q.get("start"), (String) q.get("end"));
        }
    }

    static final class MemoryStore implements SetupStore {
        final Map<String, Map<String, Object>> rows = new HashMap<>();

        public Map<String, Map<String, Object>> load() {
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            rows.forEach((k, v) -> out.put(k, new LinkedHashMap<>(v)));
            return out;
        }

        public void save(String step, Map<String, Object> state) {
            rows.put(step, new LinkedHashMap<>(state));
        }
    }

    final FakeProbe probe = new FakeProbe();
    final MemoryStore store = new MemoryStore();
    final SetupService setup = new SetupService(probe, store);

    static RunSetup.Step step(RunSetup.Status s, String id) {
        return s.steps().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theNineStepsInTheContractsOrder() {
        RunSetup.Status s = setup.status();
        assertThat(s.steps()).extracting(RunSetup.Step::id).containsExactly("host", "model", "voice", "robot", "mr60",
                "speaker", "voiceprint", "owner", "quiet_hours");
        assertThat(s.steps()).extracting(RunSetup.Step::state).containsOnly("ok", "missing");
        assertThat(s.done()).isFalse();
        assertThat(step(s, "mr60").fix()).isEqualTo("Flash firmware/mr60_bridge");
        assertThat(step(s, "host").fix()).isNull();
    }

    @Test
    void skippedStepsCountAsDoneAndComeBackWhenTheThingIsThere() {
        for (String id : List.of("mr60", "speaker", "voiceprint", "owner", "quiet_hours")) {
            setup.act(id, Map.of("skip", true));
        }
        RunSetup.Status s = setup.status();
        assertThat(s.done()).isTrue();
        assertThat(step(s, "mr60")).isEqualTo(new RunSetup.Step("mr60", "skipped",
                "No MR60BHA2 kit: heart rate and breathing are off", null));
        probe.mr60 = SetupProbe.Check.ok("MR60BHA2 kit (XIAO ESP32C6), firmware 0.2.0");
        assertThat(step(setup.status(), "mr60").state()).isEqualTo("ok");
        setup.act("owner", Map.of("skip", false));
        assertThat(setup.status().done()).isFalse();
    }

    @Test
    void thisComputerCannotBeSkipped() {
        assertThatThrownBy(() -> setup.act("host", Map.of("skip", true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> setup.act("nope", Map.of())).isInstanceOf(RunSetup.UnknownStep.class);
        assertThatThrownBy(() -> setup.act("robot", Map.of("skip", "yes"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> setup.act("robot", Map.of("name", "x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSpeakerIsOkOnlyOnceTheOwnerHeardIt() {
        assertThat(step(setup.status(), "speaker").detail()).startsWith("Play a sentence");
        setup.act("speaker", Map.of("play", true));
        assertThat(probe.plays).isEqualTo(1);
        RunSetup.Step played = step(setup.status(), "speaker");
        assertThat(played.state()).isEqualTo("missing");
        assertThat(played.detail()).isEqualTo("Marvin played a chime on the robot. Did you hear it?");
        setup.act("speaker", Map.of("heard", false));
        assertThat(step(setup.status(), "speaker").fix()).contains("volume");
        setup.act("speaker", Map.of("heard", true));
        assertThat(step(setup.status(), "speaker").state()).isEqualTo("ok");
        assertThat(store.rows.get("speaker")).containsEntry("heard", true);
    }

    @Test
    void aSpeakerTestThatCannotPlaySaysWhy() {
        probe.play = SetupProbe.Check.missing("The voice is off", "Turn the voice on in Talk");
        RunSetup.Step s = step(setup.act("speaker", Map.of("play", true)), "speaker");
        assertThat(s).isEqualTo(new RunSetup.Step("speaker", "missing", "The voice is off", "Turn the voice on in Talk"));
    }

    @Test
    void theOwnerAndTheQuietHoursGoToTheSettings() {
        RunSetup.Status s = setup.act("owner", Map.of("name", "Sébastien", "languages", List.of("fr", "en")));
        assertThat(step(s, "owner")).isEqualTo(new RunSetup.Step("owner", "ok", "Sébastien · French, English", null));
        assertThatThrownBy(() -> setup.act("owner", Map.of("name", "x".repeat(41))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> setup.act("owner", Map.of("age", 33))).isInstanceOf(IllegalArgumentException.class);

        assertThat(step(setup.status(), "quiet_hours").state()).isEqualTo("missing");
        s = setup.act("quiet_hours", Map.of("enabled", false, "start", "22:00", "end", "07:00"));
        assertThat(step(s, "quiet_hours")).isEqualTo(new RunSetup.Step("quiet_hours", "skipped", "No quiet hours", null));
        s = setup.act("quiet_hours", Map.of("enabled", true, "start", "22:30", "end", "07:00"));
        assertThat(step(s, "quiet_hours").state()).isEqualTo("ok");
        assertThat(step(s, "quiet_hours").detail()).startsWith("From 22:30 to 07:00");
    }

    @Test
    void doneWhenEverythingIsOkOrSkipped() {
        probe.mr60 = SetupProbe.Check.ok("kit");
        probe.voiceprint = SetupProbe.Check.ok("Marvin knows Sébastien's voice");
        setup.act("speaker", Map.of("heard", true));
        setup.act("owner", Map.of("name", "Sébastien", "languages", List.of("fr")));
        assertThat(setup.status().done()).isFalse();
        setup.act("quiet_hours", Map.of("skip", true));
        assertThat(setup.status().done()).isTrue();
        probe.robot = SetupProbe.Check.missing("marvin-345678 went offline", "Check its USB-C cable");
        assertThat(setup.status().done()).isFalse();
    }
}
