// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import marvin.host.domain.shared.JsonText;

/**
 * A tool's arguments checked against its schema's required keys, types and enums (the Python host's
 * {@code tools.validate_arguments}). Arguments given as a JSON string are parsed, null values and unknown
 * keys are dropped (small models add them), quoted numbers are accepted, enum strings are matched without
 * case.
 */
public final class ToolArguments {

    private ToolArguments() {
    }

    public static Map<String, Object> validate(Map<String, Object> schema, Object arguments) {
        if (arguments == null || "".equals(arguments)) {
            arguments = Map.of();
        }
        if (arguments instanceof String s) {
            try {
                arguments = JsonText.parse(s);
            } catch (IllegalArgumentException e) {
                throw new ToolError("the arguments are not valid JSON");
            }
        }
        if (!(arguments instanceof Map<?, ?> args)) {
            throw new ToolError("the arguments must be a JSON object");
        }
        Map<?, ?> props = schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : args.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (e.getValue() == null || !props.containsKey(key)) {
                continue;
            }
            out.put(key, check(key, (Map<?, ?>) props.get(key), e.getValue()));
        }
        List<String> missing = new ArrayList<>();
        if (schema.get("required") instanceof List<?> required) {
            for (Object k : required) {
                if (!out.containsKey(String.valueOf(k))) {
                    missing.add(String.valueOf(k));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new ToolError("missing argument" + (missing.size() > 1 ? "s" : "") + ": " + String.join(", ", missing));
        }
        return out;
    }

    private static Object check(String key, Map<?, ?> spec, Object v) {
        Object kind = spec.get("type");
        if ("integer".equals(kind) || "number".equals(kind)) {
            if (v instanceof String s) {
                try {
                    v = Double.parseDouble(s.strip());
                } catch (NumberFormatException e) {
                    throw new ToolError("argument '" + key + "' must be a number");
                }
            }
            if (!(v instanceof Number n)) {
                throw new ToolError("argument '" + key + "' must be a number");
            }
            if ("integer".equals(kind)) {
                double d = n.doubleValue();
                if (d != Math.rint(d)) {
                    throw new ToolError("argument '" + key + "' must be a whole number");
                }
                v = (long) d;
            }
        } else if ("string".equals(kind) && !(v instanceof String)
                || "boolean".equals(kind) && !(v instanceof Boolean)
                || "object".equals(kind) && !(v instanceof Map)
                || "array".equals(kind) && !(v instanceof List)) {
            throw new ToolError("argument '" + key + "' must be a " + kind);
        }
        if (spec.get("enum") instanceof List<?> choices) {
            if (v instanceof String s) {
                for (Object c : choices) {
                    if (c instanceof String cs && cs.toLowerCase(Locale.ROOT).equals(s.strip().toLowerCase(Locale.ROOT))) {
                        v = cs;
                        break;
                    }
                }
            }
            if (!choices.contains(v)) {
                throw new ToolError("argument '" + key + "' must be one of "
                        + String.join(", ", choices.stream().map(String::valueOf).toList()));
            }
        }
        if (v instanceof String s && spec.get("maxLength") instanceof Number max
                && s.codePointCount(0, s.length()) > max.intValue()) {
            throw new ToolError("argument '" + key + "' is too long (at most " + max.intValue() + " characters)");
        }
        return v;
    }
}
