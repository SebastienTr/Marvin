// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.conversation.port.in.TaskControl;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskStep;

/**
 * Background tasks for the app (docs/ui.md, Activity; docs/intelligence.md): changes arrive on the event stream as
 * {@code task} (a task as lists show it) and {@code task_step} ({@code {id, step}}).
 *
 * <ul>
 * <li>{@code GET /api/tasks?limit=20}: {@code tasks} (newest first, without their results) and {@code open} (how many
 * wait or run);</li>
 * <li>{@code GET /api/tasks/{id}}: the {@code task} with its result, and its {@code steps}; 404 when there is none;</li>
 * <li>{@code POST /api/tasks/start} {@code {"goal": "...", "language": "fr"}}: the task, queued; 400 for an empty or too
 * long goal, 409 when tasks are off or too many wait;</li>
 * <li>{@code POST /api/tasks/cancel} {@code {"id": "..."}}: the task (a running one stops within a moment: follow the
 * stream); 404 when there is none;</li>
 * <li>{@code GET}, {@code POST /api/tasks/settings}: how hard the model thinks in the conversation and in tasks, whether
 * tasks are on, the model (its size, how it thinks), the recommendation, the budget, what the model server runs at
 * once.</li>
 * </ul>
 */
@RestController
public class TaskController {
    static final String START_PATH = "/api/tasks/start";
    static final String CANCEL_PATH = "/api/tasks/cancel";
    static final String SETTINGS_PATH = "/api/tasks/settings";

    private final TaskControl tasks;

    public TaskController(TaskControl tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/api/tasks")
    public ResponseEntity<byte[]> list(HttpServletRequest rq) {
        int limit = (int) ApiController.queryInt(rq, "limit", 20, 1, 100);
        List<Task> list = tasks.tasks(limit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tasks", list.stream().map(Task::toSummaryMap).toList());
        m.put("open", list.stream().filter(t -> !t.status().finished()).count());
        return Responses.json(m);
    }

    @GetMapping(SETTINGS_PATH)
    public ResponseEntity<byte[]> settings() {
        return Responses.json(tasks.settings());
    }

    @PostMapping(SETTINGS_PATH)
    public ResponseEntity<byte[]> updateSettings(HttpServletRequest rq) {
        @SuppressWarnings("unchecked")
        Map<String, ?> update = (Map<String, ?>) object(rq);
        return Responses.json(tasks.updateSettings(update));
    }

    @GetMapping("/api/tasks/{id}")
    public ResponseEntity<byte[]> one(@PathVariable String id) {
        return tasks.task(id).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("task", t.toMap());
            m.put("steps", tasks.steps(id).stream().map(TaskStep::toMap).toList());
            return Responses.json(m);
        }).orElseGet(() -> Responses.error(404, "no such task"));
    }

    @PostMapping(START_PATH)
    public ResponseEntity<byte[]> start(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        if (!(b.get("goal") instanceof String goal)) {
            throw new ApiController.BadRequest("goal must be a string");
        }
        Object lang = b.get("language");
        if (lang != null && !(lang instanceof String l && l.matches("[a-z]{2}"))) {
            throw new ApiController.BadRequest("language must be a two-letter code such as fr or en");
        }
        try {
            return Responses.json(Map.of("task", tasks.start(goal, (String) lang).toSummaryMap()));
        } catch (TaskControl.Refused e) {
            return Responses.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiController.BadRequest(e.getMessage());
        }
    }

    @PostMapping(CANCEL_PATH)
    public ResponseEntity<byte[]> cancel(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        if (!(b.get("id") instanceof String id)) {
            throw new ApiController.BadRequest("id must be a string");
        }
        return tasks.cancel(id).map(t -> Responses.json(Map.of("task", t.toSummaryMap())))
                .orElseGet(() -> Responses.error(404, "no such task"));
    }

    private static Map<?, ?> object(HttpServletRequest rq) {
        if (!(ApiController.body(rq) instanceof Map<?, ?> m)) {
            throw new ApiController.BadRequest("send a JSON object");
        }
        return m;
    }
}
