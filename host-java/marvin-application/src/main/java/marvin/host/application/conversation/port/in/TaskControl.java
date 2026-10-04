// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.in;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskStep;

/** Background tasks for the app: the list, one task with its steps, starting one, cancelling one, and their settings. */
public interface TaskControl {

    /** The latest tasks, newest first (running and queued ones always included). */
    List<Task> tasks(int limit);

    Optional<Task> task(String id);

    List<TaskStep> steps(String id);

    /**
     * Starts a task the owner typed in the app.
     *
     * @throws IllegalArgumentException an empty or too long goal
     * @throws Refused                  tasks are switched off, or too many wait already
     */
    Task start(String goal, String language);

    /** Cancels a queued or running task (a finished one is returned as it is). */
    Optional<Task> cancel(String id);

    /**
     * The settings ({@code reasoning}, {@code task_reasoning}, {@code tasks}), the model with its size and how it thinks,
     * the budget, the recommendation, and what the model server can run at once.
     */
    Map<String, Object> settings();

    /** Saves settings ({@code reasoning}, {@code task_reasoning}, {@code tasks}); nothing restarts. */
    Map<String, Object> updateSettings(Map<String, ?> update);

    /** Why a task was not started, in words for the owner. */
    class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }
}
