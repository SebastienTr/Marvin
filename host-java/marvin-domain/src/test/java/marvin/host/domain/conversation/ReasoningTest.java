// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The reasoning levels, what Ollama is sent for each model, and their settings. */
class ReasoningTest {
    static final ModelThinking QWEN38 = ModelThinking.of(List.of("completion", "tools", "thinking", "vision"),
            Map.of("values", List.of(false, "low", "medium", "xhigh"), "default", "medium"));
    static final ModelThinking GPT_OSS = ModelThinking.of(List.of("completion", "thinking"),
            Map.of("values", List.of("low", "medium", "high"), "default", "medium"));
    static final ModelThinking QWEN3 = ModelThinking.of(List.of("completion", "thinking"),
            Map.of("values", List.of(false, true), "default", true));
    static final ModelThinking OLD_SERVER = ModelThinking.of(List.of("completion", "thinking"), null);
    static final ModelThinking NO_THINKING = ModelThinking.of(List.of("completion", "tools"), null);

    @Test
    void highIsTheStrongestBoundedLevelTheModelLists() {
        assertThat(Reasoning.HIGH.thinkValue(QWEN38)).isEqualTo("xhigh");     // "high" would be Ollama's default, medium
        assertThat(Reasoning.HIGH.thinkValue(GPT_OSS)).isEqualTo("high");
        assertThat(Reasoning.HIGH.thinkValue(QWEN3)).isEqualTo(true);
        assertThat(Reasoning.HIGH.thinkValue(OLD_SERVER)).isEqualTo("high");
        assertThat(Reasoning.HIGH.thinkValue(ModelThinking.UNKNOWN)).isEqualTo("high");
        assertThat(Reasoning.HIGH.thinkValue(ModelThinking.of(List.of("thinking"),
                Map.of("values", List.of("low", "max"), "default", "low")))).isEqualTo("max");
    }

    @Test
    void lowerLevelsAndOff() {
        assertThat(Reasoning.MEDIUM.thinkValue(QWEN38)).isEqualTo("medium");
        assertThat(Reasoning.LOW.thinkValue(QWEN38)).isEqualTo("low");
        assertThat(Reasoning.LOW.thinkValue(QWEN3)).isEqualTo(true);
        assertThat(Reasoning.MEDIUM.thinkValue(ModelThinking.of(List.of("thinking"),
                Map.of("values", List.of("fast", "deep"), "default", "fast")))).isEqualTo("fast");
        for (ModelThinking m : List.of(QWEN38, GPT_OSS, QWEN3, OLD_SERVER, ModelThinking.UNKNOWN)) {
            assertThat(Reasoning.OFF.thinkValue(m)).isEqualTo(false);
        }
        for (Reasoning r : Reasoning.values()) {
            assertThat(r.thinkValue(NO_THINKING)).isEqualTo(false);
        }
    }

    @Test
    void theModelsDescription() {
        assertThat(QWEN38.thinks()).isTrue();
        assertThat(QWEN38.levels()).containsExactly("low", "medium", "xhigh");
        assertThat(QWEN38.toMap()).containsEntry("default", "medium");
        assertThat(NO_THINKING.thinks()).isFalse();
        assertThat(ModelThinking.of(null, null).thinks()).isNull();
        assertThat(ModelThinking.of(List.of("thinking"), Map.of("values", List.of(false), "default", false)).thinks())
                .isFalse();
        assertThat(ModelThinking.of(List.of("thinking"), Map.of("values", List.of("", 3))).values()).isEmpty();
    }

    @Test
    void namesAndSettings() {
        assertThat(Reasoning.parse("high")).isEqualTo(Reasoning.HIGH);
        assertThat(Reasoning.parse(" Off ")).isEqualTo(Reasoning.OFF);
        assertThat(Reasoning.parse("xhigh")).isEqualTo(Reasoning.HIGH);
        assertThat(Reasoning.parse(true)).isEqualTo(Reasoning.HIGH);
        assertThat(Reasoning.parse("loud")).isNull();
        assertThat(Reasoning.parse(3)).isNull();

        assertThat(ReasoningSettings.of(Map.of())).isEqualTo(ReasoningSettings.DEFAULT);
        assertThat(ReasoningSettings.DEFAULT.toMap()).containsExactly(Map.entry("reasoning", "off"),
                Map.entry("task_reasoning", "high"), Map.entry("tasks", true));
        assertThat(ReasoningSettings.of(Map.of("reasoning", "low", "task_reasoning", "medium", "tasks", false)))
                .isEqualTo(new ReasoningSettings(Reasoning.LOW, Reasoning.MEDIUM, false));
        assertThat(ReasoningSettings.of(Map.of("reasoning", "nonsense", "tasks", "yes"))).isEqualTo(ReasoningSettings.DEFAULT);
        assertThat(ReasoningSettings.validate(Map.of("task_reasoning", "low", "tasks", false)))
                .containsEntry("task_reasoning", "low").containsEntry("tasks", false);
        assertThatThrownBy(() -> ReasoningSettings.validate(Map.of("reasoning", "max")))
                .isInstanceOf(VoiceSettings.InvalidVoiceSettingException.class)
                .hasMessage("reasoning must be one of off, low, medium, high");
        assertThatThrownBy(() -> ReasoningSettings.validate(Map.of("tasks", "on")))
                .hasMessage("tasks must be true or false");
        assertThatThrownBy(() -> ReasoningSettings.validate(Map.of("llm_model", "x")))
                .hasMessage("unknown setting: llm_model");
    }
}
