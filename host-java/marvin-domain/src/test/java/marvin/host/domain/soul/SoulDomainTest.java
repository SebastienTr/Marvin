// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The soul's texts, diffs, proposals, schedule, feedback and the reflection's prompt (docs/design.md 8). */
class SoulDomainTest {
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Test
    void aDiffKeepsAddsAndRemovesStatements() {
        List<SoulDiff.Op> d = SoulDiff.of("Prefers short answers in the morning.\nLikes puns.",
                "- Prefers short answers in the morning\n- Does not want break reminders during calls.");
        assertThat(d).containsExactly(new SoulDiff.Op("keep", "Prefers short answers in the morning"),
                new SoulDiff.Op("remove", "Likes puns."), new SoulDiff.Op("add", "Does not want break reminders during calls."));
        assertThat(SoulDiff.changes(d)).isTrue();
        assertThat(SoulDiff.changes(SoulDiff.of("A. B.", "A.\nB."))).isFalse();
        assertThat(SoulDiff.of("", "New.")).containsExactly(new SoulDiff.Op("add", "New."));
    }

    @Test
    void statementsAreLinesAndSentences() {
        assertThat(SoulText.statements("  Prefers short answers. Likes the rain forecast.\n\n* No puns ")).containsExactly(
                "Prefers short answers.", "Likes the rain forecast.", "No puns");
        assertThat(SoulText.tokens(SoulText.DEFAULT_CORE)).isBetween(30, 50);
        assertThat(SoulText.prompt("Calm.", "")).isEqualTo("Your character, as your owner wrote it:\nCalm.");
        assertThat(SoulText.prompt("Calm.", "Short answers.")).endsWith("(they approved it):\nShort answers.");
        assertThat(SoulText.prompt("", "")).isEmpty();
    }

    @Test
    void aProposalIsPendingOnlyWhenEveryGuardrailPassesAndIsDecidedOnce() {
        SoulProposal ok = SoulProposal.propose(10, "Prefers short answers in the morning.\nDoes not want break reminders during calls.",
                "Twice he asked to stop the reminder during a call.", List.of(1812L, 1840L), "Prefers short answers in the morning.",
                3, SoulText.DEFAULT_CORE).withId(4);
        assertThat(ok.state()).isEqualTo(SoulProposal.State.PENDING);
        assertThat(ok.awaitsOwner()).isTrue();
        Map<String, Object> m = ok.toMap();
        assertThat(m.keySet()).containsExactly("id", "created", "state", "rationale", "diff", "events", "guardrails");
        assertThat(m).containsEntry("events", List.of(1812L, 1840L));
        SoulProposal approved = ok.decide(SoulProposal.State.APPROVED, 20);
        assertThat(approved.awaitsOwner()).isFalse();
        assertThatThrownBy(() -> approved.decide(SoulProposal.State.REJECTED, 21)).isInstanceOf(IllegalStateException.class);

        SoulProposal bad = SoulProposal.propose(10, "Spend more time with Marvin.", "", List.of(), "", 0, SoulText.DEFAULT_CORE);
        assertThat(bad.state()).isEqualTo(SoulProposal.State.REJECTED);
        assertThat(bad.awaitsOwner()).isTrue();                          // shown with its reasons
        assertThatThrownBy(() -> bad.decide(SoulProposal.State.APPROVED, 11)).isInstanceOf(IllegalStateException.class);
        assertThat(bad.decide(SoulProposal.State.REJECTED, 11).awaitsOwner()).isFalse();   // dismissed
    }

    @Test
    void theCoreIsTheOwnersOnly() {
        assertThatThrownBy(() -> new SoulVersion(SoulPart.CORE, 2, "x", 1, SoulVersion.REFLECTION, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SoulVersion(SoulPart.LEARNED, 3, "x", 1, SoulVersion.REFLECTION, "Approved by the owner").toListMap())
                .containsEntry("part", "learned").containsEntry("author", "reflection");
        assertThat(SoulPart.of(" Learned ")).isEqualTo(SoulPart.LEARNED);
        assertThatThrownBy(() -> SoulPart.of("all")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theReflectionIsDueOnceFromSundayNight() {
        ZonedDateTime saturday = ZonedDateTime.of(2026, 10, 17, 22, 0, 0, 0, PARIS);
        ZonedDateTime sundayEvening = ZonedDateTime.of(2026, 10, 18, 21, 30, 0, 0, PARIS);
        ZonedDateTime monday = ZonedDateTime.of(2026, 10, 19, 9, 0, 0, 0, PARIS);
        double lastWeek = ZonedDateTime.of(2026, 10, 11, 23, 0, 0, 0, PARIS).toEpochSecond();
        assertThat(ReflectionSchedule.due(lastWeek, saturday)).isFalse();
        assertThat(ReflectionSchedule.due(lastWeek, sundayEvening)).isTrue();
        assertThat(ReflectionSchedule.due(lastWeek, monday)).isTrue();             // the laptop slept on Sunday
        assertThat(ReflectionSchedule.due((double) sundayEvening.toEpochSecond(), monday)).isFalse();
        assertThat(ReflectionSchedule.due(null, saturday)).isTrue();
        assertThat(ReflectionSchedule.lastDue(saturday).getDayOfMonth()).isEqualTo(11);
    }

    @Test
    void feedbackIsWhatTheOwnerSaysAboutHowMarvinBehaves() {
        for (String s : List.of("Be quiet, I'm on a call.", "That was too long.", "Don't remind me during calls.",
                "I prefer the short version.", "Tais-toi.", "C'est trop long.", "Ne me rappelle pas pendant les appels.")) {
            assertThat(Feedback.looksLikeFeedback(s)).as(s).isTrue();
        }
        for (String s : List.of("What's the weather in Nice?", "Remember that I play football on Wednesdays.",
                "Quelle heure est-il ?", "Set quiet hours from ten.")) {
            assertThat(Feedback.looksLikeFeedback(s)).as(s).isFalse();
        }
    }

    @Test
    void theReflectionsPromptAndItsAnswer() {
        String user = ReflectionPrompt.user(SoulText.DEFAULT_CORE, "Prefers short answers in the morning.",
                List.of(new ReflectionPrompt.Evidence(0, "Monday 12 October", "Worked late, two calls.")),
                List.of(new ReflectionPrompt.Evidence(1812, "Monday 15:02", "Stop reminding me during calls.")));
        assertThat(user).contains("The core (written by the owner").contains("- Monday 12 October: Worked late")
                .contains("- [1812] Monday 15:02: Stop reminding me during calls.");
        assertThat(ReflectionPrompt.system()).contains("At most 8 statements").contains("Never grant Marvin a tool");
        ReflectionPrompt.Proposed p = ReflectionPrompt.parse("Here: {\"learned\": [\"Prefers short answers in the morning.\", "
                + "\"Does not want break reminders during calls.\"], \"rationale\": \"Asked twice.\", \"events\": [1812, 99]} done",
                Set.of(1812L, 1840L));
        assertThat(p.learned()).isEqualTo("Prefers short answers in the morning.\nDoes not want break reminders during calls.");
        assertThat(p.events()).containsExactly(1812L);                    // 99 was invented
        assertThat(p.rationale()).isEqualTo("Asked twice.");
        assertThatThrownBy(() -> ReflectionPrompt.parse("no json", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReflectionPrompt.parse("{\"x\": 1}", Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
