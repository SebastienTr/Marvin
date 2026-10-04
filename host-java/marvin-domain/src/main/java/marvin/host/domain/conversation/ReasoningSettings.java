// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One model, two regimes (docs/intelligence.md, "Thinking and background tasks"): how hard the conversation's model
 * thinks for spoken and typed turns, how hard it thinks for background tasks, and whether Marvin may start tasks at all.
 * Kept in {@code voice.json} beside the model they apply to; changing them restarts nothing (each turn and each task
 * step reads them).
 *
 * @param chat   for spoken and typed turns: {@link Reasoning#OFF} by default (thinking is silence before the first word)
 * @param tasks  for background tasks: {@link Reasoning#HIGH} by default
 * @param tasksOn whether Marvin may start background tasks (the {@code start_task} tool and the app's form)
 */
public record ReasoningSettings(Reasoning chat, Reasoning tasks, boolean tasksOn) {
    public static final String CHAT_KEY = "reasoning";
    public static final String TASKS_KEY = "task_reasoning";
    public static final String ON_KEY = "tasks";
    /** Their keys in voice.json. */
    public static final List<String> KEYS = List.of(CHAT_KEY, TASKS_KEY, ON_KEY);
    public static final ReasoningSettings DEFAULT = new ReasoningSettings(Reasoning.OFF, Reasoning.HIGH, true);
    /** The model the app recommends for tasks (said, never switched to on its own). */
    public static final String RECOMMENDED_MODEL = "qwen3.8:27b-mxfp8";

    /** The settings voice.json holds, defaults filled in (a value it cannot read is the default). */
    public static ReasoningSettings of(Map<String, Object> settings) {
        Reasoning chat = Reasoning.parse(settings.get(CHAT_KEY));
        Reasoning tasks = Reasoning.parse(settings.get(TASKS_KEY));
        Object on = settings.get(ON_KEY);
        return new ReasoningSettings(chat == null ? DEFAULT.chat : chat, tasks == null ? DEFAULT.tasks : tasks,
                on instanceof Boolean b ? b : DEFAULT.tasksOn);
    }

    /**
     * Checks an update from the app: the keys it changes, as they are saved.
     *
     * @throws VoiceSettings.InvalidVoiceSettingException an unknown key or a bad value (the app shows the message)
     */
    public static Map<String, Object> validate(Map<String, ?> update) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : update.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            switch (k) {
                case CHAT_KEY, TASKS_KEY -> {
                    if (!(v instanceof String s) || !Reasoning.NAMES.contains(s)) {
                        throw new VoiceSettings.InvalidVoiceSettingException(k + " must be one of "
                                + String.join(", ", Reasoning.NAMES));
                    }
                    out.put(k, s);
                }
                case ON_KEY -> {
                    if (!(v instanceof Boolean b)) {
                        throw new VoiceSettings.InvalidVoiceSettingException(k + " must be true or false");
                    }
                    out.put(k, b);
                }
                default -> throw new VoiceSettings.InvalidVoiceSettingException("unknown setting: " + k);
            }
        }
        return out;
    }

    /** For the app, in the keys' order. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(CHAT_KEY, chat.id());
        m.put(TASKS_KEY, tasks.id());
        m.put(ON_KEY, tasksOn);
        return m;
    }
}
