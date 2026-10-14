// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.port.out.MemoryContext;
import marvin.host.application.system.port.out.Tracing;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ContextAssembler;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.event.RoomState;
import marvin.host.domain.shared.LanguageTokens;
import marvin.host.domain.shared.PyNumbers;
import marvin.host.domain.shared.TokenEstimator;

/**
 * What a question's message says besides the question (a collaborator of {@link VoiceService}): the "now" section
 * (who is there, the room, the time, whose voice), memory's sections searched while the question was still being
 * recognised when possible and assembled within their budgets (docs/design.md 5.3), and what the model server
 * evaluated, which calibrates the token estimate of each language.
 */
final class TurnContext {
    private static final Logger log = Logger.getLogger("marvin.voice");

    /** Qwen's chat template: {@code <|im_start|>role\n ... <|im_end|>\n} per message, then the reply's opening. */
    static final int TEMPLATE_TOKENS_PER_MESSAGE = 5;
    static final int TEMPLATE_TOKENS_REPLY = 3;
    /** Shorter prompts than this give a ratio too noisy to learn from. */
    static final int CALIBRATION_MIN_CHARS = 200;

    private final VoiceService v;
    /** Memory searches started on a speculative transcript, by utterance. */
    private final Map<Long, Prefetch> prefetches = new ConcurrentHashMap<>();
    /** Characters per token by language, calibrated from the model server's counts. */
    private volatile LanguageTokens tokenEstimates = LanguageTokens.initial();

    TurnContext(VoiceService v) {
        this.v = v;
    }

    /** {@code text}'s tokens in {@code language}, as estimated so far. */
    int estimate(String language, String text) {
        return tokenEstimates.estimate(language, text);
    }

    Map<String, Double> tokenRatios() {
        return tokenEstimates.ratios();
    }

    // ------------------------------------------------------------------ memory, searched early

    /** A memory search started on a speculative transcript. */
    private record Prefetch(String text, boolean othersPresent, CompletableFuture<MemoryContext.Recollection> result) {
    }

    private static String normal(String text) {
        return text == null ? "" : String.join(" ", text.strip().toLowerCase(java.util.Locale.ROOT).split("\\s+"));
    }

    private RoomState room() {
        try {
            return v.room.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean othersPresent() {
        try {
            PresenceState st = v.presence.state();
            return st != null && st.targets() > 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private CompletableFuture<MemoryContext.Recollection> search(String text, boolean others) {
        MemoryContext m = v.memoryContext;
        return CompletableFuture.supplyAsync(() -> m.recollect(text, new MemoryContext.Audience(others, false)), v.answers);
    }

    /**
     * The speculative transcript of an utterance: its memory search starts now, overlapping the end of recognition;
     * the final transcript reuses it when the words are the same.
     */
    void prefetch(long uid, String text) {
        if (!v.memoryContext.available() || text == null || text.isBlank()) {
            return;
        }
        Prefetch p = prefetches.get(uid);
        if (p != null && p.text().equals(normal(text))) {
            return;
        }
        boolean others = othersPresent();
        prefetches.put(uid, new Prefetch(normal(text), others, search(text, others)));
        if (prefetches.size() > 16) {                   // utterances that were never heard (dropped, noise)
            prefetches.keySet().stream().sorted().limit(prefetches.size() - 8L).toList().forEach(prefetches::remove);
        }
    }

    /** The question's memory search: the speculative one when it searched the same words, else a new one. */
    void startRecollection(VoiceTurns.Turn turn) {
        Prefetch p = prefetches.remove(turn.heard.uid());
        if (!v.memoryContext.available()) {
            return;
        }
        boolean others = othersPresent();
        if (p != null && p.text().equals(normal(turn.heard.text())) && p.othersPresent() == others) {
            turn.othersPresent = p.othersPresent();
            turn.recollection = p.result();
            return;
        }
        turn.othersPresent = others;
        turn.recollection = search(turn.heard.text(), others);
    }

    // ------------------------------------------------------------------ the question's context

    /**
     * The question's context, assembled within its budgets (docs/design.md 5.3).
     *
     * @param usedFacts the facts sent (their keys)
     */
    record Assembled(String context, List<ContextAssembler.Cut> sections, MemoryContext.Recollection recollection,
                     double waitedS, boolean timedOut, List<String> usedFacts, int estimatedTokens) {

        /** What the reply inspector shows; {@code null} without memory. */
        Map<String, Object> report(MemoryContext.Profile profile, Map<String, Object> usage) {
            if (recollection == null) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("version", profile.version());
            p.put("chars", profile.text().length());
            m.put("profile", p);
            m.put("sections", sections.stream().map(ContextAssembler.Cut::report).toList());
            m.put("tokens", estimatedTokens);
            Map<String, Object> t = new LinkedHashMap<>();
            recollection.timings().forEach((k, val) -> t.put(k, PyNumbers.round(val, 4)));
            t.put("waited", PyNumbers.round(waitedS, 4));
            m.put("timings", t);
            String problem = timedOut ? "memory took longer than " + (int) (VoiceService.MEMORY_WAIT_S * 1000)
                    + " ms: answered without it" : recollection.problem();
            m.put("problem", problem);
            if (usage != null) {
                m.put("usage", usage);
            }
            return m;
        }
    }

    Assembled assemble(VoiceTurns.Turn turn, String lang, LocalDateTime local, String home) {
        TokenEstimator est = tokenEstimates.of(lang);
        CompletableFuture<MemoryContext.Recollection> f = turn.recollection;
        if (f == null) {
            // no memory: the "now" section as it always was (no budget without the memory sections beside it)
            String speaker = Persona.speakerFact(turn.heard.speaker());
            return new Assembled(Persona.contextBlock(v.presence.state(), v.presence.recentEvents(), local, home, room())
                    + (speaker == null ? "" : "\n- " + speaker), List.of(), null, 0, false, List.of(), 0);
        }
        double t0 = v.clocks.monotonicSeconds();
        MemoryContext.Recollection r;
        boolean timedOut = false;
        try {
            r = f.get((long) (VoiceService.MEMORY_WAIT_S * 1000), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            r = MemoryContext.Recollection.EMPTY;
            timedOut = true;
            log.warning("memory took longer than " + (int) (VoiceService.MEMORY_WAIT_S * 1000) + " ms: answering without it");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r = MemoryContext.Recollection.EMPTY;
        } catch (ExecutionException e) {
            log.log(Level.WARNING, "memory failed", e.getCause());
            r = MemoryContext.Recollection.EMPTY;
        }
        double waited = v.clocks.monotonicSeconds() - t0;
        List<ContextAssembler.Item> nowItems = new ArrayList<>(Persona.contextItems(v.presence.state(),
                v.presence.recentEvents(), local, home, room()));
        String speaker = Persona.speakerFact(turn.heard.speaker());
        if (speaker != null) {
            nowItems.add(ContextAssembler.Item.of("speaker", speaker, 0.95));     // whose voice: memory attributes by it
        }
        ContextAssembler.Cut now = ContextAssembler.cut(new ContextAssembler.Section("now", Persona.NOW_HEADING,
                ContextAssembler.NOW_BUDGET, nowItems), est);
        // the memory sections share one budget: it bounds what memory adds to every question's prompt evaluation
        List<ContextAssembler.Cut> mem = ContextAssembler.cutTogether(List.of(
                new ContextAssembler.Section("today", Persona.GIST_HEADING, ContextAssembler.GIST_BUDGET, r.gist()),
                new ContextAssembler.Section("facts", Persona.FACTS_HEADING, ContextAssembler.FACTS_BUDGET, r.facts())),
                v.memoryBudget, est);
        ContextAssembler.Cut gist = mem.get(0);
        ContextAssembler.Cut facts = mem.get(1);
        String context = Persona.context(List.of(ContextAssembler.render(now), ContextAssembler.render(gist),
                ContextAssembler.render(facts)));
        List<String> used = facts.kept().stream().map(ContextAssembler.Item::key).toList();
        return new Assembled(context, List.of(now, gist, facts), r, waited, timedOut, used,
                now.tokens() + gist.tokens() + facts.tokens());
    }

    /**
     * The model server's counts for the question's first request: logged, traced, shown in the reply inspector, and
     * used to calibrate the token estimate of the language. With the prompt cache, the count covers only what was
     * evaluated again: the messages after those the previous request shared with this one (each message also costs
     * the chat template's few tokens).
     */
    Map<String, Object> usage(AnswerLoop.Outcome out, List<ChatMessage> previous, List<ChatMessage> messages, String lang,
                              Tracing.Span span) {
        if (out.usage().isEmpty()) {
            return null;
        }
        LanguageModel.Usage u = out.usage().getFirst();
        int shared = 0;
        while (shared < previous.size() && shared < messages.size() - 1 && previous.get(shared).equals(messages.get(shared))) {
            shared++;
        }
        int chars = 0;
        for (int i = shared; i < messages.size(); i++) {
            chars += messages.get(i).content().length();
        }
        int templateTokens = TEMPLATE_TOKENS_PER_MESSAGE * (messages.size() - shared) + TEMPLATE_TOKENS_REPLY;
        if (shared > 0 && chars >= CALIBRATION_MIN_CHARS && u.promptEvalCount() > templateTokens) {
            tokenEstimates = tokenEstimates.calibrated(lang, chars, u.promptEvalCount() - templateTokens);
        }
        double rate = u.promptEvalSeconds() > 0 ? u.promptEvalCount() / u.promptEvalSeconds() : 0;
        int reused = shared;
        log.info(() -> "prompt: " + u.promptEvalCount() + " tokens evaluated in " + PyNumbers.fixed(u.promptEvalSeconds(), 3)
                + " s (" + PyNumbers.fixed(rate, 0) + " tokens/s), " + messages.size() + " messages, " + (messages.size()
                - reused) + " new");
        span.attribute("marvin.prompt_eval_count", u.promptEvalCount());
        span.attribute("marvin.prompt_eval_s", u.promptEvalSeconds());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prompt_eval_count", u.promptEvalCount());
        m.put("prompt_eval_s", PyNumbers.round(u.promptEvalSeconds(), 4));
        m.put("eval_count", u.evalCount());
        m.put("eval_s", PyNumbers.round(u.evalSeconds(), 4));
        m.put("load_s", PyNumbers.round(u.loadSeconds(), 4));
        m.put("messages_reused", shared);
        m.put("chars_evaluated", chars);
        return m;
    }
}
