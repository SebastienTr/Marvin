// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.Map;

/**
 * Told of background tasks as they change: {@code task} (the task, as lists show it, without its result) and
 * {@code task_step} ({@code {id, step}}: one step it took). The app's event stream carries both.
 */
public interface TaskListener {
    void onTask(String kind, Map<String, Object> payload);
}
