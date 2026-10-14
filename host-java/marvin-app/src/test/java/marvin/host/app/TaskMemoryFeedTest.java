// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.in.TaskControl;
import marvin.host.application.memory.port.in.RecordMemory;
import marvin.host.application.memory.port.out.BackfillSource;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.task.TaskStep;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySources;

/** A finished task reaches memory's log with its result; running ones and other kinds do not. */
class TaskMemoryFeedTest {
    final List<MemoryEvent> recorded = new ArrayList<>();
    Task task = Task.queued("t1", "Compare two laptops", "en", Task.VOICE, 100, TaskBudget.DEFAULT)
            .running(101, "m", "high", "xhigh").finished(TaskStatus.DONE, 160, "The first one.", "## Laptops", "", null);

    final TaskControl tasks = new TaskControl() {
        @Override
        public List<Task> tasks(int limit) {
            return List.of(task);
        }

        @Override
        public Optional<Task> task(String id) {
            return Optional.of(task).filter(t -> t.id().equals(id));
        }

        @Override
        public List<TaskStep> steps(String id) {
            return List.of();
        }

        @Override
        public Task start(String goal, String language) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Task> cancel(String id) {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> settings() {
            return Map.of();
        }

        @Override
        public Map<String, Object> updateSettings(Map<String, ?> update) {
            return Map.of();
        }
    };

    final RecordMemory memory = new RecordMemory() {
        @Override
        public void record(MemoryEvent draft) {
            recorded.add(draft);
        }

        @Override
        public int catchUp(BackfillSource source) {
            return 0;
        }

        @Override
        public boolean flush(long timeoutMs) {
            return true;
        }
    };

    @Test
    void aDoneTaskIsRecordedWithItsResult() {
        TaskMemoryFeed feed = new TaskMemoryFeed(tasks, memory);
        feed.onTask("task_step", Map.of("id", "t1"));
        feed.onTask("task", Map.of("id", "t1", "status", "running"));
        assertThat(recorded).isEmpty();
        feed.onTask("task", Map.of("id", "t1", "status", "done"));
        assertThat(recorded).hasSize(1);
        MemoryEvent e = recorded.getFirst();
        assertThat(e.source()).isEqualTo(MemorySources.TASK);
        assertThat(e.externalRef()).isEqualTo("task:t1");
        assertThat(e.body()).contains("The first one.").contains("## Laptops");
        feed.onTask("task", Map.of("id", "nope", "status", "done"));
        assertThat(recorded).hasSize(1);
    }
}
