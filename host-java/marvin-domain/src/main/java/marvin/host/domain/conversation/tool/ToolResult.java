// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import marvin.host.domain.shared.JsonText;
import marvin.host.domain.shared.PyNumbers;

/**
 * One tool call, done. {@code content} is what the model reads; {@link #record()} what the app's reply
 * inspector shows.
 */
public record ToolResult(String name, Map<String, Object> arguments, boolean ok, String content, double seconds,
                         String error) {
    /** Characters of a result, as recorded for the app. */
    public static final int SUMMARY_CHARS = 280;

    public static ToolResult success(String name, Map<String, Object> arguments, String content, double seconds) {
        return new ToolResult(name, arguments, true, content, seconds, "");
    }

    public static ToolResult failure(String name, Map<String, Object> arguments, String error, double seconds) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("error", error);
        return new ToolResult(name, arguments, false, JsonText.write(e), seconds, error);
    }

    public Map<String, Object> record() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("arguments", arguments);
        out.put("ok", ok);
        out.put("seconds", PyNumbers.round(seconds, 3));
        if (ok) {
            out.put("result", shorten(content, SUMMARY_CHARS));
        } else {
            out.put("error", error);
        }
        return out;
    }

    /** At most {@code n} characters, with an ellipsis when cut. */
    public static String shorten(String text, int n) {
        if (text.codePointCount(0, text.length()) <= n) {
            return text;
        }
        int end = text.offsetByCodePoints(0, n - 1);
        return text.substring(0, end).stripTrailing() + "…";
    }
}
