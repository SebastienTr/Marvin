// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.port.out.VoiceprintStore;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.Voiceprint;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.shared.LocalDays;

/**
 * The voice calibration and the voices Marvin knows (docs/voice.md, "Your voice"): what the app starts, what the voice
 * reports, what is kept (the device's thresholds, the voiceprint) and sent back to the voice, and whose voice asked in
 * the question's context. With the fakes of {@link VoiceServiceTest}.
 */
class VoiceEnrollmentServiceTest {
    final VoiceServiceTest.Sidecar sidecar = new VoiceServiceTest.Sidecar();
    final VoiceServiceTest.Store store = new VoiceServiceTest.Store();
    final VoiceServiceTest.Settings settings = new VoiceServiceTest.Settings();
    final VoiceServiceTest.Clock clock = new VoiceServiceTest.Clock();
    final Prints prints = new Prints();
    final List<Map<String, Object>> transcript = new ArrayList<>();
    final List<VoiceService> services = new ArrayList<>();

    static final class Prints implements VoiceprintStore {
        volatile List<Voiceprint> saved = List.of();

        @Override
        public List<Voiceprint> load() {
            return saved;
        }

        @Override
        public void save(List<Voiceprint> v) {
            saved = List.copyOf(v);
        }
    }

    @AfterEach
    void close() {
        services.forEach(VoiceService::close);
    }

    VoiceService voice(FakeModel model) {
        PresenceQuery presence = new PresenceQuery() {
            @Override
            public PresenceState state() {
                return null;
            }

            @Override
            public List<PresenceEvent> recentEvents() {
                return List.of();
            }
        };
        VoiceService v = new VoiceService(sidecar, model, settings, (url, params, t) -> Map.of(), store, presence, clock,
                new LocalDays(ZoneId.of("Europe/Paris")), "linux", Optional::empty);
        v.addListener((kind, payload) -> {
            if ("transcript".equals(kind)) {
                synchronized (transcript) {
                    transcript.add(payload);
                }
            }
        });
        services.add(v);
        return v;
    }

    VoiceEnrollmentService enrollment(VoiceService v) {
        VoiceEnrollmentService e = new VoiceEnrollmentService(v, settings, prints, clock);
        v.setVoiceprints(prints::load);
        v.setEnrollment(e::onProgress);
        return e;
    }

    static void waitFor(BooleanSupplier cond) throws InterruptedException {
        VoiceServiceTest.waitFor(cond);
    }

    List<Map<String, Object>> kinds(String kind) {
        synchronized (transcript) {
            return transcript.stream().filter(e -> kind.equals(e.get("kind"))).toList();
        }
    }

    static VoiceSidecar.EnrollProgress progress(String state, int index, String text) {
        return new VoiceSidecar.EnrollProgress(state, index, text, 0, 0, "", 0, null);
    }

    static final List<Double> EMBEDDING = List.of(0.6, 0.8);

    static VoiceSidecar.EnrollResult result(String name) {
        return new VoiceSidecar.EnrollResult(name, "computer", "", -68.5, -27.0, -62.5, -45.0, 0.5,
                new Voiceprint(name, EMBEDDING, "wespeaker-ecapa512", "", "computer"),
                List.of("The room is noisy for this microphone: move closer."),
                List.of("Bonjour Marvin, je m'appelle Sébastien.", "Hey Marvin, what's the weather like today?"));
    }

    @Test
    void aCalibrationKeepsTheDevicesThresholdsAndTheVoiceThenTheVoiceKnowsIt() throws InterruptedException {
        VoiceService v = voice(FakeModel.of("w", "w"));
        VoiceEnrollmentService e = enrollment(v);
        assertThat(e.state()).containsEntry("state", "idle").containsEntry("first_run", true);
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        int opened = sidecar.opened.size();
        Map<String, Object> s = e.start("  Sébastien ");
        assertThat(s).containsEntry("name", "Sébastien").containsEntry("state", "starting");
        assertThat((List<?>) s.get("sentences")).first().isEqualTo("Bonjour Marvin, je m'appelle Sébastien.");
        VoiceSidecar.Enroll cmd = sidecar.sent(VoiceSidecar.Enroll.class).getFirst();
        assertThat(cmd.action()).isEqualTo("start");
        assertThat(cmd.name()).isEqualTo("Sébastien");
        assertThat(cmd.sentences()).hasSize(3).first().asString().contains("{name}");

        sidecar.signals.signal(new VoiceSidecar.EnrollProgress("noise", 0, "", 0, 0, "", 1.5, null));
        assertThat(e.state()).containsEntry("state", "noise").containsEntry("seconds", 1.5);
        sidecar.signals.signal(progress("sentence", 0, "Bonjour Marvin, je m'appelle Sébastien."));
        sidecar.signals.signal(new VoiceSidecar.EnrollProgress("heard", 0, "Bonjour Marvin, je m'appelle Sébastien.", 1.0,
                -27.5, "", 0, null));
        sidecar.signals.signal(new VoiceSidecar.EnrollProgress("retry", 1, "", 0, 0, "That was short.", 0, null));
        assertThat(e.state()).containsEntry("message", "That was short.");
        sidecar.signals.signal(progress("sentence", 1, "Hey Marvin, what's the weather like today?"));
        assertThat(e.state()).containsEntry("index", 1).containsEntry("message", "");
        assertThat((List<?>) e.state().get("heard")).hasSize(1);
        sidecar.signals.signal(new VoiceSidecar.EnrollProgress("done", 0, "", 0, 0, "", 0, result("Sébastien")));

        Map<String, Object> done = e.state();
        assertThat(done).containsEntry("state", "done").containsEntry("first_run", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) done.get("result");
        assertThat(r).containsEntry("device", "computer").containsEntry("vad_min_dbfs", -62.5)
                .containsEntry("speech_min_dbfs", -45.0).containsEntry("voiceprint", true).doesNotContainKey("embedding");
        // the device's thresholds in voice.json, with when and what was measured
        @SuppressWarnings("unchecked")
        Map<String, Object> computer = (Map<String, Object>) ((Map<String, Object>) settings.values.get("devices"))
                .get("computer");
        assertThat(computer).containsEntry("vad_min_dbfs", -62.5).containsEntry("speech_min_dbfs", -45.0)
                .containsEntry("vad_threshold", 0.5);
        @SuppressWarnings("unchecked")
        Map<String, Object> calibration = (Map<String, Object>) computer.get("calibration");
        assertThat(calibration).containsEntry("noise_dbfs", -68.5)
                .containsEntry("name", "Sébastien").containsKey("at");
        assertThat(VoiceSettings.config(settings.values).computer().vadMinDbfs()).isEqualTo(-62.5);
        // the voiceprint kept, never shown, and sent to the voice with its settings again
        assertThat(prints.saved).extracting(Voiceprint::name).containsExactly("Sébastien");
        assertThat(prints.saved.getFirst().embedding()).isEqualTo(EMBEDDING);
        assertThat((List<?>) done.get("people")).hasSize(1).first().asString().doesNotContain("0.6");
        waitFor(() -> sidecar.opened.size() > opened);
        VoiceSidecar.Settings again = sidecar.opened.getLast();
        assertThat(again.ears().voiceprints()).extracting(Voiceprint::name).containsExactly("Sébastien");
        assertThat(again.device().speechMinDbfs()).isEqualTo(-45.0);

        // forgotten: gone from the store and from the voice
        int before = sidecar.opened.size();
        e.forget("sébastien");
        assertThat(prints.saved).isEmpty();
        waitFor(() -> sidecar.opened.size() > before && sidecar.opened.getLast().ears().voiceprints().isEmpty());
        assertThatThrownBy(() -> e.forget("Nobody")).isInstanceOf(VoiceSettings.InvalidVoiceSettingException.class);
    }

    @Test
    void itNeedsTheVoiceAndAName() throws InterruptedException {
        VoiceService v = voice(FakeModel.of("w", "w"));
        VoiceEnrollmentService e = enrollment(v);
        assertThatThrownBy(() -> e.start("Ana")).isInstanceOf(VoiceControl.VoiceOff.class);
        assertThat(e.state()).containsEntry("state", "idle");
        assertThatThrownBy(() -> e.start("")).isInstanceOf(VoiceSettings.InvalidVoiceSettingException.class);
        assertThatThrownBy(() -> e.start("<script>")).isInstanceOf(VoiceSettings.InvalidVoiceSettingException.class);
        List<Voiceprint> full = new ArrayList<>();
        for (int i = 0; i < Voiceprint.MAX_PEOPLE; i++) {
            full.add(new Voiceprint("P" + i, EMBEDDING, "m", "", "computer"));
        }
        prints.save(full);
        assertThatThrownBy(() -> e.start("Ana")).hasMessageContaining("delete one first");
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        e.start("P3");                                          // calibrating someone known again is fine
        e.cancel();
        assertThat(e.state()).containsEntry("state", "cancelled");
        assertThat(sidecar.sent(VoiceSidecar.Enroll.class)).extracting(VoiceSidecar.Enroll::action)
                .containsExactly("start", "cancel");
    }

    @Test
    void whoseVoiceAskedIsInTheContextAndTheTranscript() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Noté.", "Bonjour.");
        VoiceService v = voice(model);
        v.start();
        waitFor(() -> model.calls.size() == 2);
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Je pars en mer demain.", "", "fr", "voice", Map.of(), 0,
                List.of(), "Sébastien", 0.81));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat(kinds("heard").getFirst()).containsEntry("speaker", "Sébastien").containsEntry("speaker_score", 0.81);
        assertThat((String) kinds("reply").getFirst().get("context"))
                .contains("- The voice asking is Sébastien's (you recognise it)");
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Salut.", "", "fr", "voice", Map.of(), 0, List.of(), "unknown",
                0.12));
        waitFor(() -> kinds("reply").size() == 2);
        assertThat((String) kinds("reply").get(1).get("context")).contains("You do not recognise the voice asking");
        assertThat(Persona.speakerFact("")).isNull();
        assertThat(new VoiceSidecar.Heard(3, "x", "", "fr", "voice", Map.of(), 0).knownSpeaker()).isFalse();
    }
}
