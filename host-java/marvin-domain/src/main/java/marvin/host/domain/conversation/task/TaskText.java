// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import marvin.host.domain.conversation.SentenceSplitter;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.tool.ToolResult;

/** Words around tasks: a title from a goal, a spoken summary from a result. */
public final class TaskText {
    /** Characters of a title. */
    public static final int TITLE = 60;
    /** Characters of a spoken summary. */
    public static final int SUMMARY = 300;
    /** A goal is at most this long. */
    public static final int MAX_GOAL = 500;

    private static final Pattern POLITE = Pattern.compile("^(?:(?:marvin|ok|okay|alors|dis|hey)[ ,]+)*"
            + "(?:(?:est-ce que tu peux|est-ce que tu pourrais|tu peux|tu pourrais|peux-tu|pourrais-tu|can you|could you|"
            + "would you|please|s'il te plaît|stp)[ ,]+)*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private TaskText() {
    }

    /** A short name for a goal: without "Marvin, can you", capitalised, cut on a word. */
    public static String title(String goal) {
        String g = goal == null ? "" : goal.strip().replaceAll("\\s+", " ");
        String t = POLITE.matcher(g).replaceFirst("").strip();
        if (t.isEmpty()) {
            t = g;
        }
        t = t.replaceAll("[\\s?!.]+$", "");
        if (t.codePointCount(0, t.length()) > TITLE) {
            String cut = t.substring(0, t.offsetByCodePoints(0, TITLE));
            int space = cut.lastIndexOf(' ');
            t = (space > TITLE / 2 ? cut.substring(0, space) : cut).replaceAll("[\\s,;:]+$", "") + "…";
        }
        return t.isEmpty() ? "Task" : t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    /**
     * Something Marvin can say from a result in Markdown: its first sentences of plain text, at most {@link #SUMMARY}
     * characters.
     */
    public static String summaryOf(String result) {
        String plain = SpeechText.stripThinking(result == null ? "" : result)
                .replaceAll("(?m)^\\s*#+.*$", "")
                .replaceAll("(?m)^\\s*([-*+]|\\d+[.)])\\s+", "")
                .replaceAll("[*_`|>]", "")
                .replaceAll("\\s+", " ").strip();
        List<String> sentences = SentenceSplitter.split(plain);
        StringBuilder b = new StringBuilder();
        for (String s : sentences) {
            if (b.length() > 0 && b.length() + s.length() + 1 > SUMMARY) {
                break;
            }
            b.append(b.length() > 0 ? " " : "").append(s.strip());
            if (b.length() >= SUMMARY / 2) {
                break;
            }
        }
        String out = b.length() > 0 ? b.toString() : plain;
        return ToolResult.shorten(out, SUMMARY);
    }

    /** At most {@code n} characters, with an ellipsis when cut. */
    public static String cut(String text, int n) {
        return ToolResult.shorten(text == null ? "" : text, n);
    }
}
