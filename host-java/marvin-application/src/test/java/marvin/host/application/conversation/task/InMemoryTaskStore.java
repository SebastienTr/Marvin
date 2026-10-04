// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import marvin.host.application.conversation.port.out.TaskStore;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.task.TaskStep;

/** The task store in memory, for tests. */
public final class InMemoryTaskStore implements TaskStore {
    public final Map<String, Task> tasks = new ConcurrentHashMap<>();
    public final Map<String, List<TaskStep>> steps = new ConcurrentHashMap<>();

    @Override
    public void save(Task task) {
        tasks.put(task.id(), task);
    }

    @Override
    public void addStep(String taskId, TaskStep step) {
        steps.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(step);
    }

    @Override
    public Optional<Task> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public List<TaskStep> steps(String id) {
        return List.copyOf(steps.getOrDefault(id, List.of()));
    }

    @Override
    public List<Task> recent(int limit) {
        List<Task> all = new ArrayList<>(tasks.values());
        all.sort(Comparator.comparingDouble(Task::createdAt).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public int interruptUnfinished(double at, String error) {
        int n = 0;
        for (Task t : List.copyOf(tasks.values())) {
            if (!t.status().finished()) {
                tasks.put(t.id(), t.finished(TaskStatus.INTERRUPTED, at, "", "", error, null));
                n++;
            }
        }
        return n;
    }
}
