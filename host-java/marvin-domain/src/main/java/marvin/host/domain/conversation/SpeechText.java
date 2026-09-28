// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import marvin.host.domain.shared.JsonText;

/**
 * What a model writes, made fit to be spoken (the Python host's {@code voice/text.py}): tool calls written as
 * text, reasoning written into the answer, markdown, URLs and emoji.
 *
 * <p>A model given tools sometimes writes the call as text (a JSON object, {@code <tool_call>} tags)
 * instead of calling it: {@link #payloadToolCalls} reads such calls back, and {@link #cleanForSpeech}
 * never lets JSON or tool-call markup reach the speaker.
 */
public final class SpeechText {
    static final int U = Pattern.UNICODE_CHARACTER_CLASS;

    /** The end of reasoning written into an answer: {@code </think>}, possibly unfinished. */
    public static final Pattern THINK_END = Pattern.compile("</think\\b\\s*>?", U);
    public static final String THINK_TAG = "</think>";
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>.*?</think\\s*>?", U | Pattern.DOTALL);

    private static final Pattern TOOL_TAG = Pattern.compile(
            "<\\|?/?(tool_call|tool_calls|function_call|functions?)\\|?>", U | Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACES = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern JSON_KEY = Pattern.compile("\"[A-Za-z_][\\w-]*\"\\s*:\\s*[\"{\\[\\d-]", U);
    private static final Pattern EMPTY_LIST = Pattern.compile("\\[[\\s,]*\\]", U);
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{200D}\\x{FE0F}]");
    private static final Pattern CODE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+", U);
    private static final Pattern LIST_MARK = Pattern.compile("^\\s*(#+|[-*•]|\\d+[.)])\\s+", U | Pattern.MULTILINE);
    private static final Pattern MARKUP = Pattern.compile("[*_`#~|>]+");
    private static final Pattern BLANKS = Pattern.compile("[ \\t]+");
    /** How tool calls written as text begin: a reply starting with a prefix of one of these waits. */
    private static final List<String> OPENINGS = List.of("<tool_call>", "<|tool_call|>", "<function_call>",
            "<functions>", "```");

    private SpeechText() {
    }

    /**
     * Whether a reply starts like a tool call written as text (JSON, a code block, a tag) rather than like
     * a sentence, decided on its first characters so the reply can be held back. {@code null}: not sure
     * yet ({@code "<tool_c"} may become a tag), wait for more.
     */
    public static Boolean looksLikePayload(String text) {
        String t = text.stripLeading();
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("{") || t.startsWith("[") || t.startsWith("```") || TOOL_TAG.matcher(t).lookingAt()) {
            return true;
        }
        for (String o : OPENINGS) {
            if (o.startsWith(t) && t.length() < o.length()) {
                return null;
            }
        }
        return false;
    }

    /**
     * {@code text} without JSON objects and tool-call tags; {@code ""} if what is left still looks like a
     * piece of JSON (a streamed chunk can hold half an object).
     */
    public static String stripPayload(String text) {
        String t = TOOL_TAG.matcher(text).replaceAll(" ");
        while (true) {
            String n = BRACES.matcher(t).replaceAll(" ");
            if (n.equals(t)) {
                break;
            }
            t = n;
        }
        if (t.indexOf('{') >= 0 || t.indexOf('}') >= 0 || JSON_KEY.matcher(t).find()) {
            return "";
        }
        return EMPTY_LIST.matcher(t).replaceAll(" ");
    }

    /**
     * Tool calls written as text: every JSON object in {@code text} (bare, in a code block or between
     * {@code <tool_call>} tags, or a list of them) that has a {@code "name"} (or a {@code "function"}).
     */
    public static List<Map<String, Object>> payloadToolCalls(String text) {
        String t = TOOL_TAG.matcher(CODE_FENCE.matcher(text).replaceAll("\n")).replaceAll("\n");
        List<Map<String, Object>> found = new ArrayList<>();
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c != '{' && c != '[') {
                i++;
                continue;
            }
            JsonText.Decoded d;
            try {
                d = JsonText.decode(t, i);
            } catch (IllegalArgumentException e) {
                i++;
                continue;
            }
            List<?> items = d.value() instanceof List<?> l ? l : List.of(d.value());
            for (Object o : items) {
                if (o instanceof Map<?, ?> m && (m.get("name") instanceof String || m.get("function") instanceof Map)) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    found.add(copy);
                }
            }
            i = d.end();
        }
        return found;
    }

    /**
     * Drops reasoning a model wrote into its answer: {@code <think>…</think>} blocks, and everything before
     * a lone {@code </think>} (after a tool result, some models think in the answer itself even with
     * thinking off, then close the tag and answer again).
     */
    public static String stripThinking(String text) {
        String t = THINK_BLOCK.matcher(text).replaceAll("");
        String[] parts = THINK_END.split(t, -1);
        return parts.length > 1 ? parts[parts.length - 1].stripLeading() : t;
    }

    /** Removes markdown, URLs, emoji and anything that looks like JSON or a tool call. */
    public static String cleanForSpeech(String text) {
        String t = THINK_END.matcher(THINK_BLOCK.matcher(text).replaceAll(" ")).replaceAll(" ");
        t = CODE_BLOCK.matcher(t).replaceAll(" ");
        t = stripPayload(t);
        t = LINK.matcher(t).replaceAll("$1");
        t = URL.matcher(t).replaceAll("");
        t = LIST_MARK.matcher(t).replaceAll("");
        t = MARKUP.matcher(t).replaceAll("");
        t = EMOJI.matcher(t).replaceAll("");
        return BLANKS.matcher(t).replaceAll(" ").strip();
    }

    // ------------------------------------------------------------------ language

    private static final Map<String, Set<String>> STOPWORDS = Map.of(
            "fr", Set.of("je", "tu", "il", "elle", "nous", "vous", "le", "la", "les", "un", "une", "des", "est", "et",
                    "pas", "ne", "de", "du", "que", "qui", "pour", "avec", "mais", "sur", "dans", "mes", "ton",
                    "c'est", "j'ai", "oui", "non", "suis", "moi", "toi", "ça", "en", "au", "aux"),
            "en", Set.of("i", "you", "he", "she", "we", "they", "the", "a", "an", "is", "are", "and", "not", "do",
                    "of", "to", "that", "for", "with", "but", "on", "in", "my", "your", "it's", "i'm", "yes",
                    "no", "am", "me", "it", "this", "have", "get"));
    private static final Pattern WORD = Pattern.compile("[a-zA-Zà-ÿÀ-Ÿ']+");

    /** The candidate language whose common words dominate {@code text}, or {@code null} when unsure. */
    public static String guessLanguage(String text, List<String> candidates) {
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        while (m.find()) {
            words.add(m.group());
        }
        String best = null;
        int bestScore = -1;
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String lang : candidates) {
            Set<String> sw = STOPWORDS.getOrDefault(lang, Set.of());
            int s = (int) words.stream().filter(sw::contains).count();
            scores.put(lang, s);
            if (s > bestScore) {
                best = lang;
                bestScore = s;
            }
        }
        int others = 0;
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            if (!e.getKey().equals(best)) {
                others = Math.max(others, e.getValue());
            }
        }
        return best != null && bestScore >= 2 && bestScore >= 2 * others ? best : null;
    }
}
