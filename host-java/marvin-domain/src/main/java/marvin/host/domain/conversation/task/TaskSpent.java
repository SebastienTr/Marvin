// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a task spent so far.
 *
 * @param steps        requests to the model that counted (a request cut short for the conversation does not)
 * @param toolCalls    tools it ran
 * @param tokens       tokens the model wrote, thinking included (Ollama's {@code eval_count}, else an estimate)
 * @param promptTokens prompt tokens Ollama evaluated (cached ones not counted)
 * @param seconds      time since it started, pauses for the conversation included
 */
public record TaskSpent(int steps, int toolCalls, int tokens, int promptTokens, double seconds) {
    public static final TaskSpent NONE = new TaskSpent(0, 0, 0, 0, 0);

    public TaskSpent plusStep(int written, int prompt) {
        return new TaskSpent(steps + 1, toolCalls, tokens + Math.max(0, written), promptTokens + Math.max(0, prompt), seconds);
    }

    public TaskSpent plusTool() {
        return new TaskSpent(steps, toolCalls + 1, tokens, promptTokens, seconds);
    }

    public TaskSpent at(double s) {
        return new TaskSpent(steps, toolCalls, tokens, promptTokens, s);
    }

    /** Which budget is spent ({@code steps}, {@code time}, {@code tokens}), or {@code null}. */
    public String exceeds(TaskBudget b) {
        if (steps >= b.steps()) {
            return "steps";
        }
        if (seconds >= b.seconds()) {
            return "time";
        }
        return tokens >= b.tokens() ? "tokens" : null;
    }

    /** Whether the next step should be the last: one step left, or little time or few tokens left. */
    public boolean nearlySpent(TaskBudget b) {
        return steps >= b.steps() - 1 || seconds >= b.seconds() * 0.85 || tokens >= b.tokens() * 0.85;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("steps", steps);
        m.put("tool_calls", toolCalls);
        m.put("tokens", tokens);
        m.put("prompt_tokens", promptTokens);
        m.put("seconds", Math.round(seconds * 10) / 10.0);
        return m;
    }

    public static TaskSpent of(Map<?, ?> m) {
        if (m == null) {
            return NONE;
        }
        return new TaskSpent(i(m, "steps"), i(m, "tool_calls"), i(m, "tokens"), i(m, "prompt_tokens"),
                m.get("seconds") instanceof Number n ? n.doubleValue() : 0);
    }

    private static int i(Map<?, ?> m, String k) {
        return m.get(k) instanceof Number n ? n.intValue() : 0;
    }
}
