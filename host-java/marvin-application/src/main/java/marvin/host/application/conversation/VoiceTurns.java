// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.application.conversation.tools.MemoryTools;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.application.system.port.out.Tracing;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.ReasoningSettings;
import marvin.host.domain.conversation.SentenceSplitter;
import marvin.host.domain.conversation.SpeechLanguage;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.VoiceSettings.VoiceConfig;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskIntent;
import marvin.host.domain.conversation.tool.TaskTools;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.shared.PyNumbers;

/**
 * The questions and their answers (a collaborator of {@link VoiceService}): a question heard becomes a turn, answered
 * once the one before is over (the persona, the context, the history, the model streamed, the tools), streamed to the
 * voice sentence by sentence, then written down with its latency breakdown and what the model was given, and kept in
 * the history. One thought, one question: a question that continues earlier ones is answered alone (see
 * {@link VoiceService}).
 */
final class VoiceTurns {
    private static final Logger log = Logger.getLogger("marvin.voice");

    private final VoiceService v;
    private final AnswerLoop loop;
    private final Map<Long, Turn> turns = new ConcurrentHashMap<>();
    /** The last turns by utterance uid, finished or not: what a question that continues them needs to know. */
    private final Map<Long, Turn> recentTurns = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Turn> eldest) {
            return size() > 32;
        }
    });
    /** The last turn heard: each turn builds its messages only once the one before is over (Python's single worker). */
    private Turn lastTurn;
    /** When someone last asked something (monotonic seconds). */
    private volatile double lastTalkAt = Double.NEGATIVE_INFINITY;
    /** The last question answered, for "do it in the background" said after it. */
    private volatile String lastQuestion = "";
    /** Whether the last question was asked with someone else in the room. */
    private volatile boolean othersBefore;

    VoiceTurns(VoiceService v, AnswerLoop loop) {
        this.v = v;
        this.loop = loop;
    }

    /** A question is being answered or said. */
    boolean active() {
        return !turns.isEmpty();
    }

    /** The voice went off: every turn ends. */
    void cancelAll(String reason) {
        turns.values().forEach(t -> t.cancel(reason));
    }

    /** When someone last asked something (monotonic seconds; {@code -Infinity}: never). */
    double lastTalkAt() {
        return lastTalkAt;
    }

    /** One question and its answer. */
    final class Turn {
        final VoiceSidecar.Heard heard;
        /** The conversation entry of what was heard (written late for a question that continues others). */
        volatile long said;
        final long replyId;
        final long number = v.turnSeq.incrementAndGet();
        /** The memory candidates for this question, searched while it was still being recognised when possible. */
        volatile CompletableFuture<MemoryContext.Recollection> recollection;
        volatile boolean othersPresent;
        final CompletableFuture<VoiceSidecar.ReplySpoken> spoken = new CompletableFuture<>();
        /** Complete once this turn is over: said (or dropped) and remembered. */
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile boolean superseded;
        volatile boolean cancelled;
        volatile double cancelledAt;
        /** Why it was cancelled first ({@code merged}: a question that continues it follows). */
        volatile String cancelReason;
        /** The user message sent to the model, once built. */
        volatile String userMessage;
        /** It is in the history (and was the last question there when it was put). */
        volatile boolean remembered;
        /** A reply entry was written for it. */
        volatile boolean replied;
        /** The image the owner showed with it ({@code null}: none). */
        volatile VoiceService.PendingImage image;

        Turn(VoiceSidecar.Heard heard, long replyId) {
            this.heard = heard;
            this.replyId = replyId;
        }

        void cancel(String reason) {
            if (!cancelled) {
                cancelledAt = v.clocks.monotonicSeconds();
                cancelReason = reason;
            }
            cancelled = true;
        }

        boolean merged() {
            return cancelled && "merged".equals(cancelReason);
        }
    }

    void onHeard(VoiceSidecar.Heard h) {
        v.language = h.language() == null || h.language().isEmpty() ? v.language : h.language();
        boolean continues = !h.continues().isEmpty();
        Turn turn = new Turn(h, v.replyIds.getAndIncrement());
        turn.image = v.takeImage();
        for (long uid : h.continues()) {
            // a question said in several breaths keeps the image its first part took
            Turn t = recentTurns.get(uid);
            if (turn.image == null && t != null && t.image != null) {
                turn.image = t.image;
            }
        }
        if (!continues) {
            turn.said = heardEntry(h, turn.image).id();
        }
        recentTurns.put(h.uid(), turn);
        v.context.startRecollection(turn);
        Turn previous;
        synchronized (v.lock) {
            previous = lastTurn;
            lastTurn = turn;
        }
        if (previous != null) {
            previous.superseded = true;
        }
        turns.put(h.uid(), turn);
        lastTalkAt = v.clocks.monotonicSeconds();
        v.laneUpdate();                         // a task's request gives way to the question at once
        v.answers.execute(() -> {
            try {
                if (previous != null) {
                    previous.done.join();       // its answer (even cut short) is in the history first
                }
                if (continues) {
                    // after what the questions it continues left (a cut answer): one bubble, in order
                    turn.said = heardEntry(h, turn.image).id();
                }
                if (turn.cancelled) {
                    return;                     // dropped by the voice before its turn came: nothing to answer
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("utterance", Long.toString(h.uid()));
                attributes.put("source", h.source().isEmpty() ? "voice" : h.source());
                VoiceConfig c = v.config;
                attributes.put("audio_route", c == null ? "" : v.sidecarSettings(c).robot() ? "robot" : "computer");
                try (Tracing.Span span = v.tracing.start("marvin.question", attributes)) {
                    answer(turn, span);
                }
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "the answer failed", e);
            } finally {
                turns.remove(h.uid());
                turn.done.complete(null);
                v.maybeRewarm();
                v.laneUpdate();
            }
        });
    }

    /** The conversation entry of a question heard; with what it continues, when it continues earlier ones. */
    private ConversationEntry heardEntry(VoiceSidecar.Heard h, VoiceService.PendingImage image) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("language", h.language());
        d.put("source", h.source().isEmpty() ? "voice" : h.source());
        if (h.raw() != null && !h.raw().isEmpty()) {
            d.put("raw", h.raw());
        }
        if (!h.speaker().isEmpty()) {
            d.put("speaker", h.speaker());      // whose voice (a name, or unknown), and how sure
            d.put("speaker_score", h.speakerScore());
        }
        if (image != null) {
            d.put("image", image.image().describe());      // what it was, never the image
        }
        if (!h.continues().isEmpty()) {
            List<Long> replaces = new ArrayList<>();
            List<Long> goesOn = new ArrayList<>();
            for (long uid : h.continues()) {
                Turn t = recentTurns.get(uid);
                if (t != null && t.said != 0) {
                    (t.replied ? goesOn : replaces).add(t.said);
                }
            }
            d.put("joined", h.continues().size() + 1);
            d.put("replaces", replaces);        // their bubbles give way to this one: nothing was answered in between
            d.put("continues", goesOn);         // this one goes on after their (cut) answers
            log.info("joined " + (h.continues().size() + 1) + " utterances: " + h.text());
        }
        return v.transcript.entry("heard", h.wallTime() > 0 ? h.wallTime() : v.clocks.wallSeconds(), h.text(), d);
    }

    /** The voice cut or dropped the answer to the question {@code uid}. */
    void onInterrupted(long uid, String reason) {
        Turn t = turns.get(uid);
        if (t != null) {
            t.cancel(reason);
        }
    }

    /** The voice said (or was interrupted saying) the answer to the question {@code uid}. */
    void onSpoken(VoiceSidecar.ReplySpoken r) {
        Turn t = turns.get(r.utteranceUid());
        if (t != null) {
            t.spoken.complete(r);
        }
    }

    private void answer(Turn turn, Tracing.Span span) {
        VoiceSidecar.Heard h = turn.heard;
        VoiceConfig c = v.config != null ? v.config : VoiceSettings.config(v.settings());
        ToolRegistry reg = v.routing.currentTools();
        VoiceService.PendingImage image = turn.image;
        String modelName = image != null ? image.model() : c.llmModel();
        String key = c.ollamaHost() + " " + modelName;
        boolean offer = reg != null && reg.ollamaTools() != null && v.routing.takesTools(key);
        String lang = h.language() == null || h.language().isEmpty() ? v.language : h.language();
        double now = v.clocks.monotonicSeconds();
        if (turn.othersPresent && !othersBefore) {
            // a guest came in: the earlier questions' memory sections may hold what the guest must not hear
            v.clearHistory();
        }
        othersBefore = turn.othersPresent;
        long epoch = v.historyEpoch.get();
        List<ChatMessage> history;
        synchronized (v.memory) {
            for (long uid : h.continues()) {
                // a question whose answer this one cut: this one says it all again, the cut turn leaves the history
                Turn t = recentTurns.get(uid);
                if (t != null && t.remembered && t.userMessage != null) {
                    t.remembered = !v.memory.forgetLast(t.userMessage);
                }
            }
            history = v.memory.messages(now);
        }
        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli((long) (v.clocks.wallSeconds() * 1000)),
                v.days.zone());
        MemoryContext.Profile profile = v.routing.profile();
        TurnContext.Assembled asm = v.context.assemble(turn, lang, local, offer ? c.homePlace() : "");
        if (asm.recollection() != null) {
            // what memory cost this question, on its trace (docs/design.md 10.4)
            asm.recollection().timings().forEach((k, val) -> span.attribute("marvin.memory." + k + "_s", val));
            span.attribute("marvin.memory.waited_s", asm.waitedS());
            span.attribute("marvin.memory.timed_out", Boolean.toString(asm.timedOut()));
            span.attribute("marvin.memory.tokens", asm.estimatedTokens());
        }
        String context = asm.context();
        // background tasks: "do it in the background" starts one at once; finished ones are mentioned first; words that
        // usually announce work in several steps get a hint (the model decides)
        boolean taskTools = offer && reg != null && reg.get(TaskTools.START) != null;
        String explicitGoal = taskTools && TaskIntent.explicit(h.text()) ? TaskIntent.goal(h.text(), lastQuestion) : null;
        TaskService ts = v.tasks;
        List<Task> mention = ts != null && image == null ? ts.toMention() : List.of();
        List<String> taskNotes = new ArrayList<>();
        String finished = Persona.finishedTasksNote(mention);
        if (finished != null) {
            taskNotes.add(finished);
        }
        if (taskTools && explicitGoal == null && TaskIntent.likely(h.text())) {
            taskNotes.add(Persona.TASK_HINT);
        }
        String user = Persona.userMessage(h.text(), context, lang, notes(image != null ? Persona.IMAGE_NOTE : null, taskNotes));
        // the history keeps the text with a note, never the image (later questions do not send it again), nor the notes
        // about tasks (they were for this answer)
        String kept = image != null ? Persona.userMessage(h.text(), context, lang, Persona.IMAGE_SHOWN_NOTE)
                : taskNotes.isEmpty() ? user : Persona.userMessage(h.text(), context, lang);
        turn.userMessage = kept;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(v.routing.systemPrompt(lang, offer, reg, profile)));
        messages.addAll(history);
        messages.add(image != null ? ChatMessage.user(user, List.of(image.image().base64())) : ChatMessage.user(user));
        List<ChatMessage> previous = v.routing.replacePrompt(messages);
        if (!asm.usedFacts().isEmpty()) {
            v.memoryContext.used(asm.usedFacts());
        }
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(MemoryTools.TURN, turn.number);
        toolContext.put(MemoryTools.SAID, turn.said);
        toolContext.put(MemoryTools.OTHERS_PRESENT, turn.othersPresent);
        toolContext.put("source", h.source().isEmpty() ? "voice" : h.source());

        v.voice.send(new VoiceSidecar.ReplyStart(turn.replyId, lang, false, h.uid()));
        AnswerLoop.Speaker speaker = new AnswerLoop.Speaker() {
            @Override
            public void say(String sentence) {
                for (SpeechLanguage.Clause cl : SpeechLanguage.clauses(SpeechText.cleanForSpeech(sentence), lang)) {
                    v.voice.send(new VoiceSidecar.Text(turn.replyId, cl.text(), cl.language()));
                }
            }

            @Override
            public void filler(String text) {
                v.voice.send(new VoiceSidecar.Filler(turn.replyId, text, lang));
            }
        };
        AnswerLoop.Outcome out = explicitGoal != null ? startTask(reg, explicitGoal, lang, toolContext, speaker) : null;
        if (out == null) {
            LanguageModel.Thinking thinking = LanguageModel.Thinking.chat(ReasoningSettings.of(v.settings()).chat());
            out = loop.answer(c.ollamaHost(), modelName, messages, reg, offer, lang, c.maxToolRounds(),
                    VoiceService.MODEL_TIMEOUT_S, () -> turn.cancelled, speaker, () -> v.routing.noTools(key), toolContext,
                    thinking);
        }
        Map<String, Object> usage = v.context.usage(out, previous, messages, lang, span);
        if (out.failure() != null && out.saidAnswer().isEmpty() && !turn.cancelled) {
            // nothing was said: the voice says why, in a sentence of the persona's (it has no words of its own)
            String why = Persona.phrase("llm_down".equals(out.failure()) ? "llm_down" : "error", lang);
            v.voice.send(new VoiceSidecar.Text(turn.replyId, why, lang));
        }
        v.voice.send(new VoiceSidecar.ReplyEnd(turn.replyId, out.failure() == null ? "" : out.failure()));

        VoiceSidecar.ReplySpoken spoken = awaitSpoken(turn);
        if (spoken == null) {
            return;                             // dropped before it was said: nothing to record
        }
        boolean interrupted = turn.cancelled || spoken.interrupted();
        int joined = h.continues().isEmpty() ? 0 : h.continues().size() + 1;
        if (turn.merged() || interrupted && SpeechText.cleanForSpeech(spoken.text()).isEmpty()) {
            // the owner went on talking (merged), or cut it before Marvin said a word (Talk now or Stop while it
            // thought): this one is not remembered, and shows only if Marvin had started saying something. A question
            // that continues it replaces its bubble (nothing was answered in between)
            if (!SpeechText.cleanForSpeech(spoken.text()).isEmpty()) {
                reply(spoken.text(), lang, latency(h.latency(), out.latency(), spoken.latency()), true, out, context, user,
                        modelName, image, null, "", asm.report(profile, usage), joined);
                turn.replied = true;
            }
            return;
        }
        String said = out.saidAnswer();
        Map<String, Double> lat = latency(h.latency(), out.latency(), spoken.latency());
        lat.forEach((k, val) -> span.attribute("marvin.latency." + k, val));
        log.info(latencyLine(lat));
        span.attribute("marvin.language", lang);
        span.attribute("marvin.interrupted", Boolean.toString(turn.cancelled || spoken.interrupted()));
        if (out.failure() != null) {
            span.error(out.failure());
            reply(spoken.text(), lang, lat, false, out, context, user, modelName, image, out.failure(), out.hint(),
                    asm.report(profile, usage), joined);
            turn.replied = true;
            return;
        }
        synchronized (v.memory) {
            // cleared during this turn (something was forgotten, maybe by this very turn): its messages stay out
            boolean keep = v.historyEpoch.get() == epoch;
            if (keep && interrupted) {
                if (!said.isEmpty() || !out.exchange().isEmpty()) {
                    v.memory.remember(kept, (said + " …").strip(), out.exchange(), v.clocks.monotonicSeconds());
                    turn.remembered = true;
                }
            } else if (keep) {
                v.memory.remember(kept, said, out.exchange(), v.clocks.monotonicSeconds());
                turn.remembered = true;
            }
        }
        reply(spoken.text(), lang, lat, interrupted, out, context, user, modelName, image, null, "",
                asm.report(profile, usage), joined);
        turn.replied = true;
        lastQuestion = explicitGoal != null ? explicitGoal : h.text();
        if (!mention.isEmpty() && !interrupted && ts != null) {
            ts.mentioned(mention);
        }
    }

    /** The notes of a question's message, one per line ({@code null} when none). */
    private static String notes(String first, List<String> more) {
        List<String> all = new ArrayList<>();
        if (first != null) {
            all.add(first);
        }
        all.addAll(more);
        return all.isEmpty() ? null : String.join("\n", all);
    }

    /**
     * "Do it in the background": the task starts without asking the model, and Marvin says it will take care of it.
     * {@code null} when it could not start (tasks off, too many): the model answers as usual, and can say why.
     */
    private AnswerLoop.Outcome startTask(ToolRegistry reg, String goal, String lang, Map<String, Object> toolContext,
                                         AnswerLoop.Speaker speaker) {
        Map<String, Object> ctx = new LinkedHashMap<>(toolContext);
        ctx.put("language", lang);
        ToolResult r = reg.call(TaskTools.START, Map.of("goal", goal), ctx);
        String say = r.finalSay();
        if (say == null) {
            log.info("could not start the task asked for: " + r.error());
            return null;
        }
        List<String> said = SentenceSplitter.split(say);
        said.forEach(speaker::say);
        List<ChatMessage> exchange = List.of(
                ChatMessage.assistant("", List.of(new marvin.host.domain.conversation.ToolCall(TaskTools.START,
                        Map.of("goal", goal), null))),
                ChatMessage.tool(TaskTools.START, r.content()), ChatMessage.assistant(say));
        Map<String, Double> lat = new LinkedHashMap<>();
        lat.put("tools", r.seconds());
        return new AnswerLoop.Outcome(said, exchange, List.of(r.record()), null, "", lat);
    }

    private VoiceSidecar.ReplySpoken awaitSpoken(Turn turn) {
        while (true) {
            try {
                return turn.spoken.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                double grace = turn.superseded ? VoiceService.SUPERSEDED_GRACE_S : VoiceService.SPOKEN_GRACE_S;
                if (turn.cancelled && v.clocks.monotonicSeconds() - turn.cancelledAt > grace) {
                    return null;
                }
                if (v.closed || !v.opened) {
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ the reply and its latency

    /**
     * When the first word was heard after the person stopped talking (seconds, 2 decimals): the end of speech plus
     * the time to the answer's first sound on the speaker (the speaker's own start when the voice measured it, else
     * when the sound was queued); {@code null} when nothing was said.
     */
    static Double firstWord(Map<String, Double> lat) {
        Double heard = lat.containsKey("playback_start") ? lat.get("playback_start") : lat.get("audio_start");
        return heard == null ? null : PyNumbers.round(lat.getOrDefault("endpoint", 0.0) + heard, 2);
    }

    /**
     * Where the time went, one line for the log: end of speech, recognition, the model's first token, the first clause,
     * its synthesis, the speaker.
     */
    static String latencyLine(Map<String, Double> lat) {
        StringBuilder b = new StringBuilder();
        Double first = firstWord(lat);
        b.append("first word ").append(first == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f s", first))
                .append(" after the end of speech:");
        String[][] stages = {{"endpoint", "end of speech"}, {"stt", "recognition"}, {"llm_first_token", "model first token"},
                {"tools", "tools"}, {"first_chunk", "first clause"}, {"tts", "synthesis"}};
        for (String[] st : stages) {
            if (lat.containsKey(st[0])) {
                b.append(' ').append(st[1]).append(String.format(java.util.Locale.ROOT, " %.2f,", lat.get(st[0])));
            }
        }
        if (lat.containsKey("playback_start") && lat.containsKey("audio_start")) {
            b.append(String.format(java.util.Locale.ROOT, " speaker %.2f,", lat.get("playback_start") - lat.get("audio_start")));
        }
        String line = b.toString().replaceAll(",$", "");
        return lat.getOrDefault("speculative", 0.0) > 0 ? line + " (recognition started during the pause)" : line;
    }

    /** The latency breakdown: listening stages, then the model's, then the speaking stages (seconds, 3 decimals). */
    static Map<String, Double> latency(Map<String, Double> heard, Map<String, Double> core, Map<String, Double> spoken) {
        Map<String, Double> lat = new LinkedHashMap<>();
        List<String> order = List.of("endpoint", "queue", "stt", "speculative", "wake");
        for (String k : order) {
            if (heard.containsKey(k)) {
                lat.put(k, heard.get(k));
            }
        }
        heard.forEach(lat::putIfAbsent);
        core.forEach(lat::put);
        spoken.forEach((k, val) -> {
            if (!"reply_start".equals(k) && !("first_chunk".equals(k) && lat.containsKey(k))) {
                lat.put(k, val);
            }
        });
        Map<String, Double> out = new LinkedHashMap<>();
        lat.forEach((k, val) -> out.put(k, PyNumbers.round(val, 3)));
        return out;
    }

    private void reply(String text, String lang, Map<String, Double> lat, boolean interrupted, AnswerLoop.Outcome out,
                       String context, String prompt, String modelName, VoiceService.PendingImage image, String failure,
                       String hint, Map<String, Object> memoryReport, int joined) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("language", lang);
        d.put("latency", lat);
        d.put("first_word_s", firstWord(lat));
        d.put("interrupted", interrupted);
        d.put("proactive", false);
        d.put("error", failure);
        d.put("hint", hint);
        d.put("context", context);
        d.put("prompt", prompt);
        d.put("model", modelName);
        if (image != null) {
            d.put("image", image.info());      // what the model was shown (never the image) and which model it was
        }
        if (!out.calls().isEmpty()) {
            d.put("tools", out.calls());
        }
        if (memoryReport != null) {
            d.put("memory", memoryReport);
        }
        if (!out.thinking().isBlank()) {
            d.put("thinking", ToolResult.shorten(out.thinking().strip(), 6000));      // the inspector shows it, never spoken
        }
        if (joined > 1) {
            d.put("joined", joined);            // the question was said in this many breaths (the inspector says so)
        }
        v.transcript.entry("reply", v.clocks.wallSeconds(), text, d);
    }
}
