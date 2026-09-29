// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ProfileTextTest {
    final TokenEstimator est = new TokenEstimator(4, 0);

    @Test
    void keptLinesComeFirstVerbatimAndTheModelsLinesAreCutToFit() {
        String model = "# Profile\nFacts:\n- The owner lives in Lille.\n* Call me Sam.\n2. The owner likes jazz.\n- The owner has a cat.";
        ProfileText.Enforced e = ProfileText.enforce(model, List.of("Call me Sam."), 12, est);
        assertThat(ProfileText.lines(e.text())).containsExactly("Call me Sam.", "The owner lives in Lille.");
        assertThat(e.dropped()).containsExactly("The owner likes jazz.", "The owner has a cat.");
        assertThat(e.tokens()).isLessThanOrEqualTo(12);
        ProfileText.Enforced tight = ProfileText.enforce(model, List.of("A kept line longer than the whole budget allows."), 3, est);
        assertThat(ProfileText.lines(tight.text())).containsExactly("A kept line longer than the whole budget allows.");
    }

    @Test
    void anOwnerEditKeepsWhatTheOwnerWrote() {
        String before = "The owner lives in Lyon.\nThe owner likes jazz.";
        String edited = "The owner lives in Lille.\nThe owner likes jazz.\nCall me Sam.";
        assertThat(ProfileText.keptAfterOwnerEdit(before, List.of(), edited))
                .containsExactly("The owner lives in Lille.", "Call me Sam.");
        assertThat(ProfileText.keptAfterOwnerEdit(edited, List.of("Call me Sam."), "Call me Sam.\nThe owner likes jazz."))
                .containsExactly("Call me Sam.");
    }

    @Test
    void aDiffShowsWhatChanged() {
        assertThat(ProfileText.diff("a\nb\nc", "a\nc\nd")).containsExactly(new ProfileText.DiffLine(' ', "a"),
                new ProfileText.DiffLine('-', "b"), new ProfileText.DiffLine(' ', "c"), new ProfileText.DiffLine('+', "d"));
    }

    @Test
    void aForgottenFactLeavesTheProfile() {
        String p = "The owner lives in Lille.\nThe owner's sister Julie lives in Nantes.\nThe owner likes jazz.";
        assertThat(ProfileText.withoutLinesLike(p, "Julie is the owner's sister and lives in Nantes."))
                .isEqualTo("The owner lives in Lille.\nThe owner likes jazz.");
        assertThat(ProfileText.withoutLinesLike(p, "The owner plays chess.")).isEqualTo(p);
    }
}
