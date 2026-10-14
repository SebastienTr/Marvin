// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.Map;

/**
 * Told of every change of an approval: {@code {id, task_id, state, tool, summary}}. The app's event stream carries it as
 * {@code approval}.
 */
@FunctionalInterface
public interface ApprovalListener {
    void onApproval(Map<String, Object> payload);
}
