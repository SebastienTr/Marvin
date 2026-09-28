// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the model is told about a tool, and how it is run.
 *
 * @param name         what the model calls (letters, digits, underscores)
 * @param description  when to use it, for the model
 * @param parameters   a JSON schema of type object ({@code properties}, {@code required})
 * @param timeoutS     seconds before the call is given up (the model is told it timed out)
 * @param online       it needs the internet (switched off with the Internet setting)
 * @param wantsContext it is given the conversation's language
 * @param filler       say a short "let me check" while it runs; {@code null}: when online
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters, double timeoutS,
                       boolean online, boolean wantsContext, Boolean filler) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || !name.replace("_", "").chars().allMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException("tool names are letters, digits and underscores: " + name);
        }
        if (!"object".equals(parameters.get("type"))) {
            throw new IllegalArgumentException(name + ": parameters must be a JSON schema of type object");
        }
    }

    public boolean saysFiller() {
        return filler == null ? online : filler;
    }

    /**
     * Ollama's (OpenAI-style) function schema, with every map's keys sorted: the same bytes whatever order
     * the tool's author wrote it in (the tool definitions are part of the prompt the model server caches).
     */
    public Map<String, Object> schema() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "function");
        out.put("function", fn);
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) canonical(out);
        return c;
    }

    static Object canonical(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), canonical(v)));
            return new LinkedHashMap<>(out);
        }
        if (o instanceof List<?> l) {
            return l.stream().map(ToolSpec::canonical).toList();
        }
        return o;
    }
}
