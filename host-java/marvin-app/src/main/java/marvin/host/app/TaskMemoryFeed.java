// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Map;

import marvin.host.application.conversation.port.in.TaskControl;
import marvin.host.application.conversation.port.out.TaskListener;
import marvin.host.application.memory.port.in.RecordMemory;
import marvin.host.domain.memory.EventFeeds;

/**
 * What a background task found goes to memory's event log when it finishes (docs/design.md 6.1: "results come back as an
 * event in the log, so memory learns from them"). Only ports meet here: the task is read through the tasks' in-port, the
 * event written through memory's; the log keeps it once whatever how often the task is published (its reference is the
 * task's id).
 */
final class TaskMemoryFeed implements TaskListener {
    private final TaskControl tasks;
    private final RecordMemory memory;

    TaskMemoryFeed(TaskControl tasks, RecordMemory memory) {
        this.tasks = tasks;
        this.memory = memory;
    }

    @Override
    public void onTask(String kind, Map<String, Object> payload) {
        if (!"task".equals(kind)) {
            return;
        }
        Object status = payload.get("status");
        if (!"done".equals(status) && !"failed".equals(status) || !(payload.get("id") instanceof String id)) {
            return;
        }
        tasks.task(id).filter(t -> t.finishedAt() != null).flatMap(t -> EventFeeds.task(t.id(), t.finishedAt(),
                t.status().id(), t.title(), t.goal(), t.summary(), t.result(), t.error(), t.origin()))
                .ifPresent(memory::record);
    }
}
