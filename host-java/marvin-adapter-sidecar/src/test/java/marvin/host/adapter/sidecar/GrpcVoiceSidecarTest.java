// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;

import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.contracts.voice.v1.AudioRoute;
import marvin.host.domain.conversation.AudioDeviceProfile;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.contracts.voice.v1.Heard;
import marvin.host.contracts.voice.v1.Status;
import marvin.host.contracts.voice.v1.VoiceToCore;

/** The contract's messages as the voice port's records, and back (no sidecar needed). */
class GrpcVoiceSidecarTest {

    @Test
    void aQuestionThatGoesOnSaysWhichOnesItContinues() {
        var h = (VoiceSidecar.Heard) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setHeard(Heard.newBuilder()
                .setUid(5).setText("Je suis développeur. Et j'aime la voile.").setLanguage("fr").setSource("voice")
                .addContinues(3).addContinues(4)).build());
        assertThat(h.continues()).containsExactly(3L, 4L);
        var alone = (VoiceSidecar.Heard) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setHeard(Heard.newBuilder()
                .setUid(6).setText("Bonjour.")).build());
        assertThat(alone.continues()).isEmpty();
    }

    @Test
    void theWindowWaitsWhileSomeoneTalks() {
        var s = (VoiceSidecar.Status) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setStatus(Status.newBuilder()
                .setState(Status.State.LISTENING).setHearing(true)).build());
        assertThat(s.hearing()).isTrue();
        assertThat(s.listenS()).isNull();
    }

    static VoiceSidecar.Settings defaults(AudioDeviceProfile device) {
        return VoiceSidecar.Settings.of(VoiceSettings.config(Map.of()), device);
    }

    @Test
    void everyValueIsSentWithTheDevice() {
        var c = GrpcVoiceSidecar.configure(defaults(AudioDeviceProfile.ROBOT_DEFAULT.forRobot("marvin-a1b2c3")));
        assertThat(c.getContractVersion()).isEqualTo(2);
        assertThat(c.getRoute()).isEqualTo(AudioRoute.AUDIO_ROUTE_ROBOT);
        assertThat(c.getDevice().getKind()).isEqualTo("robot");
        assertThat(c.getDevice().getRobot()).isEqualTo("marvin-a1b2c3");
        assertThat(c.getDevice().getSpeechMinDbfs()).isEqualTo(-45.0);
        var s = c.getSettings();
        assertThat(s.hasContinueGraceS()).isTrue();
        assertThat(List.of(s.getEndSilenceMs(), s.getEndSilenceLongMs(), s.getContinueGraceS(), s.getContinueJoinS(),
                s.getSpeculateAfterS())).containsExactly(550.0, 1100.0, 1.5, 6.0, 0.25);
        var off = GrpcVoiceSidecar.configure(VoiceSidecar.Settings.of(VoiceSettings.config(Map.of("continue_grace_s", 0,
                "end_silence_long_ms", 0)), AudioDeviceProfile.COMPUTER_DEFAULT));
        assertThat(off.getSettings().hasContinueGraceS()).as("0 is sent, as 0: off").isTrue();
        assertThat(off.getSettings().getEndSilenceLongMs()).isEqualTo(0.0);
        assertThat(off.getRoute()).isEqualTo(AudioRoute.AUDIO_ROUTE_LOCAL);
    }

    @Test
    void theCommandsCarryTheirClausesAndEarcons() {
        // through a session-less sidecar: the message as it would be sent
        var t = marvin.host.contracts.voice.v1.TextPiece.newBuilder();
        assertThat(t.getClause()).isFalse();
        var st = (VoiceSidecar.Status) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setStatus(Status.newBuilder()
                .setState(Status.State.LISTENING).setAsked(true).setDevice("marvin-a1b2c3")).build());
        assertThat(st.asked()).isTrue();
        assertThat(st.device()).isEqualTo("marvin-a1b2c3");
    }

    @Test
    void theEarsAndTheVoicesMarvinKnowsAreSent() {
        var c = VoiceSettings.config(Map.of("vocabulary", List.of("Marvin", "Aashma"), "known_voices_only", true,
                "turn", "silence", "devices", Map.of("computer", Map.of("vad_threshold", 0.7, "speech_min_dbfs", -42.0))));
        var print = new marvin.host.domain.conversation.Voiceprint("Sébastien", List.of(0.6, 0.8), "wespeaker-ecapa512",
                "2026-10-04T10:00", "computer");
        var cfg = GrpcVoiceSidecar.configure(VoiceSidecar.Settings.of(c, c.computer(), List.of(print)));
        var s = cfg.getSettings();
        assertThat(s.getVocabularyList()).containsExactly("Marvin", "Aashma");
        assertThat(s.getKnownVoicesOnly()).isTrue();
        assertThat(s.getTurn()).isEqualTo("silence");
        assertThat(List.of(s.getTurnCheckMs(), s.getTurnMaxMs(), s.getSpeakerThreshold())).containsExactly(200.0, 2000.0, 0.45);
        assertThat(s.getVoiceprints(0).getName()).isEqualTo("Sébastien");
        assertThat(s.getVoiceprints(0).getEmbeddingList()).containsExactly(0.6f, 0.8f);
        assertThat(cfg.getDevice().getVadThreshold()).isEqualTo(0.7);
        assertThat(cfg.getDevice().getSpeechMinDbfs()).isEqualTo(-42.0);
    }

    @Test
    void whoSpokeAndTheCalibrationComeBack() {
        var h = (VoiceSidecar.Heard) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setHeard(Heard.newBuilder()
                .setUid(9).setText("Bonjour.").setSpeaker("Sébastien").setSpeakerScore(0.82)).build());
        assertThat(h.speaker()).isEqualTo("Sébastien");
        assertThat(h.speakerScore()).isEqualTo(0.82);
        assertThat(h.knownSpeaker()).isTrue();
        var untold = (VoiceSidecar.Heard) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setHeard(Heard.newBuilder()
                .setUid(10).setText("Oui.")).build());
        assertThat(untold.speaker()).isEmpty();
        assertThat(untold.speakerScore()).isNull();
        var r = marvin.host.contracts.voice.v1.EnrollResult.newBuilder().setName("Ana").setNoiseDbfs(-70).setSpeechDbfs(-28)
                .setVadMinDbfs(-64).setSpeechMinDbfs(-46).setVadThreshold(0.5).addWarnings("noisy").addTranscripts("Bonjour")
                .setDevice(marvin.host.contracts.voice.v1.AudioDeviceProfile.newBuilder().setKind("robot").setRobot("marvin-a1"))
                .setVoiceprint(marvin.host.contracts.voice.v1.Voiceprint.newBuilder().setName("Ana").setModel("m")
                        .addEmbedding(0.5f).addEmbedding(-0.25f));
        var p = (VoiceSidecar.EnrollProgress) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setEnroll(
                marvin.host.contracts.voice.v1.EnrollProgress.newBuilder().setState("done").setResult(r)).build());
        assertThat(p.state()).isEqualTo("done");
        assertThat(p.result().kind()).isEqualTo("robot");
        assertThat(p.result().robot()).isEqualTo("marvin-a1");
        assertThat(p.result().speechMinDbfs()).isEqualTo(-46.0);
        assertThat(p.result().voiceprint().embedding()).containsExactly(0.5, -0.25);
        assertThat(p.result().warnings()).containsExactly("noisy");
        var heard = (VoiceSidecar.EnrollProgress) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setEnroll(
                marvin.host.contracts.voice.v1.EnrollProgress.newBuilder().setState("heard").setIndex(1).setText("Hey")
                        .setMatch(0.9).setDbfs(-27)).build());
        assertThat(heard.result()).isNull();
        assertThat(heard.match()).isEqualTo(0.9);
        var noPrint = (VoiceSidecar.EnrollProgress) GrpcVoiceSidecar.signal(VoiceToCore.newBuilder().setEnroll(
                marvin.host.contracts.voice.v1.EnrollProgress.newBuilder().setState("done")
                        .setResult(r.clearVoiceprint())).build());
        assertThat(noPrint.result().voiceprint()).as("no speaker model: calibration only").isNull();
    }

    static final Path SPEC = Path.of("../marvin-contracts/golden/voice/configure_defaults.json");

    /** JSON as Python's json.dumps(indent=2) writes it, for these flat values. */
    static String json(Object v, String indent) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            StringBuilder b = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append(indent).append("  \"").append(e.getKey()).append("\": ").append(json(e.getValue(), indent + "  "));
                b.append(++i < m.size() ? ",\n" : "\n");
            }
            return b.append(indent).append("}").toString();
        }
        if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                return "[]";
            }
            StringBuilder b = new StringBuilder("[\n");
            int i = 0;
            for (Object o : l) {
                b.append(indent).append("  ").append(json(o, indent + "  ")).append(++i < l.size() ? ",\n" : "\n");
            }
            return b.append(indent).append("]").toString();
        }
        if (v instanceof String str) {
            return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (v instanceof Double d) {
            return d == Math.rint(d) ? String.format(java.util.Locale.ROOT, "%.1f", d) : Double.toString(d);
        }
        return String.valueOf(v);
    }

    /** The defaults as JSON, the field names of voice.proto. */
    static Map<String, Object> asMap(Message m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Descriptors.FieldDescriptor f : m.getDescriptorForType().getFields()) {
            Object v = m.getField(f);
            out.put(f.getName(), v instanceof Descriptors.EnumValueDescriptor e ? e.getName() : v);
        }
        return out;
    }

    /**
     * The voice's defaults are written in one place (VoiceSettings); the sidecar's own copies (its tests' fallback)
     * are checked against this file by its tests (host/tests/test_voice_sidecar.py). Run with
     * {@code -Dmarvin.contract.write=true} to write it again after changing a default on purpose.
     */
    @Test
    void theDefaultsAreTheSpecification() throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("about", "The voice's defaults as the Java host sends them (Configure, contract version 2): the settings and "
                + "the two devices' profiles. Kept by GrpcVoiceSidecarTest; the Python sidecar's own defaults must equal them.");
        spec.put("settings", asMap(GrpcVoiceSidecar.configure(defaults(AudioDeviceProfile.COMPUTER_DEFAULT)).getSettings()));
        Map<String, Object> devices = new LinkedHashMap<>();
        devices.put("computer", asMap(GrpcVoiceSidecar.device(AudioDeviceProfile.COMPUTER_DEFAULT)));
        devices.put("robot", asMap(GrpcVoiceSidecar.device(AudioDeviceProfile.ROBOT_DEFAULT)));
        spec.put("devices", devices);
        String text = json(spec, "") + "\n";
        if (Boolean.getBoolean("marvin.contract.write")) {
            Files.writeString(SPEC, text);
        }
        try (InputStream in = getClass().getResourceAsStream("/marvin/contracts/golden/voice/configure_defaults.json")) {
            assertThat(in).as("golden/voice/configure_defaults.json").isNotNull();
            assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(text);
        }
    }
}
