// SPDX-License-Identifier: MIT
package marvin.host.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.application.conversation.task.TaskService;
import marvin.host.application.memory.port.in.RecordMemory;

/**
 * The long-term brain's wiring (docs/design.md 6.1, 8): what background tasks found goes to memory's event log.
 */
@Configuration(proxyBeanMethods = false)
public class SoulWiring {

    /** A finished task's result, in memory's log. */
    @Bean
    public TaskResultsInMemory taskResultsInMemory(TaskService tasks, RecordMemory memory) {
        tasks.addListener(new TaskMemoryFeed(tasks, memory));
        return new TaskResultsInMemory();
    }

    /** A marker: task results reach memory. */
    public record TaskResultsInMemory() {
    }
}
