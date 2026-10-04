// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;
import java.util.Optional;

import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskStep;

/** Background tasks and their steps, kept across restarts (the conversation's schema: {@code task}, {@code task_step}). */
public interface TaskStore {

    /** Inserts or replaces the task. */
    void save(Task task);

    /** Adds a step to a task (its number is new). */
    void addStep(String taskId, TaskStep step);

    Optional<Task> get(String id);

    /** The task's steps, in order. */
    List<TaskStep> steps(String id);

    /** The latest tasks, newest first. */
    List<Task> recent(int limit);

    /**
     * After a restart: every task still queued or running is now {@code interrupted}, with {@code error}, at {@code at}.
     *
     * @return how many
     */
    int interruptUnfinished(double at, String error);
}
