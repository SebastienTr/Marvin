// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import marvin.host.domain.conversation.task.TaskText;

/**
 * The tools around background tasks: {@code start_task} and {@code task_result} in the conversation, {@code final_answer}
 * in a task. Fixed descriptions: they are part of the cached system prompt.
 */
public final class TaskTools {
    public static final String START = "start_task";
    public static final String RESULT = "task_result";
    public static final String FINAL = "final_answer";
    /** The result of a task, as the final answer may give it. */
    public static final int MAX_RESULT = 20_000;

    private TaskTools() {
    }

    /** The conversation starts a task; the turn ends with Marvin saying it will take care of it. */
    public static ToolSpec start() {
        return new ToolSpec(START, "Start a background task for work in several steps that you should not answer at once: "
                + "prepare, compare, look into, research, summarize a week or a period, plan. You work on it with your tools "
                + "while the conversation goes on, and tell the person when it is ready. Also when they ask you to do "
                + "something in the background. Not for a question you can answer now.",
                object(Map.of("goal", string("the task, complete enough to work from alone, in the person's words and language",
                        TaskText.MAX_GOAL)), List.of("goal")), 5.0, false, true, false);
    }

    /** How tasks are going, and what they found. */
    public static ToolSpec result() {
        return new ToolSpec(RESULT, "How your background tasks are going and what they found: the running ones, and the "
                + "latest results with their summary. Use it when the person asks about a task or its result.",
                object(Map.of("query", string("which task, a few words; leave it out for the latest", 200)), List.of()),
                5.0, false, false, false);
    }

    /** A task's last call: what Marvin says, and the result. */
    public static ToolSpec finalAnswer() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("summary", string("one or two short sentences Marvin says aloud: plain words, no markdown, numbers written "
                + "as they are said", 400));
        props.put("result", string("the full result in Markdown, concise and structured, saying what could not be checked",
                MAX_RESULT));
        return new ToolSpec(FINAL, "Finish the task: give the result. Call it once, at the end.",
                object(props, List.of("summary", "result")), 5.0, false, false, false);
    }

    private static Map<String, Object> string(String description, int maxLength) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        m.put("maxLength", maxLength);
        return m;
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        return m;
    }
}
