// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.domain.conversation.tool.Look;
import marvin.host.domain.conversation.tool.ToolArguments;
import marvin.host.domain.conversation.tool.ToolSpec;

/** The {@code look} tool's words, and the persona that goes with it (the Java host only: the Python host has no camera). */
class LookTest {

    @Test
    void theToolIsOfflineSaysAFillerAndTakesAnOptionalQuestion() {
        ToolSpec spec = Look.spec();
        assertThat(spec.name()).isEqualTo("look");
        assertThat(spec.online()).isFalse();
        assertThat(spec.saysFiller()).isTrue();
        assertThat(spec.timeoutS()).isGreaterThan(Look.CAPTURE_S + Look.DESCRIBE_S);
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) spec.schema().get("function");
        assertThat(fn).containsEntry("name", "look").containsKey("description");
        assertThat(ToolArguments.validate(spec.parameters(), Map.of())).isEmpty();
        assertThat(ToolArguments.validate(spec.parameters(), Map.of("question", "je tiens quoi ?")))
                .containsEntry("question", "je tiens quoi ?");
        assertThat(spec.parameters().get("required")).isEqualTo(List.of());
    }

    @Test
    void theResultSaysWhichCameraAndWhatItShowedInFewWords() {
        assertThat(Look.result("computer", 1280, 720, "  A mug held in a hand.  ")).containsExactly(
                Map.entry("camera", "the computer's camera, in front of the person"),
                Map.entry("picture", "1280x720, taken just now"), Map.entry("seen", "A mug held in a hand."));
        assertThat(Look.result("robot", 640, 480, "")).containsEntry("camera", "your own camera, on the robot")
                .containsEntry("seen", "nothing could be made out");
        assertThat((String) Look.result("computer", 10, 10, "x".repeat(2000)).get("seen")).hasSize(Look.MAX_DESCRIPTION).endsWith("…");
        assertThat(Look.visionMessage(" ")).isEqualTo("Describe what you see.");
        assertThat(Look.visionMessage("lis l'écran")).isEqualTo("The person asks: lis l'écran");
    }

    @Test
    void withACameraThePersonaSaysMarvinCanLookAndOnlyThen() {
        String blind = Persona.systemPrompt("fr", true, false, "");
        assertThat(Persona.systemPrompt("fr", true, false, false, "")).isEqualTo(blind);
        assertThat(blind).contains("it is not your camera, which you still cannot look at").doesNotContain("call look");
        String seeing = Persona.systemPrompt("fr", true, false, true, "");
        assertThat(seeing).contains("call look").contains("each look is a single picture")
                .doesNotContain("which you still cannot look at").doesNotContain("You cannot look at the camera image");
        // the rest of the persona is the same
        assertThat(seeing.replace(Persona.CAMERA, Persona.BLIND)).isEqualTo(blind);
        // no tools, no look: the camera sentence stays blind
        assertThat(Persona.systemPrompt("fr", false, false, true, "")).isEqualTo(Persona.systemPrompt("fr", false, false, ""));
        assertThat(Persona.systemPrompt(null, true, true, true, "The owner is Seb.")).contains("call look")
                .contains(Persona.MEMORY_TOOLS.strip()).endsWith("The owner is Seb.");
    }
}
