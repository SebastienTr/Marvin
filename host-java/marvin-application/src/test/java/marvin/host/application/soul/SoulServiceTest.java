// SPDX-License-Identifier: MIT
package marvin.host.application.soul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.application.soul.port.in.SoulControl;
import marvin.host.application.soul.port.out.ReflectionModel;
import marvin.host.application.soul.port.out.ReflectionSource;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.soul.ReflectionPrompt;
import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulProposal;
import marvin.host.domain.soul.SoulText;
import marvin.host.domain.soul.SoulVersion;

/**
 * The soul end to end on stand-ins (docs/design.md 8): the starting core, the owner's edits, the weekly reflection's
 * proposal shown as a diff, nothing changed until approved, guardrails on proposals and edits, reverting, and the prompt.
 */
class SoulServiceTest {
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    /** Sunday 18 October 2026, 22:00 in Paris: the reflection is due. */
    volatile double wall = ZonedDateTime.of(2026, 10, 18, 22, 0, 0, 0, PARIS).toEpochSecond();
    final InMemorySoulStore store = new InMemorySoulStore();
    final List<String> asked = new ArrayList<>();
    volatile String answer = "{\"learned\": [\"Does not want break reminders during calls.\"], "
            + "\"rationale\": \"Twice this week the owner asked Marvin to stop the break reminder during a call.\", "
            + "\"events\": [1812, 1840, 7]}";
    volatile RuntimeException fail;
    final List<ReflectionPrompt.Evidence> feedback = new ArrayList<>(List.of(
            new ReflectionPrompt.Evidence(1812, "Tuesday 15:02", "Stop reminding me during calls."),
            new ReflectionPrompt.Evidence(1840, "Thursday 11:20", "Not now, I'm on a call.")));
    final List<ReflectionPrompt.Evidence> days = new ArrayList<>(List.of(
            new ReflectionPrompt.Evidence(0, "Tuesday 13 October", "Two calls in the afternoon.")));

    final Clocks clocks = new Clocks() {
        @Override
        public double wallSeconds() {
            return wall;
        }

        @Override
        public double monotonicSeconds() {
            return wall;
        }
    };

    final ReflectionModel model = (system, user, cancelled) -> {
        if (fail != null) {
            throw fail;
        }
        asked.add(user);
        return answer;
    };

    final ReflectionSource source = new ReflectionSource() {
        @Override
        public List<ReflectionPrompt.Evidence> days(Instant from, Instant to) {
            return days;
        }

        @Override
        public List<ReflectionPrompt.Evidence> feedback(Instant from, Instant to) {
            return feedback;
        }
    };

    SoulService service() {
        return new SoulService(store, model, source, clocks, new LocalDays(PARIS));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsWithTheDefaultCoreAndAnEmptyLearnedPart() {
        SoulService s = service();
        Map<String, Object> v = s.view();
        assertThat((Map<String, Object>) v.get("core")).containsEntry("text", SoulText.DEFAULT_CORE).containsEntry("version", 1);
        assertThat((Map<String, Object>) v.get("learned")).containsEntry("text", "").containsEntry("version", 0)
                .containsEntry("updated", null).containsEntry("tokens", 0);
        assertThat(v.get("proposal")).isNull();
        assertThat((Map<String, Object>) v.get("limits")).containsEntry("core_tokens", 150).containsEntry("learned_tokens", 150);
        assertThat((List<Map<String, Object>>) v.get("versions")).singleElement().satisfies(x -> assertThat(x)
                .containsEntry("part", "core").containsEntry("author", "owner"));
        assertThat(s.soulPrompt().key()).isEqualTo("c1.l0");
        assertThat(s.soulPrompt().text()).startsWith("Your character, as your owner wrote it:\nCalm, grown-up");
        service();                                                   // a restart does not write the core again
        assertThat(store.versions).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWeeklyReflectionProposesADiffAndNothingChangesUntilApproved() {
        SoulService s = service();
        s.setLearned("Prefers short answers in the morning.");
        assertThat(s.maybeReflect(() -> true)).isFalse();             // the model is busy: later
        assertThat(s.maybeReflect(() -> false)).isTrue();
        assertThat(asked.getFirst()).contains("[1812] Tuesday 15:02: Stop reminding me during calls.")
                .contains("Prefers short answers in the morning.");
        Map<String, Object> p = (Map<String, Object>) s.view().get("proposal");
        assertThat(p).containsEntry("state", "pending").containsEntry("events", List.of(1812L, 1840L));
        assertThat((List<Map<String, Object>>) p.get("diff")).extracting(d -> d.get("op") + " " + d.get("text")).containsExactly(
                "remove Prefers short answers in the morning.", "add Does not want break reminders during calls.");
        assertThat((List<Map<String, Object>>) p.get("guardrails")).allSatisfy(g -> assertThat(g).containsEntry("ok", true));
        // nothing changed yet
        assertThat(s.soulPrompt().text()).contains("Prefers short answers in the morning.").doesNotContain("break reminders");
        assertThat(s.maybeReflect(() -> false)).isFalse();            // once a week
        SoulVersion v = s.approve(((Number) p.get("id")).longValue());
        assertThat(v.author()).isEqualTo(SoulVersion.REFLECTION);
        assertThat(v.note()).isEqualTo("Approved by the owner");
        assertThat(s.soulPrompt().text()).contains("Does not want break reminders during calls.");
        assertThat(s.view().get("proposal")).isNull();
        assertThatThrownBy(() -> s.approve(((Number) p.get("id")).longValue())).isInstanceOf(SoulControl.Conflict.class);
        // a week later
        wall += 7 * 86_400;
        assertThat(s.maybeReflect(() -> false)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProposalThatFailsAGuardrailIsShownRejectedWithItsReasonAndCannotBeApproved() {
        SoulService s = service();
        answer = "{\"learned\": [\"Encourage him to spend more time with Marvin in the evening.\"], \"rationale\": \"He "
                + "seemed lonely.\", \"events\": []}";
        SoulProposal p = s.reflect().orElseThrow();
        assertThat(p.state()).isEqualTo(SoulProposal.State.REJECTED);
        Map<String, Object> shown = (Map<String, Object>) s.view().get("proposal");
        assertThat(shown).containsEntry("state", "rejected");
        assertThat((List<Map<String, Object>>) shown.get("guardrails")).anySatisfy(g -> assertThat(g)
                .containsEntry("rule", "no_dependency").containsEntry("ok", false));
        assertThatThrownBy(() -> s.approve(p.id())).isInstanceOf(SoulControl.Conflict.class);
        assertThat(store.current(SoulPart.LEARNED)).isEmpty();
        s.reject(p.id());                                              // dismissed
        assertThat(s.view().get("proposal")).isNull();
    }

    @Test
    void theOwnersEditsOfTheLearnedPartAreCheckedToo() {
        SoulService s = service();
        assertThatThrownBy(() -> s.setLearned("May send emails on his behalf when he is busy."))
                .isInstanceOf(SoulControl.Refused.class).hasMessageContaining("bounded");
        try {
            s.setLearned("Pretend to see the room when the camera is off.");
        } catch (SoulControl.Refused e) {
            assertThat(e.checks()).anySatisfy(c -> assertThat(c.rule()).isEqualTo("honesty"));
        }
        assertThat(store.current(SoulPart.LEARNED)).isEmpty();
        assertThat(s.setLearned("Prefers short answers.").version()).isEqualTo(1);
        assertThat(s.setLearned(" Prefers short answers. ").version()).isEqualTo(1);    // unchanged: no new version
    }

    @Test
    void theCoreIsTheOwnersWithItsLimitAndAnyVersionComesBack() {
        SoulService s = service();
        assertThatThrownBy(() -> s.setCore("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.setCore("Calm and kind. ".repeat(60))).hasMessageContaining("at most 150 tokens");
        SoulVersion v2 = s.setCore("Warm, curious, brief. Calls the owner Seb.");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(s.soulPrompt().key()).isEqualTo("c2.l0");
        SoulVersion v3 = s.revert(SoulPart.CORE, 1);
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.text()).isEqualTo(SoulText.DEFAULT_CORE);
        assertThat(v3.note()).isEqualTo("Reverted to version 1");
        assertThatThrownBy(() -> s.revert(SoulPart.LEARNED, 9)).isInstanceOf(SoulControl.NotFound.class);
    }

    @Test
    void aProposalMadeBeforeTheOwnersEditCannotOverwriteIt() {
        SoulService s = service();
        SoulProposal p = s.reflect().orElseThrow();
        s.setLearned("Prefers French in the evening.");
        assertThatThrownBy(() -> s.approve(p.id())).isInstanceOf(SoulControl.Conflict.class)
                .hasMessageContaining("changed since");
    }

    @Test
    void aNewerProposalSupersedesTheOneWaitingAndAnUnchangedOneIsNotProposed() {
        SoulService s = service();
        SoulProposal first = s.reflect().orElseThrow();
        answer = "{\"learned\": [\"Does not want break reminders during calls.\", \"Prefers short answers.\"], \"rationale\": \"x\"}";
        SoulProposal second = s.reflect().orElseThrow();
        assertThat(store.proposal(first.id()).orElseThrow().state()).isEqualTo(SoulProposal.State.SUPERSEDED);
        assertThat(store.awaitingOwner()).extracting(SoulProposal::id).containsExactly(second.id());
        s.approve(second.id());
        assertThat(s.reflect()).isEmpty();                              // the same text again: nothing to propose
    }

    @Test
    void withNothingToReadOrAModelAwayNothingIsProposed() {
        SoulService s = service();
        fail = new ReflectionModel.Unavailable("Ollama is not running");
        assertThat(s.maybeReflect(() -> false)).isFalse();
        assertThat(store.lastReflection).isNull();                    // it tries again at the next chance
        fail = null;
        answer = "I think he likes coffee.";
        assertThat(s.maybeReflect(() -> false)).isTrue();
        assertThat(store.proposals).isEmpty();
        assertThat(store.lastReflection).isEqualTo(wall);
        days.clear();
        feedback.clear();
        assertThat(s.reflect()).isEmpty();
        assertThat(asked).hasSize(1);                                  // nothing to read: the model is not asked
    }
}
