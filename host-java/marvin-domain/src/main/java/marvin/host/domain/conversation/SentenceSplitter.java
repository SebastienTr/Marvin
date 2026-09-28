// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a streamed reply into sentences as it arrives (the Python host's {@code text.SentenceSplitter}), so
 * the first sentence is spoken while the model still writes the second.
 *
 * <p>A sentence ends at {@code . ! ?} or {@code …} followed by a space (not "3.5", not "M. Dupont"), or at a
 * line break. Sentences shorter than {@code minChars} are joined with the next one, except the very first,
 * which is released as soon as possible: it may be a clause, cut at a comma, semicolon or colon once it has
 * {@code firstClauseWords} words (0 disables it), and it does not even wait for the space after its
 * punctuation.
 */
public final class SentenceSplitter {
    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;
    /** "M. Dupont", "Dr. Who", "etc. " would otherwise end a sentence. */
    static final Set<String> ABBREVIATIONS = Set.of("m", "mm", "mme", "mlle", "dr", "pr", "st", "ste", "mr", "mrs",
            "ms", "prof", "etc", "vs", "cf", "ex", "p", "env", "approx", "no", "n°", "e.g", "i.e");
    private static final Pattern END = Pattern.compile("([.!?…]+[\"»”')\\]]*)(\\s+)|(\\n+)", U);
    private static final Pattern CLAUSE = Pattern.compile("(?<=[^\\d\\s])([,;:—])(\\s+|$)", U);
    private static final Pattern FIRST_END = Pattern.compile("(?<=[^\\d\\s])([.!?…]+[\"»”')\\]]*)$", U);
    private static final Pattern LAST_WORD = Pattern.compile("[\\w°]+$", U);
    private static final Pattern WORD_BEFORE_END = Pattern.compile("[\\w°]+(?=[.!?…]+\\W*$)", U);
    private static final Pattern WORDS = Pattern.compile("\\w+", U);

    private final int minChars;
    private final int firstClauseWords;
    private String buf = "";
    private String pending = "";
    private int emitted;

    public SentenceSplitter() {
        this(12, 3);
    }

    public SentenceSplitter(int minChars, int firstClauseWords) {
        this.minChars = minChars;
        this.firstClauseWords = firstClauseWords;
    }

    /** Feeds a piece of the stream; returns the sentences it completed. */
    public List<String> feed(String piece) {
        buf += piece;
        List<String> out = new ArrayList<>();
        int start = 0;
        Matcher m = END.matcher(buf);
        while (m.find()) {
            int end;
            if (m.group(3) == null) {
                Matcher w = LAST_WORD.matcher(buf.substring(start, m.start(1)));
                if (m.group(1).equals(".") && w.find() && ABBREVIATIONS.contains(w.group().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                end = m.end(1);
            } else {
                end = m.start(3);
            }
            String s = buf.substring(start, end).strip();
            start = m.end();
            if (!s.isEmpty()) {
                out.addAll(push(s));
            }
        }
        buf = buf.substring(start);
        if (emitted == 0 && out.isEmpty() && firstClauseWords > 0) {
            Matcher f = FIRST_END.matcher(buf);
            List<String> words = new ArrayList<>();
            Matcher wm = WORD_BEFORE_END.matcher(buf);
            while (wm.find()) {
                words.add(wm.group());
            }
            if (f.find()) {
                String last = words.isEmpty() ? null : words.get(words.size() - 1);
                boolean doubtful = f.group(1).startsWith(".") && (last == null
                        || ABBREVIATIONS.contains(last.toLowerCase(Locale.ROOT)) || last.codePointCount(0, last.length()) < 2);
                if (!doubtful) {
                    String head = buf.strip();
                    buf = "";
                    return push(head);
                }
            }
            Matcher c = CLAUSE.matcher(buf);
            while (c.find()) {
                String head = buf.substring(0, c.end(1)).strip();
                Matcher n = WORDS.matcher(head);
                int count = 0;
                while (n.find()) {
                    count++;
                }
                if (count >= firstClauseWords) {
                    buf = buf.substring(c.end());
                    out.addAll(push(head));
                    break;
                }
            }
        }
        return out;
    }

    /** The rest, at the end of the stream. */
    public List<String> flush() {
        String s = (pending + " " + buf).strip();
        buf = "";
        pending = "";
        return s.isEmpty() ? List.of() : List.of(s);
    }

    private List<String> push(String sentence) {
        String s = (pending + " " + sentence).strip();
        if (s.codePointCount(0, s.length()) < minChars && emitted > 0) {
            pending = s;
            return List.of();
        }
        pending = "";
        emitted++;
        return List.of(s);
    }

    /** Every sentence of a whole text. */
    public static List<String> split(String text) {
        SentenceSplitter sp = new SentenceSplitter(0, 0);
        List<String> out = new ArrayList<>(sp.feed(text));
        out.addAll(sp.flush());
        return out;
    }
}
