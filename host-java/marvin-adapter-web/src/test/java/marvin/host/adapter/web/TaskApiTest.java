// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.conversation.port.in.TaskControl;
import marvin.host.domain.conversation.ReasoningSettings;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskSpent;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.task.TaskStep;
import marvin.host.domain.shared.JsonText;

/** The tasks' API behind the real access filter, on stand-in tasks; and the event hub's task messages. */
class TaskApiTest {

    static final class FakeTasks implements TaskControl {
        final List<Task> tasks = new ArrayList<>();
        final Map<String, Object> settings = new LinkedHashMap<>(ReasoningSettings.DEFAULT.toMap());
        boolean off;
        String lastLanguage;

        @Override
        public List<Task> tasks(int limit) {
            return tasks.stream().limit(limit).toList();
        }

        @Override
        public Optional<Task> task(String id) {
            return tasks.stream().filter(t -> t.id().equals(id)).findFirst();
        }

        @Override
        public List<TaskStep> steps(String id) {
            return List.of(new TaskStep(1, 10.5, TaskStep.MODEL, Map.of("thinking", "Plan first.", "text", "")),
                    new TaskStep(2, 11.0, TaskStep.TOOL, Map.of("name", "recall", "ok", true, "result", "{}")));
        }

        @Override
        public Task start(String goal, String language) {
            if (off) {
                throw new Refused("Background tasks are switched off (Marvin > Voice & models)");
            }
            if (goal.isBlank()) {
                throw new IllegalArgumentException("say what the task is");
            }
            lastLanguage = language;
            Task t = Task.queued("t" + tasks.size(), goal, language, Task.APP, 100, TaskBudget.DEFAULT);
            tasks.add(0, t);
            return t;
        }

        @Override
        public Optional<Task> cancel(String id) {
            return task(id).map(t -> t.finished(TaskStatus.CANCELLED, 120, "", "", "Cancelled", null));
        }

        @Override
        public Map<String, Object> settings() {
            return Map.of("settings", settings, "levels", List.of("off", "low", "medium", "high"));
        }

        @Override
        public Map<String, Object> updateSettings(Map<String, ?> update) {
            settings.putAll(ReasoningSettings.validate(update));
            return settings();
        }
    }

    final FakeTasks tasks = new FakeTasks();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskController(tasks)).setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(ResultActions r) throws Exception {
        return (Map<String, Object>) JsonText.parse(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Host", "localhost:8765").contentType("application/json").content(body));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startListReadAndCancel() throws Exception {
        Map<String, Object> started = json(postJson("/api/tasks/start", "{\"goal\": \"Compare two trains\", \"language\": \"fr\"}")
                .andExpect(status().isOk()));
        assertThat((Map<String, Object>) started.get("task")).containsEntry("status", "queued").containsEntry("title",
                "Compare two trains").containsEntry("has_result", false).doesNotContainKey("result");
        assertThat(tasks.lastLanguage).isEqualTo("fr");
        postJson("/api/tasks/start", "{\"goal\": \"Plan my week\"}").andExpect(status().isOk());
        assertThat(tasks.lastLanguage).isNull();                    // the host decides

        Map<String, Object> list = json(mvc.perform(get("/api/tasks?limit=5").header("Host", "localhost:8765"))
                .andExpect(status().isOk()));
        assertThat((List<Map<String, Object>>) list.get("tasks")).extracting(t -> t.get("title"))
                .containsExactly("Plan my week", "Compare two trains");
        assertThat(list.get("open")).isEqualTo(2L);

        Task done = tasks.tasks.get(1).running(101, "m", "high", "xhigh").finished(TaskStatus.DONE, 130, "Ready.", "# Trains",
                "", TaskSpent.NONE);
        tasks.tasks.set(1, done);
        Map<String, Object> one = json(mvc.perform(get("/api/tasks/" + done.id()).header("Host", "localhost:8765"))
                .andExpect(status().isOk()));
        assertThat((Map<String, Object>) one.get("task")).containsEntry("result", "# Trains").containsEntry("think", "xhigh");
        assertThat((List<Map<String, Object>>) one.get("steps")).extracting(s -> s.get("kind")).containsExactly("model", "tool");
        assertThat(((List<Map<String, Object>>) one.get("steps")).get(0)).containsEntry("thinking", "Plan first.")
                .containsEntry("n", 1L);
        mvc.perform(get("/api/tasks/nope").header("Host", "localhost:8765")).andExpect(status().isNotFound());

        Map<String, Object> cancelled = json(postJson("/api/tasks/cancel", "{\"id\": \"t0\"}").andExpect(status().isOk()));
        assertThat((Map<String, Object>) cancelled.get("task")).containsEntry("status", "cancelled");
        postJson("/api/tasks/cancel", "{\"id\": \"nope\"}").andExpect(status().isNotFound());
    }

    @Test
    void badRequestsAndRefusals() throws Exception {
        assertThat(json(postJson("/api/tasks/start", "{\"goal\": 3}").andExpect(status().isBadRequest())))
                .containsEntry("error", "goal must be a string");
        assertThat(json(postJson("/api/tasks/start", "{\"goal\": \" \"}").andExpect(status().isBadRequest())))
                .containsEntry("error", "say what the task is");
        postJson("/api/tasks/start", "{\"goal\": \"x\", \"language\": \"french\"}").andExpect(status().isBadRequest());
        postJson("/api/tasks/start", "[1]").andExpect(status().isBadRequest());
        postJson("/api/tasks/cancel", "{}").andExpect(status().isBadRequest());
        tasks.off = true;
        assertThat(json(postJson("/api/tasks/start", "{\"goal\": \"x\"}").andExpect(status().isConflict())))
                .containsEntry("error", "Background tasks are switched off (Marvin > Voice & models)");
        // the access rules of every POST: JSON, same origin
        mvc.perform(post("/api/tasks/start").header("Host", "localhost:8765").contentType("text/plain").content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/tasks/start").header("Host", "localhost:8765").header("Origin", "http://evil.example")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("unchecked")
    void settingsAreReadAndSaved() throws Exception {
        Map<String, Object> s = json(mvc.perform(get("/api/tasks/settings").header("Host", "localhost:8765"))
                .andExpect(status().isOk()));
        assertThat((Map<String, Object>) s.get("settings")).containsEntry("task_reasoning", "high");
        Map<String, Object> saved = json(postJson("/api/tasks/settings", "{\"reasoning\": \"low\", \"tasks\": false}")
                .andExpect(status().isOk()));
        assertThat((Map<String, Object>) saved.get("settings")).containsEntry("reasoning", "low").containsEntry("tasks", false);
        assertThat(json(postJson("/api/tasks/settings", "{\"reasoning\": \"max\"}").andExpect(status().isBadRequest())))
                .containsEntry("error", "reasoning must be one of off, low, medium, high");
    }

    @Test
    void theHubCarriesTasksAndSteps() throws Exception {
        EventHub hub = new EventHub();
        var q = hub.subscribe();
        hub.onTask("task", Map.of("id", "t1", "status", "running"));
        hub.onTask("task_step", Map.of("id", "t1", "step", Map.of("n", 1)));
        assertThat(q.poll().kind()).isEqualTo("task");
        assertThat(q.poll().kind()).isEqualTo("task_step");
    }
}
