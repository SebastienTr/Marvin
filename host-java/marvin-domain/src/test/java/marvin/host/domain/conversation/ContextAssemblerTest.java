// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.shared.LanguageTokens;
import marvin.host.domain.shared.TokenEstimator;

/** The question's sections and their budgets (docs/design.md 5.3), and the prompt parts memory adds to the persona. */
class ContextAssemblerTest {
    final TokenEstimator est = new TokenEstimator(4, 0);

    static ContextAssembler.Item item(String key, String text, double score) {
        return ContextAssembler.Item.of(key, text, score);
    }

    @Test
    void memorySectionsShareOneBudgetFilledByScore() {
        // 4 characters per token: "- " + 34 x + "\n" is 37 characters, 10 tokens; each heading ("h\n") 1 token
        ContextAssembler.Section today = new ContextAssembler.Section("today", "h", 100, List.of(
                item("d1", "x".repeat(34), 0.9), item("d2", "x".repeat(34), 0.2)));
        ContextAssembler.Section facts = new ContextAssembler.Section("facts", "h", 100, List.of(
                item("f1", "x".repeat(34), 0.8), item("f2", "x".repeat(34), 0.5), item("f3", "x".repeat(34), 0.1)));
        List<ContextAssembler.Cut> cuts = ContextAssembler.cutTogether(List.of(today, facts), 31, est);
        assertThat(cuts.get(0).kept()).extracting(ContextAssembler.Item::key).containsExactly("d1");
        assertThat(cuts.get(1).kept()).extracting(ContextAssembler.Item::key).containsExactly("f1");
        assertThat(cuts.get(0).tokens() + cuts.get(1).tokens()).isEqualTo(22);
        // a section's own budget still holds within the total
        List<ContextAssembler.Cut> wide = ContextAssembler.cutTogether(List.of(
                new ContextAssembler.Section("today", "h", 11, today.items()), facts), 1000, est);
        assertThat(wide.get(0).kept()).extracting(ContextAssembler.Item::key).containsExactly("d1");
        assertThat(wide.get(1).kept()).hasSize(3);
    }

    @Test
    void aSectionIsCutByScoreNeverByPositionAndKeepsItsOrder() {
        List<ContextAssembler.Item> items = List.of(
                item("a", "x".repeat(37), 0.1),         // 40 chars with "- " and the newline: 10 tokens
                item("b", "x".repeat(37), 0.9),
                item("c", "x".repeat(37), 0.5),
                item("d", "x".repeat(37), 0.7));
        ContextAssembler.Cut cut = ContextAssembler.cut(new ContextAssembler.Section("facts", "", 30, items), est);
        assertThat(cut.kept()).extracting(ContextAssembler.Item::key).containsExactly("b", "c", "d");
        assertThat(cut.dropped()).extracting(ContextAssembler.Item::key).containsExactly("a");
        assertThat(cut.tokens()).isEqualTo(30);
    }

    @Test
    void aLongLowValueLineDoesNotPushOutShortUsefulOnes() {
        List<ContextAssembler.Item> items = List.of(
                item("long", "y".repeat(200), 0.95),
                item("short1", "z".repeat(18), 0.9),
                item("short2", "z".repeat(18), 0.8));
        ContextAssembler.Cut cut = ContextAssembler.cut(new ContextAssembler.Section("s", "", 12, items), est);
        assertThat(cut.kept()).extracting(ContextAssembler.Item::key).containsExactly("short1", "short2");
    }

    @Test
    void theHeadingIsPaidOnlyWhenSomethingIsKept() {
        ContextAssembler.Section s = new ContextAssembler.Section("s", "Heading of twenty ch", 16,
                List.of(item("a", "x".repeat(37), 1)));
        ContextAssembler.Cut cut = ContextAssembler.cut(s, est);
        assertThat(cut.tokens()).isEqualTo(16);        // 10 for the line, 6 for the heading and its newline
        assertThat(ContextAssembler.render(cut)).isEqualTo("Heading of twenty ch\n- " + "x".repeat(37));
        ContextAssembler.Cut none = ContextAssembler.cut(new ContextAssembler.Section("s", "Heading", 5,
                List.of(item("a", "x".repeat(37), 1))), est);
        assertThat(none.isEmpty()).isTrue();
        assertThat(none.tokens()).isZero();
        assertThat(ContextAssembler.render(none)).isEmpty();
    }

    @Test
    void theReportListsKeptThenDroppedWithTheirDetails() {
        ContextAssembler.Cut cut = ContextAssembler.cut(new ContextAssembler.Section("facts", "", 10, List.of(
                new ContextAssembler.Item("f1", "x".repeat(37), 0.3333333, Map.of("similarity", 0.71)),
                new ContextAssembler.Item("f2", "x".repeat(37), 0.9, Map.of()))), est);
        Map<String, Object> r = cut.report();
        assertThat(r).containsEntry("name", "facts").containsEntry("budget", 10).containsEntry("tokens", 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertThat(items.get(0)).containsEntry("key", "f2").containsEntry("kept", true);
        assertThat(items.get(1)).containsEntry("key", "f1").containsEntry("kept", false).containsEntry("score", 0.333)
                .containsEntry("similarity", 0.71);
    }

    @Test
    void theNowSectionRendersExactlyTheContextBlock() {
        PresenceState st = new PresenceState(3_600_000_000L, true, true, null, null, 0.85, 0, 0, 720, 14.0, 66.0, true, true, 1);
        List<PresenceEvent> events = List.of(new PresenceEvent(EventKind.SAT_DOWN, 3_600_000_000L - 720_000_000L, "", Map.of()));
        LocalDateTime now = LocalDateTime.of(2026, 9, 29, 14, 3);
        String block = Persona.contextBlock(st, events, now, "Nice");
        ContextAssembler.Cut all = ContextAssembler.cut(new ContextAssembler.Section("now", Persona.NOW_HEADING,
                Integer.MAX_VALUE, Persona.contextItems(st, events, now, "Nice")), est);
        assertThat(ContextAssembler.render(all)).isEqualTo(block);
        assertThat(Persona.contextBlock(null, List.of(), now, "")).isEqualTo(ContextAssembler.render(ContextAssembler.cut(
                new ContextAssembler.Section("now", Persona.NOW_HEADING, Integer.MAX_VALUE,
                        Persona.contextItems(null, List.of(), now, "")), est)));
    }

    /**
     * The "now" section's budget holds the sensors' truth: with the brain's longest context (simulated sensors, seated,
     * vital signs, recent events, the home place), what goes first is the home place and the recent events, never
     * the clock or the line saying the sensors are simulated.
     */
    @Test
    void theNowBudgetKeepsTheClockAndTheSensorsTruth() {
        PresenceState st = new PresenceState(3_600_000_000L, true, true, null, null, 0.85, 0, 0, 720, 14.0, 66.0, true, true, 1);
        List<PresenceEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(new PresenceEvent(EventKind.SAT_DOWN, 3_600_000_000L - (i + 1) * 300_000_000L, "", Map.of()));
        }
        LocalDateTime now = LocalDateTime.of(2026, 9, 29, 14, 3);
        List<ContextAssembler.Item> items = Persona.contextItems(st, events, now, "Nice");
        ContextAssembler.Cut all = ContextAssembler.cut(new ContextAssembler.Section("now", Persona.NOW_HEADING,
                Integer.MAX_VALUE, items), TokenEstimator.DEFAULT);
        ContextAssembler.Cut cut = ContextAssembler.cut(new ContextAssembler.Section("now", Persona.NOW_HEADING,
                ContextAssembler.NOW_BUDGET, items), TokenEstimator.DEFAULT);
        System.out.printf("now section, longest case: %d tokens estimated before any calibration, %d kept%n",
                all.tokens(), cut.tokens());
        assertThat(cut.kept()).extracting(ContextAssembler.Item::key).contains("time", "simulated", "present");
        assertThat(cut.tokens()).isLessThanOrEqualTo(ContextAssembler.NOW_BUDGET);
    }

    @Test
    void withoutMemoryTheSystemPromptIsThePersonaByteForByte() {
        for (String lang : List.of("fr", "en")) {
            for (boolean tools : List.of(true, false)) {
                assertThat(Persona.systemPrompt(lang, tools, false, "")).isEqualTo(Persona.personaPrompt(lang, tools));
                assertThat(Persona.systemPrompt(lang, tools, true, null)).isEqualTo(tools
                        ? Persona.personaPrompt(lang, true) + Persona.MEMORY_TOOLS : Persona.personaPrompt(lang, false));
            }
        }
        String p = Persona.systemPrompt("fr", true, true, "  The owner is called Sam.\n");
        assertThat(p).endsWith(Persona.PROFILE_HEADING + "\nThe owner is called Sam.");
        assertThat(p).isEqualTo(Persona.systemPrompt("fr", true, true, "The owner is called Sam."));
    }

    @Test
    void withNoLanguageTheSystemPromptIsTheSameForEveryLanguage() {
        String any = Persona.systemPrompt(null, true, true, "The owner is called Sam.");
        assertThat(any).contains(Persona.ANY_LANGUAGE).doesNotContain("{language}").doesNotContain("Answer in French");
        // only the language line differs from a named language's
        assertThat(any.replace(Persona.ANY_LANGUAGE, "- Answer in French, the language you are spoken to in."))
                .isEqualTo(Persona.systemPrompt("fr", true, true, "The owner is called Sam."));
    }

    @Test
    void theContextJoinsOnlyTheSectionsThatKeptSomething() {
        assertThat(Persona.context(List.of("Context:\n- a", "", "Facts:\n- b"))).isEqualTo("Context:\n- a\n\nFacts:\n- b");
    }

    @Test
    void tokenEstimatesAreCalibratedPerLanguageAndIgnoreImplausibleCounts() {
        LanguageTokens t = LanguageTokens.initial();
        assertThat(t.of("fr")).isEqualTo(TokenEstimator.DEFAULT);
        for (int i = 0; i < 30; i++) {
            t = t.calibrated("fr", 4500, 1000).calibrated("en", 5000, 1000);
        }
        assertThat(t.of("fr").charsPerToken()).isCloseTo(4.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(t.of("en").charsPerToken()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(t.of("de")).isEqualTo(TokenEstimator.DEFAULT);
        assertThat(t.of("fr").margin()).isEqualTo(0.10);
        LanguageTokens same = t.calibrated("fr", 10_000, 10);       // 1000 characters per token: the cache hid most of it
        assertThat(same).isSameAs(t);
        assertThat(t.calibrated("fr", 100, 0)).isSameAs(t);
        assertThat(t.ratios().get("en")).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.01));
        // 10 % margin: 450 characters in French are about 100 tokens, estimated about 110
        assertThat(t.estimate("fr", "x".repeat(450))).isBetween(110, 111);
    }

    @Test
    void theHistoryKeepsItsTokenBudgetDroppingTheOlderHalfAtOnce() {
        ConversationMemory m = new ConversationMemory(8, 180, 100, s -> s.length() / 4);
        for (int i = 0; i < 3; i++) {
            m.remember("q".repeat(40), "a".repeat(40), List.of(), i);       // 20 tokens a turn
        }
        assertThat(m.tokens()).isEqualTo(60);
        m.remember("q".repeat(40), "a".repeat(40), List.of(), 4);
        m.remember("q".repeat(40), "a".repeat(40), List.of(), 5);
        assertThat(m.tokens()).isEqualTo(100);
        m.remember("q".repeat(40), "a".repeat(40), List.of(), 6);         // 120: the older half goes
        assertThat(m.tokens()).isEqualTo(60);
        assertThat(m.messages(7).getFirst().role()).isEqualTo("user");
        ConversationMemory big = new ConversationMemory(8, 180, 10, s -> s.length());
        big.remember("q".repeat(40), "a".repeat(40), List.of(), 1);
        assertThat(big.size()).as("the last turn stays whole").isEqualTo(2);
    }
}
