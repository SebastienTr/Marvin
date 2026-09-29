// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.port.out.MemoryContext;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ContextAssembler;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.shared.TokenEstimator;

/**
 * Memory on the voice's path (docs/design.md 5.3): the profile in a system prompt that stays byte-identical between
 * questions, the question's memory sections cut to their budgets by score in the last message, the facts sent marked
 * used, the search started on the speculative transcript and bounded in time, the audience, the reply inspector's
 * report, the token estimate calibrated from the model server's counts, and what memory costs the voice.
 */
class VoiceMemoryTest {
    final List<VoiceService> services = new ArrayList<>();
    final VoiceServiceTest.Sidecar sidecar = new VoiceServiceTest.Sidecar();
    final VoiceServiceTest.Store store = new VoiceServiceTest.Store();
    final VoiceServiceTest.Settings settings = new VoiceServiceTest.Settings();
    final VoiceServiceTest.Clock clock = new VoiceServiceTest.Clock();
    final List<Map<String, Object>> transcript = new CopyOnWriteArrayList<>();
    volatile PresenceState state = new PresenceState(3_600_000_000L, true, true, null, null, 0.85, 0, 0, 720, null, null,
            false, false, 1);

    @AfterEach
    void close() {
        services.forEach(VoiceService::close);
    }

    /** A scripted memory. */
    static final class Memory implements MemoryContext {
        volatile Profile profile = new Profile(7, "The owner is called Sam.\nSam works from home as a developer.");
        volatile List<ContextAssembler.Item> facts = List.of();
        volatile List<ContextAssembler.Item> gist = List.of();
        volatile long delayMs;
        final List<String> questions = new CopyOnWriteArrayList<>();
        final List<Audience> audiences = new CopyOnWriteArrayList<>();
        final List<Collection<String>> used = new CopyOnWriteArrayList<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Profile profile() {
            return profile;
        }

        @Override
        public Recollection recollect(String question, Audience audience) {
            questions.add(question);
            audiences.add(audience);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new Recollection(gist, facts, Map.of("embed", 0.02, "search", 0.004), "");
        }

        @Override
        public void used(Collection<String> keys) {
            used.add(List.copyOf(keys));
        }

        @Override
        public ToolAnswer remember(String statement, Audience audience) {
            return ToolAnswer.ok(Map.of("remembered", statement));
        }

        @Override
        public ToolAnswer recall(String query, String period, Audience audience) {
            return ToolAnswer.ok(Map.of("facts", List.of()));
        }

        @Override
        public ToolAnswer forget(String query, String confirm, long turn, long said, Audience audience) {
            return ToolAnswer.ok(Map.of("matches", List.of()));
        }
    }

    VoiceService service(FakeModel model, MemoryContext memory) {
        PresenceQuery presence = new PresenceQuery() {
            @Override
            public PresenceState state() {
                return state;
            }

            @Override
            public List<PresenceEvent> recentEvents() {
                return List.of();
            }
        };
        VoiceService v = new VoiceService(sidecar, model, settings, (url, params, t) -> Map.of(), store, presence, clock,
                new LocalDays(ZoneId.of("Europe/Paris")), "linux", () -> false);
        v.setMemory(memory);
        v.addListener((kind, payload) -> {
            if (kind.equals("transcript")) {
                transcript.add(payload);
            }
        });
        services.add(v);
        return v;
    }

    VoiceService started(FakeModel model, MemoryContext memory) throws InterruptedException {
        VoiceService v = service(model, memory);
        v.start();
        VoiceServiceTest.waitFor(() -> v.snapshot().state().equals("on"));
        VoiceServiceTest.waitFor(() -> model.calls.size() >= 2);
        return v;
    }

    void ask(long uid, String text) {
        sidecar.signals.signal(new VoiceSidecar.Heard(uid, text, text, "fr", "voice", Map.of("endpoint", 0.5), clock.wall));
    }

    List<Map<String, Object>> replies() {
        return transcript.stream().filter(e -> "reply".equals(e.get("kind"))).toList();
    }

    static ContextAssembler.Item fact(String id, String text, double score) {
        return new ContextAssembler.Item(id, text, score, Map.of("similarity", 0.7));
    }

    // ------------------------------------------------------------------ the prompt

    @Test
    void theSystemPromptCarriesTheProfileAndStaysByteIdenticalBetweenQuestions() throws InterruptedException {
        Memory memory = new Memory();
        memory.facts = List.of(fact("11111111-1111-1111-1111-111111111111", "Sam's sister Claire lives in Lyon.", 2.0));
        FakeModel model = FakeModel.of("w", "w", "Hello Sam.", "Claire lives in Lyon.");
        VoiceService v = started(model, memory);
        ask(1, "Hello");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        ask(2, "Where does my sister live?");
        VoiceServiceTest.waitFor(() -> replies().size() == 2);

        List<ChatMessage> warm = model.calls.get(0);
        List<ChatMessage> first = model.calls.get(2);
        List<ChatMessage> second = model.calls.get(3);
        String system = first.getFirst().content();
        assertThat(system).contains("What you know about your owner").endsWith("Sam works from home as a developer.")
                .contains("Your memory: call remember");
        assertThat(second.getFirst().content()).as("byte for byte").isEqualTo(system);
        assertThat(warm.getFirst().content()).as("the warm-up caches the same system prompt").isEqualTo(system);
        assertThat(model.tools.get(2)).as("the same tool list object every time").isSameAs(model.tools.get(3));
        assertThat(toolNames(model.tools.get(2))).containsExactly("forget", "get_weather", "recall", "remember");
        // the second request starts with the first one's messages: the history only grows at the end (prompt cache)
        assertThat(second.subList(0, first.size())).isEqualTo(first);
        // memory is in the last user message only
        String last = second.getLast().content();
        assertThat(last).contains(Persona.FACTS_HEADING).contains("- Sam's sister Claire lives in Lyon.")
                .endsWith("The person says: Where does my sister live?\n\n(Answer in French.)");
        assertThat(system).doesNotContain("Claire");
        assertThat(memory.used).contains(List.of("11111111-1111-1111-1111-111111111111"));
        v.stop();
    }

    @Test
    void anOwnerWhoSwitchesLanguagesKeepsTheSameSystemPrompt() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Bonjour.", "Hello.");
        VoiceService v = started(model, new Memory());
        ask(1, "Bonjour");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Hello there", "Hello there", "en", "voice", Map.of("endpoint", 0.5),
                clock.wall));
        VoiceServiceTest.waitFor(() -> replies().size() == 2);
        String system = model.calls.get(2).getFirst().content();
        assertThat(model.calls.get(3).getFirst().content()).as("byte for byte across languages").isEqualTo(system);
        assertThat(model.calls.get(0).getFirst().content()).as("the warm-up's too").isEqualTo(system);
        assertThat(system).doesNotContain("Answer in French").doesNotContain("Answer in English");
        // the question's message names its language
        assertThat(model.calls.get(2).getLast().content()).endsWith("(Answer in French.)");
        assertThat(model.calls.get(3).getLast().content()).endsWith("(Answer in English.)");
        v.stop();
    }

    @SuppressWarnings("unchecked")
    static List<String> toolNames(List<Map<String, Object>> tools) {
        return tools.stream().map(t -> (String) ((Map<String, Object>) t.get("function")).get("name")).toList();
    }

    @Test
    void aNewProfileVersionChangesTheSystemPromptOnlyThen() throws InterruptedException {
        Memory memory = new Memory();
        FakeModel model = FakeModel.of("w", "w", "a", "b", "c");
        started(model, memory);
        ask(1, "one");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        memory.profile = new MemoryContext.Profile(8, "The owner is called Sam.\nSam moved to Lille.");
        ask(2, "two");
        VoiceServiceTest.waitFor(() -> replies().size() == 2);
        ask(3, "three");
        VoiceServiceTest.waitFor(() -> replies().size() == 3);
        assertThat(model.calls.get(3).getFirst().content()).endsWith("Sam moved to Lille.")
                .isNotEqualTo(model.calls.get(2).getFirst().content());
        assertThat(model.calls.get(4).getFirst().content()).isEqualTo(model.calls.get(3).getFirst().content());
    }

    @Test
    void withoutMemoryThePromptIsThePersonaAsBefore() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Hi.");
        started(model, MemoryContext.NONE);
        ask(1, "Hello");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        List<ChatMessage> q = model.calls.get(2);
        assertThat(q.getFirst().content()).isEqualTo(Persona.personaPrompt("fr", true));
        assertThat(q.getLast().content()).startsWith("Context:\n- It is ").doesNotContain("memory");
        assertThat(toolNames(model.tools.get(2))).containsExactly("get_weather");
        assertThat(replies().getFirst()).doesNotContainKey("memory");
    }

    // ------------------------------------------------------------------ budgets

    @Test
    void factsAreCutToTheirBudgetByScoreAndTheInspectorShowsWhatWasSent() throws InterruptedException {
        Memory memory = new Memory();
        List<ContextAssembler.Item> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // long lines, the best ones last: a cut by position would keep the worst
            many.add(fact("fact-" + i, "Fact number " + i + " is a long sentence about the owner's life that takes room "
                    + "in the prompt, with enough words to cost tokens.", i / 10.0));
        }
        memory.facts = many;
        memory.gist = List.of(fact("episode:2026-09-28:0", "Yesterday: The owner worked late and talked about the trip.", 0.5));
        FakeModel model = FakeModel.of("w", "w", "Sure.");
        started(model, memory);
        ask(1, "Tell me something");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);

        String last = model.calls.get(2).getLast().content();
        assertThat(last).contains("Fact number 29 ").contains("Fact number 28 ").doesNotContain("Fact number 0 ");
        // the memory sections share one budget: the day's line scored below the facts, and the budget was full
        assertThat(last).doesNotContain(Persona.GIST_HEADING).doesNotContain("Yesterday: The owner worked late");
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) replies().getFirst().get("memory");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) report.get("sections");
        assertThat(sections).extracting(s -> s.get("name")).containsExactly("now", "today", "facts");
        Map<String, Object> facts = sections.get(2);
        assertThat((int) facts.get("tokens")).isLessThanOrEqualTo(ContextAssembler.FACTS_BUDGET).isGreaterThan(200);
        assertThat((int) facts.get("tokens") + (int) sections.get(1).get("tokens")).isLessThanOrEqualTo(ContextAssembler.MEMORY_BUDGET);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) facts.get("items");
        List<String> kept = items.stream().filter(i -> Boolean.TRUE.equals(i.get("kept"))).map(i -> (String) i.get("key")).toList();
        assertThat(kept).contains("fact-29", "fact-28").doesNotContain("fact-0");
        assertThat(items).hasSize(30);
        assertThat(memory.used).containsExactly(kept);
        // the estimate of what was sent agrees with the text
        TokenEstimator est = TokenEstimator.DEFAULT;
        int lines = kept.size();
        assertThat(est.estimate(last)).isGreaterThan(lines * 20);
        assertThat(((Map<String, Object>) report.get("profile"))).containsEntry("version", 7L);
    }

    // ------------------------------------------------------------------ latency

    @Test
    void theSearchStartsOnTheSpeculativeTranscriptAndIsReused() throws InterruptedException {
        Memory memory = new Memory();
        FakeModel model = FakeModel.of("w", "w", "a", "b");
        started(model, memory);
        sidecar.signals.signal(new VoiceSidecar.Partial(1, "Where is my sister"));
        sidecar.signals.signal(new VoiceSidecar.Partial(1, "Where is my sister"));
        VoiceServiceTest.waitFor(() -> memory.questions.size() == 1);
        ask(1, "Where is my sister");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        assertThat(memory.questions).containsExactly("Where is my sister");

        sidecar.signals.signal(new VoiceSidecar.Partial(2, "What time"));
        VoiceServiceTest.waitFor(() -> memory.questions.size() == 2);
        ask(2, "What time is it?");                         // the final words differ: searched again
        VoiceServiceTest.waitFor(() -> replies().size() == 2);
        assertThat(memory.questions).containsExactly("Where is my sister", "What time", "What time is it?");
    }

    @Test
    void aSlowMemoryIsLeftBehindAfterItsBudget() throws InterruptedException {
        Memory memory = new Memory();
        memory.delayMs = 2000;
        memory.facts = List.of(fact("f", "A fact that comes too late.", 1));
        FakeModel model = FakeModel.of("w", "w", "Answer.");
        started(model, memory);
        long t0 = System.nanoTime();
        ask(1, "Anything?");
        VoiceServiceTest.waitFor(() -> model.calls.size() == 3);
        double waited = (model.callNanos.get(2) - t0) / 1e9;
        assertThat(waited).isLessThan(VoiceService.MEMORY_WAIT_S + 0.25);
        assertThat(model.calls.get(2).getLast().content()).doesNotContain("comes too late");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) replies().getFirst().get("memory");
        assertThat((String) report.get("problem")).startsWith("memory took longer than 300 ms");
    }

    @Test
    void aGuestComingInDropsTheHistoryWhichMayHoldPrivateFacts() throws InterruptedException {
        Memory memory = new Memory();
        memory.facts = List.of(fact("f", "Sam's doctor changed his treatment.", 2.0));
        FakeModel model = FakeModel.of("w", "w", "a", "b", "c");
        started(model, memory);
        ask(1, "alone");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        state = new PresenceState(3_600_000_000L, true, true, null, null, 0.85, 0, 0, 720, null, null, false, false, 2);
        memory.facts = List.of();
        ask(2, "with a guest");
        VoiceServiceTest.waitFor(() -> replies().size() == 2);
        assertThat(model.calls.get(3)).hasSize(2);          // the system prompt and this question only
        assertThat(model.calls.get(3).toString()).doesNotContain("doctor");
        ask(3, "still with the guest");
        VoiceServiceTest.waitFor(() -> replies().size() == 3);
        assertThat(model.calls.get(4)).hasSize(4);          // the history grows again from there
    }

    @Test
    void forgettingDropsTheHistoryEvenOfTheTurnInProgress() throws InterruptedException {
        Memory memory = new Memory();
        memory.facts = List.of(fact("f", "Julie lives in Oslo.", 2.0));
        FakeModel model = FakeModel.of("w", "w", "a", "b", "c");
        VoiceService v = started(model, memory);
        ask(1, "Where does Julie live?");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        v.clearHistory();                                   // the owner forgot it in the app
        memory.facts = List.of();
        ask(2, "Hello");
        VoiceServiceTest.waitFor(() -> replies().size() == 2);
        assertThat(model.calls.get(3)).hasSize(2);
        assertThat(model.calls.get(3).toString()).doesNotContain("Oslo");
    }

    @Test
    void aWarmUpAskedForDuringAQuestionWaitsUntilTheVoiceIsIdle() throws InterruptedException {
        Memory memory = new Memory();
        memory.delayMs = 200;                               // the question is still being prepared
        FakeModel model = FakeModel.of("w", "w", "Answer.", "w", "w");
        VoiceService v = started(model, memory);
        ask(1, "Anything?");
        v.rewarm();                                         // a memory pass ended, or the owner edited the profile
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        VoiceServiceTest.waitFor(() -> model.calls.size() == 5);
        assertThat(model.calls.get(2).getLast().content()).contains("Anything?");
        assertThat(model.calls.get(3).getLast().content()).contains("Bonjour.");
        assertThat(model.calls.get(4).getLast().content()).contains("Bonjour.");
    }

    @Test
    void sensitiveFactsAreAskedForOnlyWhenTheOwnerIsAlone() throws InterruptedException {
        Memory memory = new Memory();
        FakeModel model = FakeModel.of("w", "w", "a", "b");
        started(model, memory);
        ask(1, "alone");
        VoiceServiceTest.waitFor(() -> replies().size() == 1);
        state = new PresenceState(3_600_000_000L, true, true, null, null, 0.85, 0, 0, 720, null, null, false, false, 2);
        ask(2, "with a guest");
        VoiceServiceTest.waitFor(() -> replies().size() == 2);
        assertThat(memory.audiences).extracting(MemoryContext.Audience::othersPresent).containsExactly(false, true);
    }

    @Test
    void theTokenEstimateIsCalibratedFromWhatTheModelServerCounted() throws InterruptedException {
        Memory memory = new Memory();
        List<ContextAssembler.Item> facts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            facts.add(fact("f" + i, "The owner mentioned detail number " + i + " about the garden and the house plans.", 1));
        }
        memory.facts = facts;
        List<Object> script = new ArrayList<>(List.of("w", "w"));
        for (int i = 0; i < 12; i++) {
            script.add("Fine.");
        }
        FakeModel model = FakeModel.of(script.toArray());
        List<ChatMessage>[] previous = new List[] {List.of()};
        // a model server that counts 4.5 characters per token, and caches the prompt prefix it saw
        model.usage = messages -> {
            int shared = 0;
            while (shared < previous[0].size() && shared < messages.size() - 1 && previous[0].get(shared).equals(messages.get(shared))) {
                shared++;
            }
            int chars = 0;
            for (int i = shared; i < messages.size(); i++) {
                chars += messages.get(i).content().length();
            }
            previous[0] = List.copyOf(messages);
            int tokens = (int) Math.ceil(chars / 4.5) + 5 * (messages.size() - shared) + 3;
            return new LanguageModel.Usage(tokens, tokens / 800.0, 3, 0.05, 0);
        };
        VoiceService v = started(model, memory);
        for (int i = 1; i <= 12; i++) {
            ask(i, "Question number " + i + ", tell me about the garden and the plans for the house this autumn.");
            int n = i;
            VoiceServiceTest.waitFor(() -> replies().size() == n);
        }
        assertThat(v.tokenRatios()).containsKey("fr");
        assertThat(v.tokenRatios().get("fr")).isCloseTo(4.5, org.assertj.core.data.Offset.offset(0.4));
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) ((Map<String, Object>) replies().getLast().get("memory"))
                .get("usage");
        assertThat(usage).containsKeys("prompt_eval_count", "prompt_eval_s", "messages_reused");
        assertThat((int) usage.get("messages_reused")).isGreaterThan(1);
    }

    /**
     * What memory costs the voice, measured: the time from a question heard to the model's request, without memory and
     * with a memory that answers at once with a full set of candidates (30 facts, a day's gist). The difference is the
     * assembler's work (the search itself runs while the question is recognised; its budget is tested above).
     */
    @Test
    void memoryCostsTheVoiceMicrosecondsBeforeTheModelIsAsked() throws InterruptedException {
        Memory memory = new Memory();
        List<ContextAssembler.Item> facts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            facts.add(fact("f" + i, "The owner's fact number " + i + " says something short about their week.", 3 - i / 10.0));
        }
        memory.facts = facts;
        memory.gist = List.of(fact("g0", "Yesterday: The owner worked from home and went for a run in the evening.", 0.5),
                fact("g1", "Yesterday: They talked about the trip to Oslo next month.", 0.4));
        double without = medianDelay(MemoryContext.NONE);
        double with = medianDelay(memory);
        System.out.printf("voice path, heard to model request (median of 40): without memory %.3f ms, with memory %.3f ms%n",
                without * 1000, with * 1000);
        assertThat(with - without).as("memory's cost on the voice's thread").isLessThan(0.010);
    }

    private double medianDelay(MemoryContext memory) throws InterruptedException {
        transcript.clear();
        List<Object> script = new ArrayList<>(List.of("w", "w"));
        for (int i = 0; i < 50; i++) {
            script.add("Ok.");
        }
        FakeModel model = FakeModel.of(script.toArray());
        VoiceService v = started(model, memory);
        List<Double> delays = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        for (int i = 1; i <= 50; i++) {
            if (memory != MemoryContext.NONE) {
                Memory m = (Memory) memory;
                int expect = m.questions.size() + 1;
                sidecar.signals.signal(new VoiceSidecar.Partial(i, "What did I do yesterday " + i));
                VoiceServiceTest.waitFor(() -> m.questions.size() >= expect);
            }
            long t0 = System.nanoTime();
            ask(i, "What did I do yesterday " + i);
            int calls = i + 2;
            VoiceServiceTest.waitFor(() -> model.callNanos.size() >= calls);
            if (i > 10) {
                delays.add((model.callNanos.get(calls - 1) - t0) / 1e9);
            }
            int replies = n.incrementAndGet();
            VoiceServiceTest.waitFor(() -> replies().size() >= replies);
        }
        v.close();
        delays.sort(Double::compare);
        return delays.get(delays.size() / 2);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
