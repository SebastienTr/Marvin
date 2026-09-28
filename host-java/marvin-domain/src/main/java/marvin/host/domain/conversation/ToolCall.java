// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.LinkedHashMap;
import java.util.Map;

import marvin.host.domain.shared.JsonText;

/**
 * The model asks for a tool (the Python host's {@code llm.ToolCall}).
 *
 * @param name      the tool
 * @param arguments a {@code Map} (a JSON string from some models is parsed; what cannot be parsed is kept
 *                  as the string)
 * @param id        when the model server gives one, else {@code null}
 */
public record ToolCall(String name, Object arguments, String id) {

    /**
     * From Ollama's (or OpenAI's) {@code {"function": {"name", "arguments"}}}, or a bare
     * {@code {"name", "arguments" | "parameters"}}; {@code null} if it has no name.
     */
    public static ToolCall parse(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        Map<?, ?> fn = m.get("function") instanceof Map<?, ?> f ? f : m;
        if (!(fn.get("name") instanceof String name) || name.isEmpty()) {
            return null;
        }
        Object args = fn.containsKey("arguments") ? fn.get("arguments")
                : fn.containsKey("parameters") ? fn.get("parameters") : Map.of();
        if (args instanceof String s) {
            try {
                args = s.isBlank() ? new LinkedHashMap<>() : JsonText.parse(s);
            } catch (IllegalArgumentException e) {
                // kept as the string: the tool answers that its arguments are not valid JSON
            }
        }
        if (args == null) {
            args = new LinkedHashMap<>();
        }
        return new ToolCall(name, args, m.get("id") instanceof String id ? id : null);
    }

    /** The arguments as a map, empty when they are not one. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> argumentsMap() {
        return arguments instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
