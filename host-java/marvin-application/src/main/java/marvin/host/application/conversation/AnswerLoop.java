// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.SentenceSplitter;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.conversation.tool.ToolResult;

/**
 * One answer: the model streamed, its tool calls run, what it writes cut into sentences for the voice (the
 * Python host's {@code VoiceAssistant._answer}, step for step).
 *
 * <p>The model is offered the tools switched on in the settings, the same list with every request. When it
 * calls some, nothing more of that response is spoken; an online tool gets a short filler while it runs, the
 * calls and their results are added to the conversation, and the model is asked again. At most
 * {@code maxToolRounds} rounds, then it must answer.
 *
 * <p>A response that starts like a tool call written as text is held back until it is complete; so is one
 * that answers a tool result (models may think aloud there). Reasoning written into the answer and closed by
 * {@code </think>} is dropped while it streams: a possible start of the tag is held back; at the tag, if
 * something was said, its sentence is finished and the repeat dropped, else what came before is dropped.
 */
public final class AnswerLoop {
    private static final Logger log = Logger.getLogger("marvin.voice");
    private static final String TAG = SpeechText.THINK_TAG;

    /** Where the answer goes, sentence by sentence. */
    public interface Speaker {
        void say(String sentence);

        void filler(String text);
    }

    /**
     * What happened.
     *
     * @param answer   the sentences the model said (fillers and error sentences aside)
     * @param exchange the tool calls and results, for the history
     * @param calls    the same, as the app's reply inspector shows them
     * @param failure  {@code llm_down}, {@code error}, or {@code null}
     * @param hint     how to fix a failure
     * @param latency  llm_first_token, first_chunk, tools, llm_first_token_2 (seconds)
     */
    public record Outcome(List<String> answer, List<ChatMessage> exchange, List<Map<String, Object>> calls,
                          String failure, String hint, Map<String, Double> latency) {

        /** What was said of the answer, cleaned for speech. */
        public String saidAnswer() {
            return SpeechText.cleanForSpeech(String.join(" ", answer));
        }
    }

    private final LanguageModel model;
    private final DoubleSupplier clock;

    public AnswerLoop(LanguageModel model, DoubleSupplier monotonicSeconds) {
        this.model = model;
        this.clock = monotonicSeconds;
    }

    /**
     * Answers. {@code tools} {@code null}: none offered. {@code supportsTools} is set to false when the model
     * says it cannot use tools (the request is then made again without them).
     */
    public Outcome answer(String host, String modelName, List<ChatMessage> messages, ToolRegistry tools,
                          boolean offerTools, String language, int maxToolRounds, double timeoutS,
                          BooleanSupplier cancelled, Speaker speaker, Runnable toolsUnsupported) {
        double t = clock.getAsDouble();
        Map<String, Double> lat = new LinkedHashMap<>();
        List<String> answer = new ArrayList<>();
        List<ChatMessage> exchange = new ArrayList<>();
        List<Map<String, Object>> calls = new ArrayList<>();
        String failure = null;
        String hint = "";
        List<Map<String, Object>> schemas = offerTools && tools != null ? tools.ollamaTools() : null;
        boolean filler = false;
        int rounds = 0;
        try {
            while (true) {
                Round r = new Round(t, rounds, lat, answer, speaker, cancelled);
                List<ChatMessage> request = new ArrayList<>(messages);
                request.addAll(exchange);
                try {
                    model.streamChat(host, modelName, request, schemas, timeoutS, r);
                } catch (LanguageModel.ToolsUnsupported e) {
                    log.warning(e.getMessage() + ": answering without tools");
                    schemas = null;
                    toolsUnsupported.run();
                    r = new Round(t, rounds, lat, answer, speaker, cancelled);
                    model.streamChat(host, modelName, request, null, timeoutS, r);
                }
                if (cancelled.getAsBoolean()) {
                    break;
                }
                String content = String.join("", r.text);
                Boolean held = r.held;
                if (held == null && !content.isBlank()) {       // too short to decide: treat it as text
                    held = true;
                }
                List<ToolCall> toolCalls = new ArrayList<>(r.toolCalls);
                if (Boolean.TRUE.equals(held)) {
                    for (Map<String, Object> raw : SpeechText.payloadToolCalls(content)) {
                        ToolCall c = ToolCall.parse(raw);
                        if (c != null) {
                            toolCalls.add(c);
                        }
                    }
                }
                if (!toolCalls.isEmpty() && tools != null && rounds < maxToolRounds) {
                    if (!filler && answer.isEmpty() && toolCalls.stream().anyMatch(c -> {
                        ToolRegistry.Tool tl = tools.get(c.name());
                        return tl != null && tl.spec().saysFiller();
                    })) {
                        speaker.filler(Persona.phrase("checking", language));
                        filler = true;
                    }
                    double tTools = clock.getAsDouble();
                    List<ToolResult> results = new ArrayList<>();
                    for (ToolCall c : toolCalls) {
                        results.add(tools.call(c.name(), c.arguments(), Map.of("language", language)));
                        if (cancelled.getAsBoolean()) {
                            return new Outcome(answer, exchange, calls, null, "", lat);
                        }
                    }
                    lat.merge("tools", clock.getAsDouble() - tTools, Double::sum);
                    exchange.add(ChatMessage.assistant(Boolean.TRUE.equals(held) ? "" : content, toolCalls));
                    for (int i = 0; i < toolCalls.size(); i++) {
                        exchange.add(ChatMessage.tool(toolCalls.get(i).name(), results.get(i).content()));
                        calls.add(results.get(i).record());
                    }
                    rounds++;
                    continue;
                }
                if (!toolCalls.isEmpty()) {
                    log.warning("ignoring " + toolCalls.size() + " more tool call(s) after " + rounds + " round(s)");
                }
                if (Boolean.TRUE.equals(held) && toolCalls.isEmpty()) {   // not a tool call after all: say what can be said
                    for (String s : r.splitter.feed(SpeechText.stripThinking(content))) {
                        r.say(s);
                    }
                }
                if (Boolean.FALSE.equals(held) && !r.cut && r.fed < content.length()) {   // a held-back "<" at the very end
                    for (String s : r.splitter.feed(content.substring(r.fed))) {
                        r.say(s);
                    }
                }
                for (String s : r.splitter.flush()) {
                    r.say(s);
                }
                if (SpeechText.cleanForSpeech(String.join(" ", answer)).isEmpty()
                        && (rounds > 0 || Boolean.TRUE.equals(held) || !toolCalls.isEmpty())) {
                    r.say(Persona.phrase("no_answer", language));
                }
                break;
            }
        } catch (LanguageModel.Unavailable e) {
            log.severe(e.getMessage() + ". " + e.hint());
            failure = "llm_down";
            hint = (e.getMessage() + ". " + e.hint()).strip();
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "the language model failed", e);
            failure = "error";
        }
        return new Outcome(answer, exchange, calls, failure, hint, lat);
    }

    /** One request to the model and what came back. */
    private final class Round implements LanguageModel.Stream {
        final double t;
        final int rounds;
        final Map<String, Double> lat;
        final List<String> answer;
        final Speaker speaker;
        final BooleanSupplier cancelled;
        final double tReq;
        final List<String> text = new ArrayList<>();
        final List<ToolCall> toolCalls = new ArrayList<>();
        SentenceSplitter splitter = new SentenceSplitter();
        Boolean held;
        boolean first = true;
        int fed;
        int scan;
        boolean cut;
        final int saidBefore;

        Round(double t, int rounds, Map<String, Double> lat, List<String> answer, Speaker speaker,
              BooleanSupplier cancelled) {
            this.t = t;
            this.rounds = rounds;
            this.lat = lat;
            this.answer = answer;
            this.speaker = speaker;
            this.cancelled = cancelled;
            this.tReq = clock.getAsDouble();
            this.held = rounds > 0 ? Boolean.TRUE : null;
            this.saidBefore = answer.size();
        }

        void say(String s) {
            lat.putIfAbsent("first_chunk", clock.getAsDouble() - t);
            answer.add(s);
            speaker.say(s);
        }

        private void firstPiece() {
            if (first) {
                first = false;
                if (rounds == 0) {
                    lat.putIfAbsent("llm_first_token", clock.getAsDouble() - t);
                } else {
                    lat.merge("llm_first_token_2", clock.getAsDouble() - tReq, Double::sum);
                }
            }
        }

        @Override
        public void toolCall(ToolCall call) {
            firstPiece();
            toolCalls.add(call);
        }

        @Override
        public void text(String piece) {
            firstPiece();
            text.add(piece);
            if (held == null) {                 // decided on the first characters
                String soFar = String.join("", text);
                held = SpeechText.looksLikePayload(soFar);
                if (held == null) {
                    return;
                }
            }
            if (held || cut) {
                return;
            }
            String joined = String.join("", text);
            Matcher end = SpeechText.THINK_END.matcher(joined);
            if (end.find(Math.max(scan, Math.max(0, fed - TAG.length())))) {
                if (answer.size() > saidBefore) {
                    for (String s : splitter.feed(joined.substring(fed, Math.max(fed, end.start())))) {
                        say(s);
                    }
                    for (String s : splitter.flush()) {
                        say(s);
                    }
                    cut = true;
                    return;
                }
                splitter = new SentenceSplitter();
                fed = end.end();
                scan = end.end();
            }
            int stop = joined.length();
            for (int k = 1; k <= TAG.length(); k++) {
                if (joined.endsWith(TAG.substring(0, k))) {
                    stop = joined.length() - k;
                }
            }
            stop = Math.max(stop, fed);
            for (String s : splitter.feed(joined.substring(fed, stop))) {
                say(s);
            }
            fed = stop;
        }

        @Override
        public boolean cancelled() {
            return cancelled.getAsBoolean();
        }
    }
}
