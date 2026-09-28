// SPDX-License-Identifier: MIT
package marvin.host.domain.settings;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Python's {@code repr()} of a JSON value, for error messages that quote what was sent. */
final class PyRepr {
    private PyRepr() {
    }

    static String of(Object v) {
        return switch (v) {
            case null -> "None";
            case Boolean b -> b ? "True" : "False";
            case String s -> string(s);
            case Double d -> d == Math.rint(d) && Double.isFinite(d) && Math.abs(d) < 1e16
                    ? String.valueOf(d.longValue()) + ".0" : String.valueOf(d);
            case Number n -> n.toString();
            case List<?> l -> l.stream().map(PyRepr::of).collect(Collectors.joining(", ", "[", "]"));
            case Map<?, ?> m -> m.entrySet().stream().map(e -> of(String.valueOf(e.getKey())) + ": " + of(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
            default -> String.valueOf(v);
        };
    }

    private static String string(String s) {
        char q = s.contains("'") && !s.contains("\"") ? '"' : '\'';
        StringBuilder b = new StringBuilder().append(q);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c == q) {
                        b.append('\\').append(c);
                    } else if (c < 0x20 || c == 0x7f) {
                        b.append(String.format(java.util.Locale.ROOT, "\\x%02x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append(q).toString();
    }
}
