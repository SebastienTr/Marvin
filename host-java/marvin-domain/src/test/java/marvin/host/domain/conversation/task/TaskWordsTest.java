// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.task.TaskAnnouncement.Decision;
import marvin.host.domain.conversation.tool.TaskTools;

/** When a request is a task, when a finished one is told, and the words around them. */
class TaskWordsTest {

    @Test
    void askingOutrightForABackgroundTask() {
        assertThat(TaskIntent.explicit("Fais-le en tâche de fond")).isTrue();
        assertThat(TaskIntent.explicit("Marvin, compare les deux offres en arrière-plan")).isTrue();
        assertThat(TaskIntent.explicit("Look into flights to Oslo in the background, please")).isTrue();
        assertThat(TaskIntent.explicit("Quelle heure est-il ?")).isFalse();
        // the goal: the request without the words, or the question before when nothing else is left
        assertThat(TaskIntent.goal("Fais-le en tâche de fond", "Compare les forfaits mobiles")).isEqualTo("Compare les forfaits mobiles");
        assertThat(TaskIntent.goal("Marvin, fais ça en tache de fond stp", "Prépare ma semaine")).isEqualTo("Prépare ma semaine");
        assertThat(TaskIntent.goal("Do it in the background", "")).isNull();
        assertThat(TaskIntent.goal("Compare les deux offres en arrière-plan", "x"))
                .isEqualTo("Compare les deux offres en arrière-plan");
    }

    @Test
    void wordsThatUsuallyAnnounceSeveralSteps() {
        for (String q : List.of("Prépare-moi un résumé de la semaine", "compare ces deux vols", "Résume ma semaine",
                "Renseigne-toi sur les marées", "Look into cheap flights to Lisbon", "Summarize my week", "Plan my weekend")) {
            assertThat(TaskIntent.likely(q)).as(q).isTrue();
        }
        for (String q : List.of("Quel temps fait-il ?", "Bonjour Marvin", "What do you see?", "Remember that I like tea")) {
            assertThat(TaskIntent.likely(q)).as(q).isFalse();
        }
    }

    @Test
    void whenAFinishedTaskIsTold() {
        assertThat(TaskAnnouncement.decide(true, true, false, true, 0)).isEqualTo(Decision.WAIT);
        assertThat(TaskAnnouncement.decide(true, false, false, true, 1e9)).isEqualTo(Decision.SPEAK_NOW);
        assertThat(TaskAnnouncement.decide(true, false, false, false, 0)).isEqualTo(Decision.NEXT_TURN);
        assertThat(TaskAnnouncement.decide(true, false, true, true, 0)).isEqualTo(Decision.NEXT_TURN);
        assertThat(TaskAnnouncement.decide(false, false, false, true, 0)).isEqualTo(Decision.NEXT_TURN);
        // no presence sensor: a recent conversation says someone is there
        assertThat(TaskAnnouncement.decide(true, false, false, null, 60)).isEqualTo(Decision.SPEAK_NOW);
        assertThat(TaskAnnouncement.decide(true, false, false, null, TaskAnnouncement.RECENT_S + 1)).isEqualTo(Decision.NEXT_TURN);
        assertThat(TaskAnnouncement.decide(true, false, false, null, Double.POSITIVE_INFINITY)).isEqualTo(Decision.NEXT_TURN);
    }

    @Test
    void titlesAndSummaries() {
        assertThat(TaskText.title("Marvin, est-ce que tu peux comparer les deux forfaits ?")).isEqualTo("Comparer les deux forfaits");
        assertThat(TaskText.title("can you look into the cheapest way to get to Lisbon from Nice in November with a bike please"))
                .isEqualTo("Look into the cheapest way to get to Lisbon from Nice in…");
        assertThat(TaskText.title("?")).isEqualTo("Task");
        assertThat(TaskText.summaryOf("## Result\n\n- **Option A** is cheaper. It takes longer.\n- Option B is faster."))
                .isEqualTo("Option A is cheaper. It takes longer. Option B is faster.");
        assertThat(TaskText.summaryOf("x".repeat(400))).hasSize(TaskText.SUMMARY);
    }

    @Test
    void aTaskChangesByNewValues() {
        Task q = Task.queued("id", " Compare trains ", "fr", Task.VOICE, 10, TaskBudget.DEFAULT);
        assertThat(q.goal()).isEqualTo("Compare trains");
        assertThat(q.toTell()).isFalse();
        Task r = q.running(11, "m", "high", "xhigh");
        Task d = r.finished(TaskStatus.DONE, 20, "Said.", "# R", "", TaskSpent.NONE.plusStep(10, 5));
        assertThat(d.toTell()).isTrue();
        assertThat(d.toSummaryMap()).containsEntry("status", "done").containsEntry("has_result", true).doesNotContainKey("result");
        assertThat(d.toMap()).containsEntry("result", "# R");
        assertThat(d.announced("spoken", 21).toTell()).isFalse();
        assertThat(r.finished(TaskStatus.CANCELLED, 12, "", "", "Cancelled", null).toTell()).isFalse();
        assertThat(TaskBudget.of(Map.of("steps", 3))).isEqualTo(new TaskBudget(3, 1200, 40_000));
        assertThat(TaskSpent.of(new TaskSpent(2, 1, 300, 40, 12.34).toMap())).isEqualTo(new TaskSpent(2, 1, 300, 40, 12.3));
        assertThat(new TaskSpent(11, 0, 0, 0, 0).nearlySpent(TaskBudget.DEFAULT)).isTrue();
        assertThat(new TaskSpent(12, 0, 0, 0, 0).exceeds(TaskBudget.DEFAULT)).isEqualTo("steps");
    }

    @Test
    void thePersonaSaysWhatTasksCanDoOnlyWhenOffered() {
        String without = Persona.systemPrompt(null, true, true, false, null);
        assertThat(Persona.systemPrompt(null, true, true, false, false, null)).isEqualTo(without);
        String with = Persona.systemPrompt(null, true, true, false, true, null);
        assertThat(with).startsWith(without).contains("call start_task with the goal")
                .contains("never promise it can do what they cannot").contains("never invent its result");
        assertThat(Persona.systemPrompt(null, false, false, false, true, null)).doesNotContain("start_task");
        assertThat(Persona.phrase("task_started", "fr")).isEqualTo("Je m'en occupe, je te dis quand c'est prêt.");
        Task done = Task.queued("i", "Plan my week", "en", Task.VOICE, 1, null).running(2, "m", "high", "xhigh")
                .finished(TaskStatus.DONE, 3, "Three meetings.", "r", "", null);
        assertThat(Persona.finishedTasksNote(List.of(done))).startsWith("A background task finished since you last spoke: “Plan my week”, "
                + "done: Three meetings.");
        assertThat(Persona.finishedTasksNote(List.of())).isNull();
        assertThat(TaskTools.start().schema().toString()).contains("start_task");
        assertThat(AgentPrompt.system(List.of(TaskTools.finalAnswer()), 5, "fr", "")).contains("none (only final_answer)")
                .contains("in French");
    }
}
