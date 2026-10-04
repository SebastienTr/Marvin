// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing a task did, kept so the app can show exactly what happened (docs/design.md 6.2): a request to the model
 * ({@code model}: its thinking, what it wrote, the tools it called, Ollama's counts), a tool run ({@code tool}: its
 * arguments and result), or a note ({@code note}: paused for the conversation, a budget nearly spent).
 *
 * @param n    its number in the task, from 1
 * @param at   when it ended (Unix seconds)
 * @param data what it holds, as the app shows it
 */
public record TaskStep(int n, double at, String kind, Map<String, Object> data) {
    public static final String MODEL = "model";
    public static final String TOOL = "tool";
    public static final String NOTE = "note";
    /** Characters of thinking kept per step: enough to follow it, not a book. */
    public static final int MAX_THINKING = 12_000;
    /** Characters of a tool's result kept per step. */
    public static final int MAX_RESULT = 4_000;

    public TaskStep {
        data = data == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("at", at);
        m.put("kind", kind);
        m.putAll(data);
        return m;
    }
}
