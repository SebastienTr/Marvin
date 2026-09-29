// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How memory is written for the model and the owner: facts with their validity, dates in words, summaries cut into
 * sentences, and a plain word overlap to rank sentences and log lines without a model.
 */
public final class MemoryText {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=\\p{Lu}|\\d|\")");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "has", "have", "are", "was",
            "his", "her", "its", "they", "their", "from", "who", "what", "when", "where", "which", "about", "did", "does",
            "you", "your", "les", "des", "une", "est", "que", "qui", "quoi", "pour", "avec", "dans", "sur", "mon", "ton", "son",
            "mes", "tes", "ses", "est-ce", "quand", "comment", "tu", "je", "il", "elle", "nous", "vous", "moi", "toi", "pas",
            "owner", "person", "remember", "souviens", "rappelle");

    private MemoryText() {
    }

    public static String date(Instant t, ZoneId zone) {
        return DATE.format(t.atZone(zone));
    }

    public static String date(LocalDate d) {
        return DATE.format(d);
    }

    /**
     * A fact as a line of the question's context: its statement, with its validity when it is not open-ended
     * ("(from 12 October 2026)", "(until 3 March 2027)").
     */
    public static String contextLine(Fact f, Instant now, ZoneId zone) {
        List<String> hints = new ArrayList<>();
        if (f.validFrom() != null && f.validFrom().isAfter(now)) {
            hints.add("from " + date(f.validFrom(), zone));
        }
        if (f.validTo() != null) {
            hints.add("until " + date(f.validTo(), zone));
        }
        return hints.isEmpty() ? f.statement() : f.statement() + " (" + String.join(", ", hints) + ")";
    }

    /** A fact's standing at {@code now}: {@code current}, {@code past}, {@code replaced} or {@code archived}. */
    public static String status(Fact f, Instant now) {
        if (f.supersededBy() != null) {
            return "replaced";
        }
        if (!f.current(now)) {
            return "past";
        }
        return f.archived() ? "archived" : "current";
    }

    /** When a fact held, in words ("since 3 March 2026", "from 1 May 2025 to 2 June 2026", "learned 3 March 2026"). */
    public static String validity(Fact f, ZoneId zone) {
        Instant end = f.validTo() != null ? f.validTo() : f.expiredAt();
        if (f.validFrom() != null && end != null) {
            return "from " + date(f.validFrom(), zone) + " to " + date(end, zone);
        }
        if (f.validFrom() != null) {
            return "since " + date(f.validFrom(), zone);
        }
        if (end != null) {
            return "until " + date(end, zone) + " (learned " + date(f.learnedAt(), zone) + ")";
        }
        return "learned " + date(f.learnedAt(), zone);
    }

    /** A summary cut into sentences. */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String s : SENTENCE_END.split(text.strip())) {
            String t = s.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** The content words of a text: lower case, without accents, three letters or more, no common words. */
    public static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        String norm = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        for (String w : norm.split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) {
                out.add(w.length() > 4 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w);
            }
        }
        return out;
    }

    /** Words to search stored text with (as written, accents kept, lower case), longest first. */
    public static List<String> searchTerms(String text, int n) {
        Set<String> out = new LinkedHashSet<>();
        if (text != null) {
            for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                String plain = Normalizer.normalize(w, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                if (w.length() >= 3 && !STOP.contains(plain)) {
                    out.add(w);
                }
            }
        }
        return out.stream().sorted(java.util.Comparator.comparingInt(String::length).reversed()).limit(n).toList();
    }

    /** The share of the query's content words found in the text, 0 to 1 (0 for a query without content words). */
    public static double overlap(Set<String> query, String text) {
        if (query.isEmpty()) {
            return 0;
        }
        Set<String> t = words(text);
        long n = query.stream().filter(t::contains).count();
        return (double) n / query.size();
    }
}
