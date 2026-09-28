// SPDX-License-Identifier: MIT
package marvin.host.app.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON shapes as the contract generator writes them (api_snapshots.py): every value replaced by its
 * type, a list by the distinct shapes of its items. Two shapes are compatible when their objects have the
 * same keys and their values are compatible; {@code null} is compatible with anything (the snapshot's
 * values are volatile: a field that was a number may be null now, and the other way round); an empty list
 * with any list.
 */
final class Shapes {
    private Shapes() {
    }

    static JsonNode of(JsonNode v) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (v == null || v.isNull()) {
            return f.stringNode("null");
        }
        if (v.isObject()) {
            ObjectNode o = f.objectNode();
            for (Map.Entry<String, JsonNode> e : v.properties()) {
                o.set(e.getKey(), of(e.getValue()));
            }
            return o;
        }
        if (v.isArray()) {
            var a = f.arrayNode();
            List<JsonNode> seen = new ArrayList<>();
            for (JsonNode item : v) {
                JsonNode s = of(item);
                if (!seen.contains(s)) {
                    seen.add(s);
                    a.add(s);
                }
            }
            return a;
        }
        if (v.isNumber()) {
            return f.stringNode("number");
        }
        if (v.isBoolean()) {
            return f.stringNode("boolean");
        }
        return f.stringNode("string");
    }

    /** Differences between a Java shape and a golden one ({@code path: why}); empty when compatible. */
    static List<String> diff(JsonNode java, JsonNode golden, String path) {
        List<String> out = new ArrayList<>();
        diff(java, golden, path, out);
        return out;
    }

    private static void diff(JsonNode j, JsonNode g, String path, List<String> out) {
        if (isNull(j) || isNull(g)) {
            return;
        }
        if (j.isString() && g.isString()) {
            if (!j.asString().equals(g.asString())) {
                out.add(path + ": " + j.asString() + " instead of " + g.asString());
            }
            return;
        }
        if (j.isObject() && g.isObject()) {
            Set<String> jk = keys(j);
            Set<String> gk = keys(g);
            if (!jk.equals(gk)) {
                Set<String> extra = new TreeSet<>(jk);
                extra.removeAll(gk);
                Set<String> missing = new TreeSet<>(gk);
                missing.removeAll(jk);
                out.add(path + ": keys differ, extra " + extra + ", missing " + missing);
                return;
            }
            for (String k : jk) {
                diff(j.get(k), g.get(k), path + "." + k, out);
            }
            return;
        }
        if (j.isArray() && g.isArray()) {
            if (j.isEmpty() || g.isEmpty()) {
                return;
            }
            for (JsonNode item : j) {
                boolean ok = false;
                List<String> best = null;
                for (JsonNode candidate : g) {
                    List<String> d = diff(item, candidate, path + "[]");
                    if (d.isEmpty()) {
                        ok = true;
                        break;
                    }
                    best = best == null || d.size() < best.size() ? d : best;
                }
                if (!ok) {
                    out.addAll(best);
                    return;
                }
            }
            return;
        }
        out.add(path + ": " + kind(j) + " instead of " + kind(g));
    }

    private static boolean isNull(JsonNode n) {
        return n.isString() && n.asString().equals("null");
    }

    private static String kind(JsonNode n) {
        return n.isObject() ? "object" : n.isArray() ? "list" : n.asString();
    }

    private static Set<String> keys(JsonNode n) {
        Set<String> s = new TreeSet<>();
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            s.add(e.getKey());
        }
        return s;
    }
}
