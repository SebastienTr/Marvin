// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.math.BigDecimal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * JSON as the Python host writes it ({@code json.dumps(obj, separators=(",", ":"))}): compact, and
 * floats in Python's {@code repr} form ({@code 1789983060.0907292}, {@code 3000.0}, {@code 1e-05}),
 * not Java's ({@code 1.7899830609072921E9}). Both are valid JSON; this keeps the app's payloads
 * byte-comparable with the Python host's. Non-finite numbers become {@code null}.
 */
public final class PyJson {
    static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new SimpleModule("python-floats")
                    .addSerializer(Double.class, new DoubleSerializer())
                    .addSerializer(double.class, new DoubleSerializer())
                    .addSerializer(Float.class, new FloatSerializer())
                    .addSerializer(float.class, new FloatSerializer()))
            .build();

    private PyJson() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static byte[] bytes(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    /** Python's {@code repr(float)}. */
    public static String repr(double d) {
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

    private static final class DoubleSerializer extends ValueSerializer<Double> {
        @Override
        public void serialize(Double value, JsonGenerator g, SerializationContext ctxt) {
            if (value == null || !Double.isFinite(value)) {
                g.writeNull();
            } else {
                g.writeNumber(repr(value));
            }
        }
    }

    private static final class FloatSerializer extends ValueSerializer<Float> {
        @Override
        public void serialize(Float value, JsonGenerator g, SerializationContext ctxt) {
            if (value == null || !Float.isFinite(value)) {
                g.writeNull();
            } else {
                g.writeNumber(repr(value.doubleValue()));
            }
        }
    }
}
