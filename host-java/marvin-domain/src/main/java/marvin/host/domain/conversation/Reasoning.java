// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.List;
import java.util.Locale;

/**
 * How hard the model thinks before it answers: Ollama's {@code think}. Off for spoken turns by default (every token of
 * thinking is silence before the first word); high for background tasks, where getting it right matters more than
 * answering at once (docs/intelligence.md, "Thinking and background tasks").
 *
 * <p>What reaches Ollama depends on what the model says it accepts ({@link ModelThinking}): Qwen3.8 takes {@code false},
 * {@code "low"}, {@code "medium"} and {@code "xhigh"}, and Ollama turns any other level, {@code "high"} included, and
 * {@code true}, into its default for that model ({@code "medium"}). So {@link #HIGH} asks for the strongest level the
 * model advertises short of an unbounded one: {@code "high"} where it exists (gpt-oss), else {@code "xhigh"}, else
 * {@code "max"}.
 */
public enum Reasoning {
    OFF, LOW, MEDIUM, HIGH;

    /** The settings' names, in order. */
    public static final List<String> NAMES = List.of("off", "low", "medium", "high");

    /** The setting's name ({@code off}, {@code low}, {@code medium}, {@code high}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The level a setting names, or {@code null} when it names none. */
    public static Reasoning parse(Object value) {
        if (value instanceof Boolean b) {
            return b ? HIGH : OFF;
        }
        if (!(value instanceof String s)) {
            return null;
        }
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "off", "none", "false" -> OFF;
            case "low" -> LOW;
            case "medium" -> MEDIUM;
            case "high", "xhigh", "max", "true" -> HIGH;
            default -> null;
        };
    }

    /**
     * Ollama's {@code think} for this level and a model: {@link Boolean#FALSE} when off or when the model cannot think,
     * a level the model advertises when it lists some, {@link Boolean#TRUE} when it takes only booleans, and the plain
     * level name when the server does not say (an older Ollama: it checks the name and maps it to thinking on).
     */
    public Object thinkValue(ModelThinking model) {
        if (this == OFF || model.thinks() == Boolean.FALSE) {
            return Boolean.FALSE;
        }
        if (model.values().isEmpty()) {
            return id();
        }
        List<String> wanted = switch (this) {
            case LOW -> List.of("low", "medium");
            case MEDIUM -> List.of("medium");
            default -> List.of("high", "xhigh", "max");
        };
        for (String w : wanted) {
            if (model.values().contains(w)) {
                return w;
            }
        }
        if (model.values().contains(Boolean.TRUE)) {
            return Boolean.TRUE;
        }
        // levels only, none of the wanted ones: the strongest named one for HIGH, else the model's default
        if (this == HIGH) {
            for (int i = model.values().size() - 1; i >= 0; i--) {
                if (model.values().get(i) instanceof String s) {
                    return s;
                }
            }
        }
        Object d = model.dflt();
        return d instanceof String || d instanceof Boolean ? d : Boolean.TRUE;
    }
}
