// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.task.InMemoryTaskStore;
import marvin.host.application.conversation.task.ModelLane;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.Reasoning;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.tool.TaskTools;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.shared.LocalDays;

/**
 * The conversation and background tasks: the model starts one ({@code start_task}) and the turn ends with "I'm on it",
 * "do it in the background" starts one without the model, a finished task is said when the owner is there and mentioned
 * in the next answer otherwise, a question takes the model from a task, and the reasoning setting of the conversation.
 */
class VoiceTasksTest {
    final VoiceServiceTest f = new VoiceServiceTest();
    final InMemoryTaskStore store = new InMemoryTaskStore();
    TaskService tasks;

    @AfterEach
    void close() {
        f.close();
        if (tasks != null) {
            tasks.close();
        }
    }

    /** An agent that finishes at once. */
    static FakeModel agent(String summary, String result) {
        return new FakeModel(m -> List.of(new ToolCall(TaskTools.FINAL, Map.of("summary", summary, "result", result), null)));
    }

    VoiceService started(FakeModel voiceModel, FakeModel agentModel) throws InterruptedException {
        VoiceService v = f.service(voiceModel);
        tasks = new TaskService(store, agentModel, new ModelLane(1, () -> System.nanoTime() / 1e9), f.settings, f.clock,
                new LocalDays(ZoneId.of("Europe/Paris")));
        v.setTasks(tasks);
        v.start();
        VoiceServiceTest.waitFor(() -> v.snapshot().state().equals("on"));
        VoiceServiceTest.waitFor(() -> voiceModel.calls.size() == 2);
        return v;
    }

    void heard(long uid, String text, String lang) {
        f.sidecar.signals.signal(new VoiceSidecar.Heard(uid, text, "", lang, "voice", Map.of(), 0));
    }

    Task onlyTask() throws InterruptedException {
        VoiceServiceTest.waitFor(() -> store.tasks.size() == 1);
        return store.tasks.values().iterator().next();
    }

    @Test
    void theModelStartsATaskTheTurnEndsWithImOnItAndTheResultIsSaid() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w",
                List.of(new ToolCall(TaskTools.START, Map.of("goal", "Compare les forfaits mobiles de Free et Orange"), null)));
        FakeModel agent = agent("Free est moins cher de deux euros.", "## Forfaits\n\n| | Free | Orange |\n|---|---|---|");
        started(model, agent);
        assertThat(model.calls.get(0).get(0).content()).contains("call start_task with the goal");
        assertThat(model.tools.get(0)).extracting(m -> (Object) ((Map<?, ?>) m.get("function")).get("name"))
                .contains(TaskTools.START, TaskTools.RESULT);
        heard(1, "Prépare-moi une comparaison des forfaits mobiles", "fr");
        VoiceServiceTest.waitFor(() -> f.kinds("reply").size() == 1);
        // one request: the tool's result says what to say
        assertThat(model.calls).hasSize(3);
        assertThat(model.calls.get(2).getLast().content()).contains(Persona.TASK_HINT);
        assertThat(f.sidecar.sent(VoiceSidecar.Text.class)).extracting(VoiceSidecar.Text::text)
                .containsExactly("Je m'en occupe, je te dis quand c'est prêt.");
        Map<String, Object> reply = f.kinds("reply").getFirst();
        assertThat(reply.get("tools").toString()).contains("start_task");
        Task t = onlyTask();
        assertThat(t.origin()).isEqualTo(Task.VOICE);
        assertThat(t.language()).isEqualTo("fr");
        // done: said at once (someone is in front of the robot, nobody is talking)
        VoiceServiceTest.waitFor(() -> !f.sidecar.sent(VoiceSidecar.Say.class).isEmpty());
        VoiceSidecar.Say say = f.sidecar.sent(VoiceSidecar.Say.class).getFirst();
        assertThat(say.text()).isEqualTo("C'est prêt, pour « Compare les forfaits mobiles de Free et Orange » : "
                + "Free est moins cher de deux euros.");
        assertThat(say.proactive()).isTrue();
        VoiceServiceTest.waitFor(() -> "spoken".equals(store.tasks.get(t.id()).announced()));
        // the history keeps the start and the "I'm on it"
        heard(2, "Merci", "fr");
        VoiceServiceTest.waitFor(() -> model.calls.size() == 4);
        List<ChatMessage> next = model.calls.get(3);
        assertThat(next).extracting(ChatMessage::role).contains("tool");
        assertThat(next.getLast().content()).doesNotContain("background task finished");
    }

    @Test
    void doItInTheBackgroundStartsTheQuestionBeforeWithoutTheModel() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Il faudrait comparer plusieurs offres.");
        started(model, agent("Fait.", "Fait."));
        heard(1, "Compare les assurances habitation", "fr");
        VoiceServiceTest.waitFor(() -> f.kinds("reply").size() == 1);
        heard(2, "Fais-le en tâche de fond", "fr");
        VoiceServiceTest.waitFor(() -> f.kinds("reply").size() == 2);
        assertThat(model.calls).hasSize(3);                     // no request for the second question
        assertThat(onlyTask().goal()).isEqualTo("Compare les assurances habitation");
        assertThat(f.kinds("reply").get(1).get("text")).isEqualTo("Je m'en occupe, je te dis quand c'est prêt.");
    }

    @Test
    void awayATaskIsMentionedInTheNextAnswerInstead() throws InterruptedException {
        f.state = PresenceState.EMPTY;                          // the sensors say nobody is there
        f.state = new PresenceState(3_600_000_000L, false, false, null, null, null, 0, 0, 0, null, null, false, false, 0);
        FakeModel model = FakeModel.of("w", "w", "Bonjour.");
        started(model, agent("Trois réunions mardi.", "- mardi"));
        Task t = tasks.start("Résume ma semaine", "fr", Task.APP);
        VoiceServiceTest.waitFor(() -> store.tasks.get(t.id()).status() == TaskStatus.DONE);
        Thread.sleep(100);
        assertThat(f.sidecar.sent(VoiceSidecar.Say.class)).isEmpty();
        heard(1, "Bonjour", "fr");
        VoiceServiceTest.waitFor(() -> f.kinds("reply").size() == 1);
        assertThat(model.calls.get(2).getLast().content())
                .contains("A background task finished since you last spoke: “Résume ma semaine”, done: Trois réunions mardi.");
        VoiceServiceTest.waitFor(() -> "mentioned".equals(store.tasks.get(t.id()).announced()));
    }

    @Test
    void aQuestionTakesTheModelFromATask() throws InterruptedException {
        boolean[] busyDuring = {false};
        ModelLane[] laneOf = {null};
        FakeModel model = new FakeModel(m -> {
            if (laneOf[0] != null) {
                busyDuring[0] = laneOf[0].voiceBusy();
            }
            return List.of("Oui.");
        });
        started(model, agent("x", "x"));
        ModelLane lane = tasks.lane();
        laneOf[0] = lane;
        assertThat(lane.voiceBusy()).isFalse();
        heard(1, "Tu es là ?", "fr");
        VoiceServiceTest.waitFor(() -> f.kinds("reply").size() == 1);
        assertThat(busyDuring[0]).isTrue();
        VoiceServiceTest.waitFor(() -> !lane.voiceBusy());
    }

    @Test
    void tasksSwitchedOffAreNotOfferedAndTheConversationThinksAsAsked() throws InterruptedException {
        f.settings.values.put("tasks", false);
        f.settings.values.put("reasoning", "low");
        FakeModel model = FakeModel.of("w", "w", "Bonjour.");
        model.thought = "They greet me.";
        started(model, agent("x", "x"));
        assertThat(model.calls.get(0).get(0).content()).doesNotContain("start_task");
        heard(1, "Prépare-moi un plan", "fr");
        VoiceServiceTest.waitFor(() -> f.kinds("reply").size() == 1);
        assertThat(model.calls.get(2).getLast().content()).doesNotContain(Persona.TASK_HINT);
        assertThat(model.thinkings.get(2)).isEqualTo(LanguageModel.Thinking.chat(Reasoning.LOW));
        assertThat(model.thinkings.get(0)).isEqualTo(LanguageModel.Thinking.OFF);     // the warm-up never thinks
        assertThat(f.kinds("reply").getFirst()).containsEntry("thinking", "They greet me.");
        assertThat(f.sidecar.sent(VoiceSidecar.Text.class)).extracting(VoiceSidecar.Text::text).containsExactly("Bonjour.");
        // switched on again: offered from the next question, without a restart
        f.settings.values.put("tasks", true);
        heard(2, "Encore", "fr");
        VoiceServiceTest.waitFor(() -> f.kinds("reply").size() == 2);
        assertThat(model.calls.get(3).get(0).content()).contains("start_task");
    }
}
