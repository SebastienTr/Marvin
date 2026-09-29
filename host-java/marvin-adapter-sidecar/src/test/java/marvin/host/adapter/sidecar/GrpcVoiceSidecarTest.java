// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.VoiceSidecar;
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

    @Test
    void theSettingsOfAQuestionThatGoesOnAreSentOnlyWhenSet() {
        var defaults = GrpcVoiceSidecar.configure(new VoiceSidecar.Settings("auto", "", "auto", "", "", "fr", true, false,
                0, 5, 0, true, 0, true, "", "", false)).getSettings();
        assertThat(defaults.hasContinueGraceS()).isFalse();
        assertThat(defaults.hasEndSilenceLongMs()).isFalse();
        var set = GrpcVoiceSidecar.configure(new VoiceSidecar.Settings("auto", "", "auto", "", "", "fr", true, false,
                0, 5, 0, true, 0, true, "", "", false, 0.0, 1200.0)).getSettings();
        assertThat(set.hasContinueGraceS()).isTrue();
        assertThat(set.getContinueGraceS()).isEqualTo(0.0);
        assertThat(set.getEndSilenceLongMs()).isEqualTo(1200.0);
        assertThat(List.of(set.getWake(), set.getChime())).containsOnly(true);
    }
}
