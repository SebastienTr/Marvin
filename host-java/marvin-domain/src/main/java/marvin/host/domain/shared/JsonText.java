// SPDX-License-Identifier: MIT
package marvin.host.domain.shared;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON for the domain, which has no JSON library: values are {@code Map} (insertion order), {@code List},
 * {@code String}, {@code Long}, {@code Double}, {@code Boolean} and {@code null}.
 *
 * <p>{@link #decode(String, int)} is Python's {@code json.JSONDecoder.raw_decode} (one value from a position,
 * and where it ends); {@link #write(Object)} is Python's {@code json.dumps(value, ensure_ascii=False)}
 * ({@code ", "} and {@code ": "} separators, floats as {@code repr}), so what a model reads is what the
 * Python host gave it.
 */
public final class JsonText {

    private JsonText() {
    }

    /** One decoded value and the index just after it. */
    public record Decoded(Object value, int end) {
    }

    /** A malformed document. */
    public static final class JsonException extends IllegalArgumentException {
        JsonException(String message) {
            super(message);
        }
    }

    /** The whole of {@code text} as one value (surrounding whitespace allowed). */
    public static Object parse(String text) {
        int i = skip(text, 0);
        Decoded d = decode(text, i);
        if (skip(text, d.end()) != text.length()) {
            throw new JsonException("extra data");
        }
        return d.value();
    }

    /** One value starting exactly at {@code start}, as Python's {@code raw_decode}. */
    public static Decoded decode(String text, int start) {
        return new Parser(text).value(start);
    }

    private static int skip(String s, int i) {
        while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }

    private record Parser(String s) {

        Decoded value(int i) {
            if (i >= s.length()) {
                throw new JsonException("end of text");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(i);
                case '[':
                    return array(i);
                case '"':
                    return string(i);
                case 't':
                    return literal(i, "true", Boolean.TRUE);
                case 'f':
                    return literal(i, "false", Boolean.FALSE);
                case 'n':
                    return literal(i, "null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number(i);
                    }
                    throw new JsonException("unexpected character at " + i);
            }
        }

        private Decoded literal(int i, String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw new JsonException("unexpected character at " + i);
            }
            return new Decoded(v, i + word.length());
        }

        private Decoded object(int i) {
            Map<String, Object> m = new LinkedHashMap<>();
            i = skip(s, i + 1);
            if (i < s.length() && s.charAt(i) == '}') {
                return new Decoded(m, i + 1);
            }
            while (true) {
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new JsonException("expected a key at " + i);
                }
                Decoded k = string(i);
                i = skip(s, k.end());
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new JsonException("expected ':' at " + i);
                }
                Decoded v = value(skip(s, i + 1));
                m.put((String) k.value(), v.value());
                i = skip(s, v.end());
                if (i < s.length() && s.charAt(i) == ',') {
                    i = skip(s, i + 1);
                } else if (i < s.length() && s.charAt(i) == '}') {
                    return new Decoded(m, i + 1);
                } else {
                    throw new JsonException("expected ',' or '}' at " + i);
                }
            }
        }

        private Decoded array(int i) {
            List<Object> l = new ArrayList<>();
            i = skip(s, i + 1);
            if (i < s.length() && s.charAt(i) == ']') {
                return new Decoded(l, i + 1);
            }
            while (true) {
                Decoded v = value(i);
                l.add(v.value());
                i = skip(s, v.end());
                if (i < s.length() && s.charAt(i) == ',') {
                    i = skip(s, i + 1);
                } else if (i < s.length() && s.charAt(i) == ']') {
                    return new Decoded(l, i + 1);
                } else {
                    throw new JsonException("expected ',' or ']' at " + i);
                }
            }
        }

        private Decoded string(int i) {
            StringBuilder b = new StringBuilder();
            int j = i + 1;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '"') {
                    return new Decoded(b.toString(), j + 1);
                }
                if (c == '\\') {
                    if (j + 1 >= s.length()) {
                        break;
                    }
                    char e = s.charAt(j + 1);
                    switch (e) {
                        case '"', '\\', '/' -> b.append(e);
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if (j + 6 > s.length()) {
                                throw new JsonException("bad escape at " + j);
                            }
                            try {
                                b.append((char) Integer.parseInt(s.substring(j + 2, j + 6), 16));
                            } catch (NumberFormatException ex) {
                                throw new JsonException("bad escape at " + j);
                            }
                            j += 4;
                        }
                        default -> throw new JsonException("bad escape at " + j);
                    }
                    j += 2;
                    continue;
                }
                if (c < 0x20) {
                    throw new JsonException("control character in a string at " + j);
                }
                b.append(c);
                j++;
            }
            throw new JsonException("unterminated string at " + i);
        }

        private Decoded number(int i) {
            int j = i;
            if (s.charAt(j) == '-') {
                j++;
            }
            int digits = j;
            while (j < s.length() && Character.isDigit(s.charAt(j)) && s.charAt(j) < 128) {
                j++;
            }
            if (j == digits) {
                throw new JsonException("bad number at " + i);
            }
            boolean real = false;
            if (j + 1 < s.length() && s.charAt(j) == '.' && Character.isDigit(s.charAt(j + 1))) {
                real = true;
                j++;
                while (j < s.length() && Character.isDigit(s.charAt(j)) && s.charAt(j) < 128) {
                    j++;
                }
            }
            if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                int k = j + 1;
                if (k < s.length() && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                    k++;
                }
                if (k < s.length() && Character.isDigit(s.charAt(k))) {
                    real = true;
                    j = k;
                    while (j < s.length() && Character.isDigit(s.charAt(j)) && s.charAt(j) < 128) {
                        j++;
                    }
                }
            }
            String t = s.substring(i, j);
            if (!real) {
                try {
                    return new Decoded(Long.parseLong(t), j);
                } catch (NumberFormatException e) {
                    return new Decoded(new BigDecimal(t).doubleValue(), j);
                }
            }
            return new Decoded(Double.parseDouble(t), j);
        }
    }

    // ------------------------------------------------------------------ writing

    /** Python's {@code json.dumps(value, ensure_ascii=False)}. */
    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        switch (v) {
            case null -> b.append("null");
            case String s -> quote(b, s);
            case Boolean x -> b.append(x ? "true" : "false");
            case Double d -> b.append(repr(d));
            case Float f -> b.append(repr(f.doubleValue()));
            case Number n -> b.append(n.longValue());
            case Map<?, ?> m -> {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        b.append(", ");
                    }
                    first = false;
                    quote(b, String.valueOf(e.getKey()));
                    b.append(": ");
                    write(b, e.getValue());
                }
                b.append('}');
            }
            case Iterable<?> l -> {
                b.append('[');
                boolean first = true;
                for (Object o : l) {
                    if (!first) {
                        b.append(", ");
                    }
                    first = false;
                    write(b, o);
                }
                b.append(']');
            }
            default -> quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    /** Python's {@code repr(float)}: the shortest text that reads back as the same double. */
    public static String repr(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0.0" : "0.0";
        }
        String s = Double.toString(d);
        double a = Math.abs(d);
        if (a >= 1e16 || a < 1e-4) {
            int e = s.indexOf('E');
            String mantissa = s.substring(0, e);
            int exp = Integer.parseInt(s.substring(e + 1));
            if (mantissa.endsWith(".0")) {
                mantissa = mantissa.substring(0, mantissa.length() - 2);
            }
            return mantissa + (exp < 0 ? "e-" : "e+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        }
        String plain = new BigDecimal(s).toPlainString();
        return plain.indexOf('.') < 0 ? plain + ".0" : plain;
    }
}
