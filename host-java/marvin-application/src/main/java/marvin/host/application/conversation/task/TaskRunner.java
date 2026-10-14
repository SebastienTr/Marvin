// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.conversation.task.AgentPrompt;
import marvin.host.domain.conversation.task.Approval;
import marvin.host.domain.conversation.task.ApprovalText;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskSpent;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.task.TaskStep;
import marvin.host.domain.conversation.task.TaskText;
import marvin.host.domain.conversation.tool.TaskTools;
import marvin.host.domain.conversation.tool.ToolAccess;
import marvin.host.domain.conversation.tool.ToolArguments;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.shared.JsonText;

/**
 * A background task's agent loop: the model thinks (at the task's reasoning level), calls tools, reads their results,
 * and finishes with {@code final_answer} (a summary Marvin can say, the result in Markdown). Every request and every tool
 * run is a {@link TaskStep}, kept as it happens.
 *
 * <p>Budgets: requests ({@code steps}), wall time and tokens written; when one is nearly spent, the model is told to
 * finish and offered {@code final_answer} only; when one is spent, the task fails with what it had. Its prompt stays
 * within the model's context: tool results are cut to {@link #MAX_TOOL_RESULT} characters, and a prompt that grows past
 * {@link #MAX_PROMPT_CHARS} makes the next step the last. A request cut for the conversation ({@link ModelLane}) is made
 * again and does not count.
 *
 * <p>A call of a tool that writes outside this computer runs only once the owner approved it ({@link Approvals}): the
 * task waits meanwhile, and the wait does not count against its time budget. A rejected or expired call is not made; the
 * model is told why and goes on without it.
 */
public final class TaskRunner {
    private static final Logger log = Logger.getLogger("marvin.tasks");
    /** Characters of a tool's result the model reads. */
    static final int MAX_TOOL_RESULT = 6_000;
    /** About 11 000 tokens of prompt: with the answer's room, within the 16 384 of the context. */
    static final int MAX_PROMPT_CHARS = 38_000;
    /** Times the model may answer in plain text instead of finishing before that text is taken as the result. */
    static final int NUDGES = 1;

    /**
     * What a task runs with.
     *
     * @param approvals asks the owner's approval of a call that writes outside this computer; {@code null}: such a call
     *                  is refused
     */
    public record Setup(String host, String model, ToolRegistry tools, LanguageModel.Thinking thinking,
                        List<ChatMessage> messages, TaskBudget budget, Map<String, Object> toolContext, double timeoutS,
                        Approvals approvals) {

        public Setup(String host, String model, ToolRegistry tools, LanguageModel.Thinking thinking,
                     List<ChatMessage> messages, TaskBudget budget, Map<String, Object> toolContext, double timeoutS) {
            this(host, model, tools, thinking, messages, budget, toolContext, timeoutS, null);
        }
    }

    /** The owner's approval of one call (docs/design.md 6.3). */
    public interface Approvals {
        /**
         * Asks for it and waits until it is decided (approved, rejected, or expired: no answer in time, or the task was
         * cancelled).
         */
        Approval ask(String tool, Map<String, Object> arguments, Map<String, Object> preview) throws InterruptedException;

        /** The approved call ran, with this result. */
        default void ran(Approval approval, ToolResult result) {
        }
    }

    /** What the runner tells as it goes: each step, and what it is doing now. */
    public interface Progress {
        void step(TaskStep step);

        void activity(String doing, TaskSpent spent);
    }

    /** How it ended. */
    public record Outcome(TaskStatus status, String summary, String result, String error, TaskSpent spent) {
    }

    private final LanguageModel model;
    private final ModelLane lane;
    private final DoubleSupplier clock;
    private final DoubleSupplier wall;

    public TaskRunner(LanguageModel model, ModelLane lane, DoubleSupplier monotonicSeconds, DoubleSupplier wallSeconds) {
        this.model = model;
        this.lane = lane;
        this.clock = monotonicSeconds;
        this.wall = wallSeconds;
    }

    /** Runs the task to its end. Never throws. */
    public Outcome run(Setup setup, Progress progress, BooleanSupplier cancelled) {
        Run r = new Run(setup, progress, cancelled);
        try {
            return r.loop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return r.end(TaskStatus.CANCELLED, "", "", "Marvin's host is stopping");
        } catch (RuntimeException e) {
            log.log(java.util.logging.Level.WARNING, "a task failed", e);
            return r.end(TaskStatus.FAILED, "", "", "Something went wrong: " + e.getMessage());
        }
    }

    private final class Run {
        final Setup s;
        final Progress progress;
        final BooleanSupplier cancelled;
        final double t0 = clock.getAsDouble();
        final List<ChatMessage> messages;
        final ToolRegistry tools;
        int n;
        TaskSpent spent = TaskSpent.NONE;
        int nudges;
        boolean last;
        double waited;

        Run(Setup s, Progress progress, BooleanSupplier cancelled) {
            this.s = s;
            this.progress = progress;
            this.cancelled = cancelled;
            this.messages = new ArrayList<>(s.messages());
            this.tools = s.tools();
        }

        TaskSpent spent() {
            return spent = spent.at(clock.getAsDouble() - t0 - waited);
        }

        Outcome end(TaskStatus status, String summary, String result, String error) {
            return new Outcome(status, summary, result, error, spent());
        }

        void step(String kind, Map<String, Object> data) {
            progress.step(new TaskStep(++n, wall.getAsDouble(), kind, data));
        }

        void note(String text) {
            step(TaskStep.NOTE, Map.of("text", text));
        }

        List<Map<String, Object>> schemas() {
            List<Map<String, Object>> out = new ArrayList<>();
            if (!last && tools.ollamaTools() != null) {
                out.addAll(tools.ollamaTools());
            }
            out.add(TaskTools.finalAnswer().schema());
            return out;
        }

        Outcome loop() throws InterruptedException {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    return end(TaskStatus.CANCELLED, "", "", "Cancelled");
                }
                String over = spent().exceeds(s.budget());
                if (over != null) {
                    String why = switch (over) {
                        case "steps" -> "Used its " + s.budget().steps() + " steps without finishing";
                        case "time" -> "Ran out of time (" + Math.round(s.budget().seconds() / 60) + " min) before finishing";
                        default -> "Wrote its " + s.budget().tokens() + " tokens without finishing";
                    };
                    note(why);
                    return end(TaskStatus.FAILED, "", lastText(), why);
                }
                if (!last && (spent.nearlySpent(s.budget()) || promptChars() > MAX_PROMPT_CHARS)) {
                    last = true;
                    messages.add(ChatMessage.user(AgentPrompt.LAST_STEP));
                    note("Budget nearly spent: asked to finish");
                }
                ModelLane.Lease lease = lane.acquire(cancelled, () -> {
                    progress.activity("Waiting: you are talking with Marvin", spent());
                    note("Paused while you talk with Marvin");
                });
                if (lease == null) {
                    continue;                           // cancelled while waiting
                }
                progress.activity(spent.steps() == 0 ? "Thinking about the task" : "Thinking", spent());
                Collect c = new Collect(lease);
                double tReq = clock.getAsDouble();
                try {
                    model.streamChat(s.host(), s.model(), List.copyOf(messages), schemas(), s.timeoutS(), s.thinking(), c);
                } catch (LanguageModel.ToolsUnsupported e) {
                    lease.close();
                    note(e.getMessage());
                    return end(TaskStatus.FAILED, "", "", s.model() + " cannot use tools: choose a model that can in Marvin > "
                            + "Voice & models");
                } catch (LanguageModel.Unavailable e) {
                    lease.close();
                    note(e.getMessage());
                    return end(TaskStatus.FAILED, "", "", (e.getMessage() + ". " + e.hint()).strip());
                } finally {
                    lease.close();
                }
                double seconds = clock.getAsDouble() - tReq;
                if (cancelled.getAsBoolean()) {
                    return end(TaskStatus.CANCELLED, "", "", "Cancelled");
                }
                if (lease.preempted()) {
                    note("Paused: you started talking with Marvin; this step starts again after");
                    continue;
                }
                if (c.overTime) {
                    note("Ran out of time during a step");
                    return end(TaskStatus.FAILED, "", lastText(), "Ran out of time ("
                            + Math.round(s.budget().seconds() / 60) + " min) before finishing");
                }
                int written = c.usage != null ? c.usage.evalCount()
                        : (c.thought.length() + c.text.length()) / 4 + 20 * c.calls.size();
                spent = spent.plusStep(written, c.usage != null ? c.usage.promptEvalCount() : 0);
                String text = SpeechText.stripThinking(c.text.toString()).strip();
                step(TaskStep.MODEL, modelData(c, text, seconds));
                if (c.calls.isEmpty()) {
                    if (!text.isEmpty() && (nudges >= NUDGES || last)) {
                        return end(TaskStatus.DONE, TaskText.summaryOf(text), text, "");
                    }
                    nudges++;
                    if (!text.isEmpty()) {
                        messages.add(ChatMessage.assistant(text));
                    }
                    messages.add(ChatMessage.user(AgentPrompt.FINISH));
                    continue;
                }
                messages.add(ChatMessage.assistant(text, c.calls));
                for (ToolCall call : c.calls) {
                    if (cancelled.getAsBoolean()) {
                        return end(TaskStatus.CANCELLED, "", "", "Cancelled");
                    }
                    if (TaskTools.FINAL.equals(call.name())) {
                        Outcome done = finish(call);
                        if (done != null) {
                            return done;
                        }
                        continue;
                    }
                    ToolRegistry.Tool tool = tools.get(call.name());
                    ToolResult res;
                    if (tool != null && tool.access() == ToolAccess.EXTERNAL_WRITE) {
                        res = approved(call, tool);
                        if (res == null) {
                            return end(TaskStatus.CANCELLED, "", "", "Cancelled");
                        }
                    } else {
                        progress.activity("Using " + call.name(), spent());
                        res = tools.call(call.name(), call.arguments(), s.toolContext());
                    }
                    spent = spent.plusTool();
                    String content = TaskText.cut(res.content(), MAX_TOOL_RESULT);
                    messages.add(ChatMessage.tool(call.name(), content));
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", res.name());
                    d.put("arguments", res.arguments());
                    d.put("ok", res.ok());
                    d.put("seconds", Math.round(res.seconds() * 1000) / 1000.0);
                    if (res.ok()) {
                        d.put("result", TaskText.cut(res.content(), TaskStep.MAX_RESULT));
                    } else {
                        d.put("error", res.error());
                    }
                    step(TaskStep.TOOL, d);
                }
            }
        }

        /**
         * A call of a tool that writes outside this computer: made once the owner approved it, otherwise an error the
         * model reads; {@code null} when the task was cancelled while it waited.
         */
        ToolResult approved(ToolCall call, ToolRegistry.Tool tool) throws InterruptedException {
            Map<String, Object> args;
            try {
                args = ToolArguments.validate(tool.spec().parameters(), call.arguments());
            } catch (ToolError e) {
                return tools.call(call.name(), call.arguments(), s.toolContext());      // the error, as for any tool
            }
            if (s.approvals() == null || !tools.approvals()) {
                return ToolResult.failure(call.name(), args, "this tool needs the owner's approval, which cannot be asked "
                        + "here: it was not done", 0);
            }
            Map<String, Object> preview = null;
            if (tool.preview() != null) {
                try {
                    preview = tool.preview().preview(args);
                } catch (RuntimeException e) {
                    log.log(java.util.logging.Level.WARNING, "the preview of " + call.name() + " failed", e);
                }
            }
            note("Waiting for the owner's approval: " + call.name());
            double w0 = clock.getAsDouble();
            Approval a;
            try {
                a = s.approvals().ask(call.name(), args, preview);
            } finally {
                waited += clock.getAsDouble() - w0;
            }
            switch (a.state()) {
                case APPROVED -> {
                    note("Approved by the owner");
                    progress.activity("Using " + call.name(), spent());
                    ToolResult res = tools.call(call.name(), args, s.toolContext());
                    try {
                        s.approvals().ran(a, res);
                    } catch (RuntimeException e) {
                        log.log(java.util.logging.Level.WARNING, "could not keep what an approved call made", e);
                    }
                    return res;
                }
                case REJECTED -> {
                    note("Rejected by the owner" + (a.reason().isEmpty() ? "" : ": " + a.reason()));
                    return ToolResult.failure(call.name(), args, ApprovalText.rejected(a.reason()), 0);
                }
                default -> {
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }
                    note("No approval: " + a.reason());
                    return ToolResult.failure(call.name(), args, ApprovalText.expired(a.reason()), 0);
                }
            }
        }

        /** The final answer, or {@code null} (with the error told to the model) when its arguments are not right. */
        Outcome finish(ToolCall call) {
            Map<String, Object> args;
            try {
                args = ToolArguments.validate(TaskTools.finalAnswer().parameters(), call.arguments());
            } catch (ToolError e) {
                messages.add(ChatMessage.tool(call.name(), JsonText.write(Map.of("error", e.getMessage()))));
                step(TaskStep.TOOL, Map.of("name", call.name(), "ok", false, "error", e.getMessage()));
                return null;
            }
            String result = String.valueOf(args.getOrDefault("result", "")).strip();
            String summary = SpeechText.cleanForSpeech(String.valueOf(args.getOrDefault("summary", ""))).strip();
            if (result.isEmpty() && summary.isEmpty()) {
                messages.add(ChatMessage.tool(call.name(), JsonText.write(Map.of("error", "give a summary and a result"))));
                return null;
            }
            if (summary.isEmpty()) {
                summary = TaskText.summaryOf(result);
            }
            step(TaskStep.TOOL, Map.of("name", call.name(), "ok", true, "result", TaskText.cut(summary, 400)));
            return end(TaskStatus.DONE, TaskText.cut(summary, TaskText.SUMMARY), result.isEmpty() ? summary : result, "");
        }

        /** What the model last wrote, for a task that ends without finishing. */
        String lastText() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage m = messages.get(i);
                if ("assistant".equals(m.role()) && !m.content().isBlank()) {
                    return m.content();
                }
            }
            return "";
        }

        int promptChars() {
            int chars = 0;
            for (ChatMessage m : messages) {
                chars += m.content().length();
            }
            return chars;
        }

        Map<String, Object> modelData(Collect c, String text, double seconds) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("thinking", TaskText.cut(c.thought.toString().strip(), TaskStep.MAX_THINKING));
            d.put("text", text);
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : c.calls) {
                calls.add(Map.of("name", call.name(), "arguments", call.argumentsMap()));
            }
            d.put("tool_calls", calls);
            d.put("seconds", Math.round(seconds * 1000) / 1000.0);
            if (c.usage != null) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("prompt_eval_count", c.usage.promptEvalCount());
                u.put("prompt_eval_s", Math.round(c.usage.promptEvalSeconds() * 1000) / 1000.0);
                u.put("eval_count", c.usage.evalCount());
                u.put("eval_s", Math.round(c.usage.evalSeconds() * 1000) / 1000.0);
                d.put("usage", u);
            }
            if (last) {
                d.put("last", true);
            }
            return d;
        }

        /** One request's stream: stops when the task is cancelled, the conversation needs the model, or time is up. */
        final class Collect implements LanguageModel.Stream {
            final ModelLane.Lease lease;
            final StringBuilder thought = new StringBuilder();
            final StringBuilder text = new StringBuilder();
            final List<ToolCall> calls = new ArrayList<>();
            LanguageModel.Usage usage;
            boolean overTime;
            long lastActivity;

            Collect(ModelLane.Lease lease) {
                this.lease = lease;
            }

            @Override
            public void text(String piece) {
                text.append(piece);
                tell("Writing");
            }

            @Override
            public void thinking(String piece) {
                thought.append(piece);
                tell("Thinking");
            }

            @Override
            public void toolCall(ToolCall call) {
                calls.add(call);
            }

            @Override
            public void usage(LanguageModel.Usage u) {
                usage = u;
            }

            private void tell(String doing) {
                long now = (long) (clock.getAsDouble() * 1000);
                if (now - lastActivity >= 1000) {       // the app sees it think, at most once a second
                    lastActivity = now;
                    progress.activity(doing + " (" + (thought.length() + text.length()) / 4 + " tokens)", spent());
                }
            }

            @Override
            public boolean cancelled() {
                if (clock.getAsDouble() - t0 - waited >= s.budget().seconds()) {
                    overTime = true;
                }
                return overTime || lease.preempted() || cancelled.getAsBoolean();
            }
        }
    }
}
