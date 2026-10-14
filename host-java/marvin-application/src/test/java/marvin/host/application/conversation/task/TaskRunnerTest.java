// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.Reasoning;
import marvin.host.domain.conversation.task.AgentPrompt;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskSpent;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.task.TaskStep;
import marvin.host.domain.conversation.tool.MemoryToolSpecs;
import marvin.host.domain.conversation.tool.TaskTools;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.ToolSpec;

/** The agent loop against a scripted model: tools, the final answer, budgets, cancelling, giving way to the voice. */
class TaskRunnerTest {
    static final LanguageModel.Thinking HIGH = new LanguageModel.Thinking(Reasoning.HIGH, LanguageModel.Thinking.TASK_TOKENS);
    double now = 1000;
    final List<String> recalled = new CopyOnWriteArrayList<>();

    ToolRegistry tools() {
        List<ToolRegistry.Tool> t = new ArrayList<>();
        t.add(new ToolRegistry.Tool(MemoryToolSpecs.recall(), (args, ctx) -> {
            recalled.add(String.valueOf(args.get("query")));
            if ("nothing".equals(args.get("query"))) {
                throw new ToolError("nothing found");
            }
            return Map.of("facts", List.of("The owner takes the 8:12 train on Mondays."));
        }));
        return new ToolRegistry(t, true, true, () -> now).forTasks(List.of());
    }

    TaskRunner.Setup setup(ToolRegistry tools, TaskBudget budget) {
        return new TaskRunner.Setup("http://ollama", "qwen3.8:27b-mxfp8", tools, HIGH,
                List.of(ChatMessage.system("agent"), ChatMessage.user("Compare my trains.")), budget, Map.of("language", "en"), 60);
    }

    static final class Recorder implements TaskRunner.Progress {
        final List<TaskStep> steps = new CopyOnWriteArrayList<>();
        final List<String> activities = new CopyOnWriteArrayList<>();

        @Override
        public void step(TaskStep step) {
            steps.add(step);
        }

        @Override
        public void activity(String doing, TaskSpent spent) {
            activities.add(doing);
        }

        List<String> kinds() {
            return steps.stream().map(s -> s.kind() + (s.data().get("name") != null ? ":" + s.data().get("name") : "")).toList();
        }
    }

    TaskRunner runner(LanguageModel model, ModelLane lane) {
        return new TaskRunner(model, lane, () -> now, () -> now);
    }

    @Test
    void itThinksUsesItsToolsAndFinishes() {
        ScriptedModel model = ScriptedModel.of(
                List.of(new ScriptedModel.Think("I should look in memory first."), ScriptedModel.call("recall", Map.of("query", "train"))),
                List.of(new ScriptedModel.Think("Enough."), ScriptedModel.call(TaskTools.FINAL, Map.of(
                        "summary", "**Your** train is the 8:12.", "result", "## Trains\n\n- 8:12 on Mondays"))));
        Recorder rec = new Recorder();
        TaskRunner.Outcome out = runner(model, new ModelLane(1, () -> now)).run(setup(tools(), TaskBudget.DEFAULT), rec, () -> false);
        assertThat(out.status()).isEqualTo(TaskStatus.DONE);
        assertThat(out.summary()).isEqualTo("Your train is the 8:12.");          // cleaned for speech
        assertThat(out.result()).isEqualTo("## Trains\n\n- 8:12 on Mondays");
        assertThat(out.spent().steps()).isEqualTo(2);
        assertThat(out.spent().toolCalls()).isEqualTo(1);
        assertThat(out.spent().tokens()).isEqualTo(240);                         // Ollama's eval_count, twice
        assertThat(rec.kinds()).containsExactly("model", "tool:recall", "model", "tool:final_answer");
        assertThat(rec.steps.get(0).data()).containsEntry("thinking", "I should look in memory first.");
        assertThat(rec.steps.get(1).data()).containsEntry("ok", true);
        assertThat(recalled).containsExactly("train");
        // every request thinks hard, is offered the task's tools and the final answer, and reads the tool's result
        assertThat(model.thinking).containsOnly(HIGH);
        assertThat(model.tools.get(0)).extracting(m -> (Object) ((Map<?, ?>) m.get("function")).get("name"))
                .containsExactly("recall", TaskTools.FINAL);
        List<ChatMessage> second = model.calls.get(1);
        assertThat(second.get(second.size() - 1).role()).isEqualTo("tool");
        assertThat(second.get(second.size() - 1).content()).contains("8:12 train");
        assertThat(rec.activities).contains("Thinking about the task", "Using recall");
    }

    @Test
    void aToolErrorIsReadByTheModelAndAPlainAnswerIsAcceptedAfterOneNudge() {
        ScriptedModel model = ScriptedModel.of(
                List.of(ScriptedModel.call("recall", Map.of("query", "nothing"))),
                "Nothing in memory about trains.",
                "Nothing in memory about trains. I could not check the timetable.");
        Recorder rec = new Recorder();
        TaskRunner.Outcome out = runner(model, new ModelLane(1, () -> now)).run(setup(tools(), TaskBudget.DEFAULT), rec, () -> false);
        assertThat(out.status()).isEqualTo(TaskStatus.DONE);
        assertThat(out.result()).isEqualTo("Nothing in memory about trains. I could not check the timetable.");
        assertThat(out.summary()).startsWith("Nothing in memory about trains.");
        assertThat(rec.steps.get(1).data()).containsEntry("ok", false).containsEntry("error", "nothing found");
        // the nudge
        assertThat(model.calls.get(2).getLast().content()).isEqualTo(AgentPrompt.FINISH);
    }

    @Test
    void anUnknownToolOrBadFinalArgumentsAreToldAndTheTaskGoesOn() {
        ScriptedModel model = ScriptedModel.of(
                List.of(ScriptedModel.call("browse_web", Map.of("url", "x"))),
                List.of(ScriptedModel.call(TaskTools.FINAL, Map.of("summary", 3))),
                List.of(ScriptedModel.call(TaskTools.FINAL, Map.of("summary", "Done.", "result", "Done."))));
        Recorder rec = new Recorder();
        TaskRunner.Outcome out = runner(model, new ModelLane(1, () -> now)).run(setup(tools(), TaskBudget.DEFAULT), rec, () -> false);
        assertThat(out.status()).isEqualTo(TaskStatus.DONE);
        assertThat(model.calls.get(1).getLast().content()).contains("unknown tool 'browse_web'");
        assertThat(model.calls.get(2).getLast().content()).contains("error");
    }

    @Test
    void theBudgetAsksToFinishThenEndsTheTask() {
        ScriptedModel model = new ScriptedModel(m -> List.of(ScriptedModel.call("recall", Map.of("query", "q" + m.size()))));
        Recorder rec = new Recorder();
        TaskRunner.Outcome out = runner(model, new ModelLane(1, () -> now)).run(setup(tools(), new TaskBudget(3, 600, 100_000)),
                rec, () -> false);
        assertThat(out.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(out.error()).isEqualTo("Used its 3 steps without finishing");
        assertThat(model.calls).hasSize(3);
        // the last request: told to finish, offered the final answer only
        assertThat(model.calls.get(2)).extracting(ChatMessage::content).contains(AgentPrompt.LAST_STEP);
        assertThat(model.tools.get(2)).extracting(m -> (Object) ((Map<?, ?>) m.get("function")).get("name")).containsExactly(TaskTools.FINAL);
        assertThat(rec.steps.stream().filter(s -> s.kind().equals(TaskStep.NOTE)).map(s -> s.data().get("text")))
                .contains("Budget nearly spent: asked to finish", "Used its 3 steps without finishing");

        ScriptedModel wordy = new ScriptedModel(m -> List.of(ScriptedModel.call("recall", Map.of("query", "q" + m.size()))));
        wordy.usage = new LanguageModel.Usage(100, 0.1, 5000, 50, 0);
        TaskRunner.Outcome tokens = runner(wordy, new ModelLane(1, () -> now)).run(setup(tools(), new TaskBudget(20, 600, 9000)),
                new Recorder(), () -> false);
        assertThat(tokens.error()).isEqualTo("Wrote its 9000 tokens without finishing");
        assertThat(wordy.calls).hasSize(2);
    }

    @Test
    void timeRunsOutEvenInTheMiddleOfAStep() throws Exception {
        ScriptedModel model = ScriptedModel.of(List.of(new ScriptedModel.Think("long"), ScriptedModel.HOLD, "never"));
        CompletableFuture<TaskRunner.Outcome> out = CompletableFuture.supplyAsync(() ->
                runner(model, new ModelLane(1, () -> now)).run(setup(tools(), new TaskBudget(10, 60, 100_000)), new Recorder(),
                        () -> false));
        model.held.await(2, TimeUnit.SECONDS);
        now += 61;
        TaskRunner.Outcome o = out.get(3, TimeUnit.SECONDS);
        assertThat(o.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(o.error()).startsWith("Ran out of time");
        assertThat(model.cut).containsExactly(0);
    }

    @Test
    void cancellingStopsTheRequestAtOnce() throws Exception {
        ScriptedModel model = ScriptedModel.of(List.of(ScriptedModel.HOLD, "never"));
        boolean[] cancel = {false};
        CompletableFuture<TaskRunner.Outcome> out = CompletableFuture.supplyAsync(() ->
                runner(model, new ModelLane(1, () -> now)).run(setup(tools(), TaskBudget.DEFAULT), new Recorder(), () -> cancel[0]));
        model.held.await(2, TimeUnit.SECONDS);
        cancel[0] = true;
        TaskRunner.Outcome o = out.get(3, TimeUnit.SECONDS);
        assertThat(o.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(model.cut).containsExactly(0);
    }

    @Test
    void theVoiceCutsARequestWhichStartsAgainAfterTheConversationAndDoesNotCount() throws Exception {
        ScriptedModel model = ScriptedModel.of(
                List.of(new ScriptedModel.Think("thinking hard"), ScriptedModel.HOLD, "never"),
                List.of(ScriptedModel.call(TaskTools.FINAL, Map.of("summary", "Done.", "result", "All done."))));
        ModelLane lane = new ModelLane(1, () -> now);
        Recorder rec = new Recorder();
        CompletableFuture<TaskRunner.Outcome> out = CompletableFuture.supplyAsync(() ->
                runner(model, lane).run(setup(tools(), TaskBudget.DEFAULT), rec, () -> false));
        model.held.await(2, TimeUnit.SECONDS);
        lane.voice(true);                               // someone talks to Marvin
        Thread.sleep(300);
        assertThat(model.cut).containsExactly(0);
        assertThat(model.calls).hasSize(1);             // it waits for the conversation
        lane.voice(false);
        now += ModelLane.GRACE_S;
        TaskRunner.Outcome o = out.get(3, TimeUnit.SECONDS);
        assertThat(o.status()).isEqualTo(TaskStatus.DONE);
        assertThat(o.spent().steps()).isEqualTo(1);     // the cut request did not count
        assertThat(model.calls.get(1)).isEqualTo(model.calls.get(0));       // the same step, again
        assertThat(rec.steps.stream().filter(s -> s.kind().equals(TaskStep.NOTE)).map(s -> s.data().get("text")))
                .containsExactly("Paused: you started talking with Marvin; this step starts again after",
                        "Paused while you talk with Marvin");
    }

    @Test
    void theModelServerFailingFailsTheTask() {
        ScriptedModel model = ScriptedModel.of("x");
        model.fail = new LanguageModel.Unavailable("cannot reach Ollama at http://ollama: Connection refused", "Start Ollama.");
        TaskRunner.Outcome o = runner(model, new ModelLane(1, () -> now)).run(setup(tools(), TaskBudget.DEFAULT), new Recorder(),
                () -> false);
        assertThat(o.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(o.error()).isEqualTo("cannot reach Ollama at http://ollama: Connection refused. Start Ollama.");
        model.fail = new LanguageModel.ToolsUnsupported("m cannot use tools");
        assertThat(runner(model, new ModelLane(1, () -> now)).run(setup(tools(), TaskBudget.DEFAULT), new Recorder(), () -> false)
                .error()).contains("cannot use tools");
    }

    @Test
    void aTasksToolsAreTheConversationsThatStayOnThisComputer() {
        List<ToolRegistry.Tool> all = List.of(
                new ToolRegistry.Tool(MemoryToolSpecs.recall(), (a, c) -> "r"),
                new ToolRegistry.Tool(MemoryToolSpecs.forget(), (a, c) -> "f", marvin.host.domain.conversation.tool.ToolAccess.LOCAL_WRITE,
                        java.util.Set.of(marvin.host.domain.conversation.tool.ToolSurface.CONVERSATION)),
                new ToolRegistry.Tool(new ToolSpec("draft_email", "d", Map.of("type", "object", "properties", Map.of()), 5, true,
                        false, false), (a, c) -> "d", marvin.host.domain.conversation.tool.ToolAccess.EXTERNAL_WRITE, null),
                new ToolRegistry.Tool(new ToolSpec("task_only", "t", Map.of("type", "object", "properties", Map.of()), 5, false,
                        false, false), (a, c) -> "t", marvin.host.domain.conversation.tool.ToolAccess.READ,
                        java.util.Set.of(marvin.host.domain.conversation.tool.ToolSurface.TASK)));
        ToolRegistry conversation = new ToolRegistry(all, true, true, () -> now);
        // a tool that writes outside this computer is never offered in the conversation (no approval there)
        assertThat(conversation.offered()).extracting(t -> t.spec().name()).containsExactly("forget", "recall");
        ToolRegistry task = conversation.forTasks(List.of());
        assertThat(task.offered()).extracting(t -> t.spec().name()).containsExactly("recall", "task_only");
        assertThat(new ToolRegistry(all, true, false, () -> now).forTasks(List.of()).offered())
                .extracting(t -> t.spec().name()).containsExactly("recall", "task_only");
        assertThat(new ToolRegistry(all, false, true, () -> now).forTasks(List.of()).ollamaTools()).isNull();
    }
}
