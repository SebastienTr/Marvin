// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.adapter.llm.OllamaLanguageModel;
import marvin.host.adapter.persistence.JdbcTaskStore;
import marvin.host.adapter.persistence.VoiceSettingsFile;
import marvin.host.adapter.web.EventHub;
import marvin.host.application.conversation.VoiceService;
import marvin.host.application.conversation.task.ModelLane;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.application.settings.SettingsService;
import marvin.host.application.system.port.in.HostLog;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * Background tasks (docs/intelligence.md, "Thinking and background tasks"): the lane the voice and the tasks share the
 * model on, the task service with its store, the app's stream and the Log panel, and the conversation that starts them,
 * says them, and gives way to nothing.
 */
@Configuration(proxyBeanMethods = false)
public class TaskWiring {

    @Bean
    public ModelLane modelLane(@Value("${marvin.llm.parallel:1}") int parallel, Clocks clocks) {
        return new ModelLane(parallel, clocks::monotonicSeconds);
    }

    @Bean(destroyMethod = "close")
    public TaskService taskService(JdbcTaskStore store, OllamaLanguageModel model, ModelLane lane, VoiceSettingsFile settings,
                                   Clocks clocks, LocalDays days, EventHub hub, HostLog hostLog, VoiceService voice,
                                   SettingsService appSettings) {
        TaskService tasks = new TaskService(store, model, lane, settings, clocks, days);
        tasks.addListener(hub);
        tasks.addListener((kind, payload) -> logLine(hostLog, kind, payload));
        voice.setTasks(tasks);
        // quiet hours: Marvin says nothing about a task on its own then
        voice.setQuiet(() -> appSettings.current().quietHours().contains(days.minuteOfDay(clocks.wallSeconds())));
        return tasks;
    }

    /** A task's start and end in the Log panel, under Voice. */
    static void logLine(HostLog hostLog, String kind, Map<String, Object> payload) {
        if (!"task".equals(kind)) {
            return;
        }
        String title = String.valueOf(payload.get("title"));
        switch (String.valueOf(payload.get("status"))) {
            case "queued" -> hostLog.add("voice", "info", "Task queued: " + title, Map.of());
            case "done" -> hostLog.add("voice", "info", "Task done: " + title, Map.of());
            case "failed" -> hostLog.add("voice", "warning", "Task failed: " + title + ". " + payload.get("error"), Map.of());
            case "cancelled" -> hostLog.add("voice", "info", "Task cancelled: " + title, Map.of());
            default -> {
                // running and its progress: the Tasks panel shows them
            }
        }
    }
}
