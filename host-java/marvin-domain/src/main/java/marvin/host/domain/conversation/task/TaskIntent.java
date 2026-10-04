// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A light guard beside the model's own choice ({@code start_task}): words that ask for a background task outright
 * ("fais-le en tâche de fond", "in the background"), which start one without asking the model, and words that usually
 * announce work in several steps ("prépare-moi", "compare", "look into", "summarize my week"), which only add a hint to
 * the question: the model still decides.
 */
public final class TaskIntent {
    /** "In the background", said outright, in French or English (accents and case ignored). */
    static final Pattern EXPLICIT = Pattern.compile("\\b(?:en tache de fond|en arriere[- ]plan|en tache d'arriere[- ]plan"
            + "|in the background|as a background task|as a task in the background|background task)\\b");
    /** What is left of a request that is only "do it in the background": the goal is the question before. */
    static final Pattern ONLY_THAT = Pattern.compile("^(?:(?:marvin|ok|okay|alors|bon|dis)[ ,]*)*(?:(?:fais|fait|fais-le|"
            + "fais le|fais ca|lance|lance-le|mets|mets-le|mets le|fais-moi ca|do|do it|do that|run it|run that|put it|"
            + "please|stp|s'il te plait|plutot|instead|alors)[ ,]*)*$");
    /** Words that usually announce work in several steps. */
    static final Pattern LIKELY = Pattern.compile("\\b(?:prepare[- ]moi|prepare[sz]?|preparer|compare[rsz]?|comparatif"
            + "|renseigne[- ]toi|fais (?:des|une) recherches?|recherche[- ]moi|fais le point|fais un point|resume[- ]moi"
            + "|resume (?:ma|mon|mes|la|le|les) (?:semaine|mois|journee|journees|derniers|dernieres)|planifie|organise"
            + "|fais[- ]moi (?:une|un) (?:liste|plan|synthese|bilan|comparatif)|look into|research|find out|compare"
            + "|prepare|put together|summari[sz]e my|summari[sz]e (?:the|this|last) (?:week|month|days)|plan (?:my|a|the)"
            + "|draw up|make (?:me )?a (?:plan|list|comparison|summary))\\b");

    private TaskIntent() {
    }

    /** Lower case, accents removed, apostrophes straightened, spaces collapsed. */
    static String plain(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").strip();
    }

    /** The person asked outright for a background task. */
    public static boolean explicit(String text) {
        return EXPLICIT.matcher(plain(text)).find();
    }

    /** Words that usually announce work in several steps (a hint for the model, never a decision). */
    public static boolean likely(String text) {
        return LIKELY.matcher(plain(text)).find();
    }

    /**
     * The goal of a request that asked outright for a background task: the request without "in the background", or the
     * question before ({@code previous}) when nothing else is left ("fais-le en tâche de fond"); {@code null} when there
     * is no goal at all.
     */
    public static String goal(String text, String previous) {
        String t = text == null ? "" : text.strip();
        Matcher m = EXPLICIT.matcher(plain(t));
        String rest = plain(m.replaceAll(" ")).replaceAll("[\\s,.;:!?]+", " ").strip();
        if (rest.isEmpty() || ONLY_THAT.matcher(rest).matches()) {
            String p = previous == null ? "" : previous.strip();
            return p.isEmpty() ? null : p;
        }
        return t;
    }
}
