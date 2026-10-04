// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What a model says of its thinking (Ollama's {@code /api/show}: the {@code thinking} capability, and on recent servers
 * {@code thinking: {values, default}}, the values {@code think} accepts and what it does without one).
 *
 * @param thinks whether it can think at all; {@code null}: the server does not say
 * @param values the values {@code think} accepts ({@code false}, {@code true}, level names), empty when not listed
 * @param dflt   what it does without {@code think} ({@code null}: not said)
 */
public record ModelThinking(Boolean thinks, List<Object> values, Object dflt) {
    /** Nothing known. */
    public static final ModelThinking UNKNOWN = new ModelThinking(null, List.of(), null);
    /** The capability Ollama lists for a model that can think. */
    public static final String CAPABILITY = "thinking";

    public ModelThinking {
        values = values == null ? List.of() : List.copyOf(values);
    }

    /**
     * From {@code /api/show}: its {@code capabilities} ({@code null} when the server lists none) and its
     * {@code thinking} object ({@code null} when absent). A listed {@code values} that holds anything but booleans and
     * non-empty names is ignored, as Ollama ignores an invalid descriptor.
     */
    public static ModelThinking of(Collection<String> capabilities, Object thinking) {
        Boolean thinks = capabilities == null ? null : capabilities.contains(CAPABILITY);
        List<Object> values = new ArrayList<>();
        Object dflt = null;
        if (thinking instanceof Map<?, ?> m) {
            if (m.get("values") instanceof List<?> l) {
                for (Object v : l) {
                    if (v instanceof Boolean || v instanceof String s && !s.isEmpty()) {
                        values.add(v);
                    } else {
                        values.clear();
                        break;
                    }
                }
            }
            Object d = m.get("default");
            dflt = d instanceof Boolean || d instanceof String ? d : null;
        }
        if (!values.isEmpty() && values.stream().noneMatch(v -> Boolean.TRUE.equals(v) || v instanceof String)) {
            thinks = Boolean.FALSE;                 // only false: the server's way of saying it cannot think
        }
        return new ModelThinking(thinks, values, dflt);
    }

    /** The level names it lists ({@code low}, {@code medium}, {@code xhigh} ...), in its order. */
    public List<String> levels() {
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /** For the app: {@code thinks}, {@code levels}, {@code default}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("thinks", thinks);
        m.put("levels", levels());
        m.put("default", dflt);
        return m;
    }
}
