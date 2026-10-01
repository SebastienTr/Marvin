// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language of each part of a sentence Marvin says, so the voice reads it with the right pronunciation. The owner
 * mixes French and English, and so do the answers: "C'est une bonne idée, let me know when it's done." is read as a
 * French clause, then an English one, by the same voice.
 *
 * <p>A word-level heuristic: each word is French (a French function word, an elision such as {@code l'}, or an
 * accented letter), English (an English function word or a very common English word), or neither (names, numbers,
 * technical words, words both languages share). A run of at least {@link #MIN_RUN} words of the other language, with
 * no word of the reply's language in it, is cut out as its own clause; isolated anglicisms ("le build", "une code
 * review") stay inside the French clause. A sentence without a single word of the reply's language and with some of
 * the other's is the other's entirely ("Yes, of course.").
 */
public final class SpeechLanguage {
    /** Words of the other language in a row before a sentence is cut there. */
    public static final int MIN_RUN = 3;

    /** One piece of speech and the language it is read in ({@code fr}, {@code en}). */
    public record Clause(String text, String language) {
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}'’\\-]*", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…]");
    private static final Pattern ACCENT = Pattern.compile("[éèêëàâùûüçôîïœæ]");
    private static final Pattern ELISION = Pattern.compile("^(?:l|d|j|qu|n|c|s|m|t|jusqu|lorsqu|puisqu)['’]\\p{L}+$",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final Set<String> FR_WORDS = Set.copyOf(List.of(
            "le", "la", "les", "un", "une", "des", "du", "de", "au", "aux", "et", "ou", "où", "mais", "donc", "or",
            "ni", "car", "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "on", "ce", "cet", "cette", "ces",
            "ça", "cela", "ceci", "que", "qui", "quoi", "dont", "quel", "quelle", "quels", "quelles", "pour", "avec",
            "dans", "sur", "sous", "chez", "vers", "par", "sans", "entre", "depuis", "pendant", "avant", "après",
            "pas", "ne", "plus", "jamais", "rien", "très", "bien", "trop", "peu", "beaucoup", "oui", "non", "si",
            "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "notre", "nos", "votre", "vos", "leur",
            "leurs", "est", "es", "suis", "sommes", "êtes", "sont", "être", "été", "était", "ai", "as", "avons",
            "avez", "ont", "avoir", "eu", "avait", "fait", "faire", "fais", "va", "vais", "vas", "allons", "allez",
            "vont", "peux", "peut", "pouvez", "veux", "veut", "voulez", "dois", "doit", "faut", "comme", "tout",
            "tous", "toute", "toutes", "alors", "aussi", "encore", "déjà", "toujours", "ici", "là", "voilà",
            "merci", "bonjour", "bonsoir", "salut", "aujourd'hui", "demain", "hier", "maintenant", "lui", "moi",
            "toi", "eux", "se", "y", "en", "quand", "comment", "pourquoi", "combien", "parce", "puis", "ensuite",
            "bon", "bonne", "petit", "petite", "grand", "grande", "heure", "heures", "minutes", "jour", "jours",
            "chose", "choses", "temps", "fois", "ans", "dis", "dit", "sais", "sait", "crois", "pense", "regarde",
            "attends", "écoute", "tiens", "quelque", "chaque", "autre", "autres", "même", "mêmes", "sûr", "vraiment",
            "juste", "assez", "presque", "environ", "degrés", "c'est", "j'ai", "n'est", "qu'il", "d'accord",
            // shared with English: they show neither language
            "a", "me", "an", "but", "minute", "minutes", "on", "plus", "type", "pause", "message"));
    private static final Set<String> EN_WORDS = Set.copyOf(List.of(
            "the", "a", "an", "and", "or", "but", "so", "if", "then", "than", "because", "of", "to", "in", "on",
            "at", "by", "for", "with", "from", "about", "into", "over", "after", "before", "up", "out", "off",
            "i", "i'm", "i've", "i'll", "i'd", "you", "you're", "you've", "you'll", "your", "yours", "he", "she",
            "it", "it's", "its", "we", "we're", "our", "they", "they're", "their", "them", "him", "his", "her",
            "my", "me", "us", "this", "that", "that's", "these", "those", "there", "there's", "here", "what",
            "what's", "how", "why", "when", "where", "who", "which", "is", "are", "was", "were", "be", "been",
            "being", "am", "do", "does", "did", "don't", "doesn't", "didn't", "have", "has", "had", "haven't",
            "will", "would", "won't", "can", "can't", "could", "should", "shall", "may", "might", "must", "not",
            "no", "yes", "yeah", "let", "let's", "know", "think", "want", "need", "like", "get", "got", "go",
            "going", "make", "made", "take", "see", "look", "tell", "say", "said", "just", "very", "really", "all",
            "some", "any", "more", "most", "much", "many", "also", "too", "still", "again", "now", "today",
            "tomorrow", "yesterday", "good", "great", "well", "thanks", "thank", "please", "sorry", "okay",
            "time", "day", "way", "thing", "things", "one", "something", "nothing", "everything", "anything",
            "isn't", "aren't", "wasn't", "weren't", "doing", "done", "work", "working", "right", "sure", "course",
            "maybe", "only", "even", "every", "other", "another", "same", "new", "old", "first", "last", "next",
            "little", "lot", "bit", "minute", "minutes", "hour", "hours", "degrees", "weather", "fine", "happy",
            "hello", "hi", "hey", "bye", "goodbye", "welcome", "should've", "would've", "they've", "we've",
            "type", "pause", "message", "plus"));
    /** Words both lists have ("on", "son", "plus", "minutes"...): they say nothing about the language. */
    private static final Set<String> SHARED;

    static {
        Set<String> s = new HashSet<>(FR_WORDS);
        s.retainAll(EN_WORDS);
        SHARED = Set.copyOf(s);
    }

    private SpeechLanguage() {
    }

    /** The language a single word shows: {@code fr}, {@code en}, or {@code null} when it shows none. */
    static String wordLanguage(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (SHARED.contains(w)) {
            return null;
        }
        if (FR_WORDS.contains(w) || ELISION.matcher(w).matches() || ACCENT.matcher(w).find()) {
            return "fr";
        }
        if (EN_WORDS.contains(w)) {
            return "en";
        }
        return null;
    }

    private record Word(int start, int end, String language) {
    }

    /**
     * {@code sentence} cut where it changes language, each part with its language. {@code language}: the reply's
     * ({@code fr} or {@code en}); other languages, and an empty sentence, give one clause as it is.
     */
    public static List<Clause> clauses(String sentence, String language) {
        String text = sentence == null ? "" : sentence.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        if (!"fr".equals(language) && !"en".equals(language)) {
            return List.of(new Clause(text, language));
        }
        String other = "fr".equals(language) ? "en" : "fr";
        List<Word> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        int own = 0;
        int others = 0;
        while (m.find()) {
            String lang = wordLanguage(m.group());
            words.add(new Word(m.start(), m.end(), lang));
            if (language.equals(lang)) {
                own++;
            } else if (other.equals(lang)) {
                others++;
            }
        }
        if (own == 0) {
            return List.of(new Clause(text, others > 0 ? other : language));
        }
        // runs of words that are not the reply's language, holding at least MIN_RUN words of the other one
        List<int[]> runs = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            if (language.equals(words.get(i).language())) {
                i++;
                continue;
            }
            int j = i;
            int count = 0;
            while (j < words.size() && !language.equals(words.get(j).language())) {
                if (other.equals(words.get(j).language())) {
                    count++;
                }
                j++;
            }
            if (count >= MIN_RUN) {
                // the run starts at its first word of the other language: words that show none before it stay
                // with what precedes, unless the run opens the sentence
                int a = i;
                while (a < j && !other.equals(words.get(a).language()) && i > 0) {
                    a++;
                }
                // it ends after its last word of the other language, and the words that show none after it up to
                // the end of the sentence
                int b = j;
                while (b > a && !other.equals(words.get(b - 1).language())) {
                    b--;
                }
                while (b < j && !SENTENCE_END.matcher(text.substring(words.get(b - 1).end(), words.get(b).start())).find()) {
                    b++;
                }
                runs.add(new int[] {a, b});
            }
            i = j;
        }
        if (runs.isEmpty()) {
            return List.of(new Clause(text, language));
        }
        List<Clause> out = new ArrayList<>();
        int from = 0;
        for (int[] r : runs) {
            int start = words.get(r[0]).start();
            int end = r[1] < words.size() ? words.get(r[1]).start() : text.length();
            add(out, text.substring(from, start), language);
            add(out, text.substring(start, end), other);
            from = end;
        }
        add(out, text.substring(from), language);
        return List.copyOf(out);
    }

    private static void add(List<Clause> out, String part, String language) {
        String t = part.strip();
        if (t.isEmpty() || t.chars().noneMatch(Character::isLetterOrDigit)) {
            if (!t.isEmpty() && !out.isEmpty()) {          // a lone "." or "?" goes with the clause before it
                Clause last = out.remove(out.size() - 1);
                out.add(new Clause(last.text() + t, last.language()));
            }
            return;
        }
        out.add(new Clause(t, language));
    }
}
