// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.in.TaskControl;
import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.VoiceSettings.VoiceConfig;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.tool.MemoryToolSpecs;
import marvin.host.domain.conversation.tool.TaskTools;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * Tasks from start to end: queued and run one at a time, kept with their steps and published, refused when switched off
 * or too many, cancelled, interrupted by a restart, told to the owner at the right moment, and the conversation's tools.
 */
class TaskServiceTest {
    final InMemoryTaskStore store = new InMemoryTaskStore();
    final List<TaskService> services = new ArrayList<>();
    final Map<String, Object> voiceJson = new LinkedHashMap<>(Map.of("llm_model", "qwen3.8:27b-mxfp8"));
    final List<String[]> published = new CopyOnWriteArrayList<>();
    volatile double wall = 1_790_000_000;
    volatile double mono = 500;

    final Clocks clocks = new Clocks() {
        @Override
        public double wallSeconds() {
            return wall;
        }

        @Override
        public double monotonicSeconds() {
            return mono;
        }
    };

    final VoiceSettingsStore settings = new VoiceSettingsStore() {
        @Override
        public Map<String, Object> load() {
            return new LinkedHashMap<>(voiceJson);
        }

        @Override
        public void save(Map<String, Object> values) {
            voiceJson.putAll(values);
        }
    };

    /** The conversation, as tasks see it. */
    final class Host implements TaskService.Host {
        volatile boolean voiceOn = true;
        volatile boolean conversing;
        volatile Boolean present;
        volatile double sinceTalk = Double.POSITIVE_INFINITY;
        volatile boolean quiet;
        final List<String> said = new CopyOnWriteArrayList<>();

        @Override
        public VoiceConfig config() {
            return VoiceSettings.config(voiceJson);
        }

        @Override
        public ToolRegistry tools() {
            return new ToolRegistry(List.of(new ToolRegistry.Tool(MemoryToolSpecs.recall(), (a, c) -> Map.of("facts", List.of()))),
                    true, true, () -> mono);
        }

        @Override
        public String profile() {
            return "The owner is called Seb.";
        }

        @Override
        public boolean voiceOn() {
            return voiceOn;
        }

        @Override
        public boolean conversing() {
            return conversing;
        }

        @Override
        public boolean speak(String text, String language) {
            if (!voiceOn || conversing) {
                return false;
            }
            said.add(language + ": " + text);
            return true;
        }

        @Override
        public Boolean present() {
            return present;
        }

        @Override
        public double sinceTalkS() {
            return sinceTalk;
        }

        @Override
        public boolean quiet() {
            return quiet;
        }
    }

    final Host host = new Host();

    TaskService service(ScriptedModel model) {
        TaskService s = new TaskService(store, model, new ModelLane(1, () -> mono), settings, clocks,
                new LocalDays(ZoneId.of("Europe/Paris")));
        s.setHost(host);
        s.addListener((kind, payload) -> published.add(new String[] {kind, String.valueOf(payload.get("status")),
                String.valueOf(payload.get("id"))}));
        services.add(s);
        return s;
    }

    @AfterEach
    void close() {
        services.forEach(TaskService::close);
    }

    static ScriptedModel finishing(String summary, String result) {
        return new ScriptedModel(m -> List.of(ScriptedModel.call(TaskTools.FINAL, Map.of("summary", summary, "result", result))));
    }

    Task await(TaskService s, String id, TaskStatus status) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            Task t = s.task(id).orElseThrow();
            if (t.status() == status) {
                return t;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("task " + id + " is " + s.task(id).orElseThrow().status() + ", not " + status);
    }

    @Test
    void aTaskRunsWithTheConversationsModelThinkingHardAndIsSaidWhenDone() throws Exception {
        ScriptedModel model = finishing("Your train leaves at eight twelve.", "## Trains\n\n- 8:12");
        TaskService s = service(model);
        host.sinceTalk = 30;                             // no presence sensor, but they just talked with Marvin
        Task t = s.start("Marvin, can you compare my morning trains?", "en", Task.VOICE);
        assertThat(t.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(t.title()).isEqualTo("Compare my morning trains");
        Task done = await(s, t.id(), TaskStatus.DONE);
        assertThat(done.result()).isEqualTo("## Trains\n\n- 8:12");
        assertThat(done.summary()).isEqualTo("Your train leaves at eight twelve.");
        assertThat(done.model()).isEqualTo("qwen3.8:27b-mxfp8");
        assertThat(done.reasoning()).isEqualTo("high");
        assertThat(done.think()).isEqualTo("xhigh");
        assertThat(store.steps(t.id())).extracting(x -> x.kind()).containsExactly("model", "tool");
        // the agent's prompt: its tools only, its budget, the language, the profile; the goal with the time
        List<ChatMessage> first = model.calls.get(0);
        assertThat(first.get(0).content()).contains("Your tools are the only things you can do: recall (and final_answer to finish)")
                .contains("at most 12 steps").contains("in English").contains("The owner is called Seb.");
        assertThat(first.get(1).content()).startsWith("Now: ").contains("The owner asked (in English): Marvin, can you compare");
        // said at once, then not again
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (host.said.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertThat(host.said).containsExactly("en: It's ready, for “Compare my morning trains”: Your train leaves at eight twelve.");
        assertThat(s.task(t.id()).orElseThrow().announced()).isEqualTo("spoken");
        assertThat(s.toMention()).isEmpty();
        assertThat(published).extracting(p -> p[0]).contains("task", "task_step");
        assertThat(published).extracting(p -> p[1]).contains("queued", "running", "done");
    }

    @Test
    void notSaidWhileTalkingOrAwayButMentionedAtTheNextAnswer() throws Exception {
        TaskService s = service(finishing("Done.", "Done."));
        host.present = false;
        Task t = s.start("Summarize my week", "fr", Task.VOICE);
        await(s, t.id(), TaskStatus.DONE);
        Thread.sleep(100);
        assertThat(host.said).isEmpty();
        assertThat(s.toMention()).extracting(Task::id).containsExactly(t.id());
        host.present = true;                             // they come back
        host.conversing = true;                          // ... and talk at once
        s.tryAnnounce();
        assertThat(host.said).isEmpty();
        s.mentioned(s.toMention());                      // the next answer said it
        assertThat(s.toMention()).isEmpty();
        assertThat(s.task(t.id()).orElseThrow().announced()).isEqualTo("mentioned");
        host.conversing = false;
        s.tryAnnounce();
        assertThat(host.said).isEmpty();
    }

    @Test
    void quietHoursAndTheVoiceOffWaitForTheNextAnswerAndOldTasksAreNotMentioned() throws Exception {
        TaskService s = service(finishing("Done.", "Done."));
        host.present = true;
        host.quiet = true;
        Task t = s.start("Plan my weekend", "en", Task.APP);
        await(s, t.id(), TaskStatus.DONE);
        Thread.sleep(50);
        assertThat(host.said).isEmpty();
        host.quiet = false;
        host.voiceOn = false;
        s.tryAnnounce();
        assertThat(host.said).isEmpty();
        wall += TaskService.MENTION_S + 1;
        assertThat(s.toMention()).isEmpty();
    }

    @Test
    void refusedWhenOffEmptyOrTooManyAndTheSameGoalIsTheSameTask() throws Exception {
        ScriptedModel model = new ScriptedModel(m -> List.of(ScriptedModel.HOLD, "never"));
        TaskService s = service(model);
        assertThatThrownBy(() -> s.start("  ", "en")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.start("x".repeat(501), "en")).hasMessageContaining("at most 500");
        Task a = s.start("Compare two laptops", "en");
        assertThat(s.start("compare two laptops.", "en").id()).isEqualTo(a.id());
        s.start("Task two", "en");
        s.start("Task three", "en");
        s.start("Task four", "en");
        assertThatThrownBy(() -> s.start("Task five", "en")).isInstanceOf(TaskControl.Refused.class)
                .hasMessageContaining("4 tasks are already waiting or running");
        // one runs at a time
        model.held.await(2, TimeUnit.SECONDS);
        assertThat(s.tasks(10)).extracting(Task::status).containsOnly(TaskStatus.RUNNING, TaskStatus.QUEUED)
                .filteredOn(st -> st == TaskStatus.RUNNING).hasSize(1);
        voiceJson.put("tasks", false);
        assertThatThrownBy(() -> s.start("Another", "en")).isInstanceOf(TaskControl.Refused.class)
                .hasMessage("Background tasks are switched off (Marvin > Voice & models)");
    }

    @Test
    void cancellingAQueuedOrARunningTask() throws Exception {
        ScriptedModel model = new ScriptedModel(m -> List.of(ScriptedModel.HOLD, "never"));
        TaskService s = service(model);
        Task running = s.start("First", "en");
        Task queued = s.start("Second", "en");
        model.held.await(2, TimeUnit.SECONDS);
        assertThat(s.cancel(queued.id()).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
        s.cancel(running.id());
        Task c = await(s, running.id(), TaskStatus.CANCELLED);
        assertThat(c.error()).isEqualTo("Cancelled");
        assertThat(s.cancel("nope")).isEmpty();
        assertThat(model.calls).hasSize(1);             // the cancelled one never ran
    }

    @Test
    void aRestartInterruptsWhatWasRunning() {
        store.save(Task.queued("a", "Old task", "en", Task.APP, wall - 100, TaskBudget.DEFAULT).running(wall - 90, "m", "high", "xhigh"));
        store.save(Task.queued("b", "Waiting task", "en", Task.APP, wall - 80, TaskBudget.DEFAULT));
        TaskService s = service(finishing("x", "x"));
        assertThat(s.task("a").orElseThrow().status()).isEqualTo(TaskStatus.INTERRUPTED);
        assertThat(s.task("b").orElseThrow().error()).isEqualTo(TaskService.INTERRUPTED);
        assertThat(s.toMention()).isEmpty();             // nothing to tell: it did not finish
    }

    @Test
    void theConversationsToolsStartATaskAndReportOnThem() throws Exception {
        TaskService s = service(finishing("Two laptops compared.", "| | A | B |\n|---|---|---|\n| price | 1 | 2 |"));
        host.present = false;
        List<ToolRegistry.Tool> tools = s.conversationTools();
        ToolRegistry reg = new ToolRegistry(tools, true, true, () -> mono);
        ToolResult started = reg.call(TaskTools.START, Map.of("goal", "Compare the two laptops I liked"),
                Map.of("language", "fr", "source", "typed"));
        assertThat(started.ok()).isTrue();
        assertThat(started.finalSay()).isEqualTo("Je m'en occupe, je te dis quand c'est prêt.");
        Task t = s.tasks(1).getFirst();
        assertThat(t.origin()).isEqualTo(Task.TYPED);
        assertThat(t.language()).isEqualTo("fr");
        await(s, t.id(), TaskStatus.DONE);
        ToolResult report = reg.call(TaskTools.RESULT, Map.of("query", "laptops"), Map.of());
        assertThat(report.content()).contains("Two laptops compared.").contains("| price | 1 | 2 |").contains("\"status\": \"done\"");
        assertThat(reg.call(TaskTools.RESULT, Map.of("query", "holidays"), Map.of()).content()).contains("no task matches");
        voiceJson.put("tasks", false);
        ToolResult off = reg.call(TaskTools.START, Map.of("goal", "Something else"), Map.of("language", "en"));
        assertThat(off.ok()).isFalse();
        assertThat(off.error()).contains("switched off");
        assertThat(off.finalSay()).isNull();
    }

    @Test
    void settingsSayTheModelAndTheLevelsAndSaveWithoutRestart() {
        TaskService s = service(finishing("x", "x"));
        Map<String, Object> m = s.settings();
        assertThat(m.get("settings")).isEqualTo(Map.of("reasoning", "off", "task_reasoning", "high", "tasks", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) m.get("model");
        assertThat(model).containsEntry("name", "qwen3.8:27b-mxfp8").containsEntry("size_bytes", 32_000_000_000L)
                .containsEntry("think_tasks", "xhigh").containsEntry("think_chat", false);
        assertThat(m.get("recommended")).isEqualTo(Map.of("model", "qwen3.8:27b-mxfp8", "current", true, "installed", true));
        assertThat(m.get("server")).isEqualTo(Map.of("parallel", 1, "yields_to_voice", true));
        s.updateSettings(Map.of("reasoning", "low"));
        assertThat(voiceJson).containsEntry("reasoning", "low");
        assertThatThrownBy(() -> s.updateSettings(Map.of("reasoning", "extreme"))).hasMessageContaining("must be one of");
    }
}
