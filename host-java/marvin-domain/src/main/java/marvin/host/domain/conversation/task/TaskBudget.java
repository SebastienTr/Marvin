// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a background task may spend (docs/design.md 6.2): requests to the model ({@code steps}), wall time, and tokens the
 * model writes (its thinking included: that is where a 27B model spends its time). Spending one ends the task as failed,
 * with what it had. The defaults suit a 27B model thinking hard on a laptop: about two minutes per step.
 */
public record TaskBudget(int steps, double seconds, int tokens) {
    public static final TaskBudget DEFAULT = new TaskBudget(12, 1200, 40_000);

    public TaskBudget {
        if (steps < 1 || seconds <= 0 || tokens < 1) {
            throw new IllegalArgumentException("a budget allows at least one step, some time and some tokens");
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("steps", steps);
        m.put("seconds", seconds);
        m.put("tokens", tokens);
        return m;
    }

    public static TaskBudget of(Map<?, ?> m) {
        if (m == null) {
            return DEFAULT;
        }
        return new TaskBudget(m.get("steps") instanceof Number n ? n.intValue() : DEFAULT.steps,
                m.get("seconds") instanceof Number n ? n.doubleValue() : DEFAULT.seconds,
                m.get("tokens") instanceof Number n ? n.intValue() : DEFAULT.tokens);
    }
}
