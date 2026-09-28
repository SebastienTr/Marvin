// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** JSON columns as JSON-like Java values (maps keep their key order). */
final class JsonValues {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonValues() {
    }

    static String write(Object value) {
        return JSON.writeValueAsString(value);
    }

    /** A JSON object as a map; an empty map when the text is not an object (a row from an older version). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object v = JSON.readValue(json, Object.class);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        } catch (JacksonException e) {
            return new LinkedHashMap<>();
        }
    }

    /** Any JSON value; {@code null} when it does not parse. */
    static Object read(String json) {
        try {
            return JSON.readValue(json, Object.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Whether the text is a JSON object. */
    static boolean isObject(String json) {
        try {
            return JSON.readTree(json).isObject();
        } catch (JacksonException | IllegalArgumentException e) {
            return false;
        }
    }
}
