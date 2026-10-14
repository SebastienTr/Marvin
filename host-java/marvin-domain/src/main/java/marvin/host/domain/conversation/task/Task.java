// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A background task (docs/intelligence.md, "Thinking and background tasks"): work Marvin does with its tools while the
 * conversation goes on, then tells the owner about. Immutable: each change is a new value, saved and published.
 *
 * @param id          a UUID
 * @param goal        what the owner asked, in their words
 * @param title       a short name for lists
 * @param language    the owner's language when they asked ({@code fr}, {@code en}): the result and the spoken summary use it
 * @param origin      {@code voice} (asked aloud), {@code typed} (typed in Talk) or {@code app} (the Tasks form)
 * @param model       the model that runs it
 * @param reasoning   the reasoning level asked ({@code high} ...)
 * @param think       what Ollama was sent for it ({@code xhigh}, {@code true} ...)
 * @param activity    what it is doing now, in a few words (empty when finished)
 * @param summary     one or two sentences Marvin can say aloud (when done)
 * @param result      the result in Markdown (when done; what it had when it failed)
 * @param error       why it failed or stopped
 * @param announced   how the owner was told: {@code spoken} (Marvin said it), {@code mentioned} (in an answer), or empty
 * @param announcedAt when (Unix seconds), {@code null} until then
 */
public record Task(String id, String goal, String title, String language, String origin, TaskStatus status,
                   double createdAt, Double startedAt, Double finishedAt, String model, String reasoning, String think,
                   TaskBudget budget, TaskSpent spent, String activity, String summary, String result, String error,
                   String announced, Double announcedAt) {
    public static final String VOICE = "voice";
    public static final String TYPED = "typed";
    public static final String APP = "app";

    public Task {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(status, "status");
        title = title == null || title.isBlank() ? TaskText.title(goal) : title;
        language = language == null || language.isBlank() ? "en" : language;
        origin = origin == null ? APP : origin;
        model = model == null ? "" : model;
        reasoning = reasoning == null ? "" : reasoning;
        think = think == null ? "" : think;
        budget = budget == null ? TaskBudget.DEFAULT : budget;
        spent = spent == null ? TaskSpent.NONE : spent;
        activity = activity == null ? "" : activity;
        summary = summary == null ? "" : summary;
        result = result == null ? "" : result;
        error = error == null ? "" : error;
        announced = announced == null ? "" : announced;
    }

    /** A new task, waiting its turn. */
    public static Task queued(String id, String goal, String language, String origin, double now, TaskBudget budget) {
        return new Task(id, goal.strip(), null, language, origin, TaskStatus.QUEUED, now, null, null, null, null, null,
                budget, TaskSpent.NONE, "Waiting its turn", null, null, null, null, null);
    }

    public Task running(double now, String model, String reasoning, String think) {
        return new Task(id, goal, title, language, origin, TaskStatus.RUNNING, createdAt, now, null, model, reasoning, think,
                budget, spent, "Starting", summary, result, error, announced, announcedAt);
    }

    /** Waiting for the owner's approval ({@code needs_approval}), or running again after it. */
    public Task awaiting(boolean waiting, String doing) {
        TaskStatus to = waiting ? TaskStatus.NEEDS_APPROVAL : TaskStatus.RUNNING;
        if (status.finished()) {
            throw new IllegalArgumentException("a finished task does not wait: " + id);
        }
        return new Task(id, goal, title, language, origin, to, createdAt, startedAt, finishedAt, model, reasoning, think,
                budget, spent, doing, summary, result, error, announced, announcedAt);
    }

    public Task progress(String doing, TaskSpent s) {
        return new Task(id, goal, title, language, origin, status, createdAt, startedAt, finishedAt, model, reasoning, think,
                budget, s, doing, summary, result, error, announced, announcedAt);
    }

    public Task finished(TaskStatus how, double now, String sum, String res, String err, TaskSpent s) {
        if (!how.finished()) {
            throw new IllegalArgumentException(how + " is not an end");
        }
        return new Task(id, goal, title, language, origin, how, createdAt, startedAt, now, model, reasoning, think, budget,
                s == null ? spent : s, "", sum, res, err, announced, announcedAt);
    }

    public Task announced(String how, double now) {
        return new Task(id, goal, title, language, origin, status, createdAt, startedAt, finishedAt, model, reasoning, think,
                budget, spent, activity, summary, result, error, how, now);
    }

    /** Finished with something to tell (done or failed), not told yet. */
    public boolean toTell() {
        return (status == TaskStatus.DONE || status == TaskStatus.FAILED) && announced.isEmpty();
    }

    /** What lists show: everything but the result. */
    public Map<String, Object> toSummaryMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("goal", goal);
        m.put("status", status.id());
        m.put("language", language);
        m.put("origin", origin);
        m.put("created_at", createdAt);
        m.put("started_at", startedAt);
        m.put("finished_at", finishedAt);
        m.put("model", model);
        m.put("reasoning", reasoning);
        m.put("think", think);
        m.put("budget", budget.toMap());
        m.put("spent", spent.toMap());
        m.put("activity", activity);
        m.put("summary", summary);
        m.put("error", error);
        m.put("announced", announced);
        m.put("announced_at", announcedAt);
        m.put("has_result", !result.isEmpty());
        return m;
    }

    /** Everything, the result included. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = toSummaryMap();
        m.put("result", result);
        return m;
    }
}
