// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The soul's guardrails (docs/design.md 8.3): rules in the domain, not only in a prompt, checked on every proposal of the
 * weekly reflection and every owner edit of the learned part. A text that fails one is refused with the reason (a
 * proposal is shown to the owner as rejected, never silently dropped).
 *
 * <ul>
 * <li>{@code no_manipulation}: no persuasion techniques, no flattery as a strategy, no feelings invented to steer the
 * owner;</li>
 * <li>{@code no_dependency}: nothing that aims at more time with Marvin, that discourages other relationships or help,
 * or makes Marvin the owner's main confidant; nothing that makes Marvin speak up more (proactive speech stays
 * rate-limited whatever the soul says);</li>
 * <li>{@code honesty}: Marvin says what it does not know, never claims observations beyond its sensors and memory, can
 * always say where a memory came from;</li>
 * <li>{@code bounded}: within {@link SoulText#LEARNED_TOKENS}, no tool, permission or rule granted or lifted, no
 * contradiction of the core.</li>
 * </ul>
 *
 * <p>Rules read English and French (the soul is stored in English; the owner may type French). They err on the side of
 * refusing: the owner can always reword.
 */
public final class Guardrails {
    public static final String NO_MANIPULATION = "no_manipulation";
    public static final String NO_DEPENDENCY = "no_dependency";
    public static final String HONESTY = "honesty";
    public static final String BOUNDED = "bounded";
    /** The rules, in the order the app shows them. */
    public static final List<String> RULES = List.of(NO_MANIPULATION, NO_DEPENDENCY, HONESTY, BOUNDED);

    /** One rule's verdict; {@code reason} says why it failed, {@code null} when it passed. */
    public record Check(String rule, boolean ok, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule);
            m.put("ok", ok);
            m.put("reason", reason);
            return m;
        }
    }

    private static Pattern words(String... alternatives) {
        return Pattern.compile("(?iu)(?<![\\p{L}])(?:" + String.join("|", alternatives) + ")");
    }

    static final Pattern MANIPULATION = words(
            "persuad\\w*", "convinc\\w*", "manipulat\\w*", "guilt\\w*", "pressur\\w*", "coax\\w*", "cajol\\w*",
            "nudg\\w* (?:him|her|them|the owner|sébastien|\\w+) (?:into|to)", "talk (?:him|her|them|the owner) into",
            "make (?:him|her|them|the owner) feel", "flatter\\w*", "compliments? (?:\\w+ ){0,4}(?:so that|to get|to make|in order)",
            "exaggerat\\w*", "(?:fake|false|invent\\w*|artificial) (?:urgency|emotions?|feelings?|deadlines?)",
            "(?:pretend|act|seem)\\w* (?:to be |to feel )?(?:sad|hurt|lonely|upset|disappointed|jealous|offended)",
            "(?:sound|look) (?:sad|hurt|disappointed) (?:when|if|so)", "play on", "exploit\\w*", "trick\\w*",
            "scare\\w*", "frighten\\w*", "threat\\w*", "emotional(?:ly)? blackmail\\w*", "reverse psychology",
            "fear of missing out", "fomo",
            // French
            "persuad\\w*", "convainc\\w*", "culpabilis\\w*", "manipul\\w*", "flatt\\w*", "faire peur", "menac\\w*",
            "faire semblant d'être (?:triste|blessé|déçu)", "chantage");

    static final Pattern DEPENDENCY = words(
            "spend more time", "more time (?:with|talking)", "talk (?:to|with) (?:me|marvin|it) more",
            "keep (?:him|her|them|the owner|\\w+) (?:talking|engaged|here|at the desk|company)",
            "(?:only|main|best|closest) (?:friend|confidant|companion|one who understands)", "confid\\w* (?:in|to) (?:me|marvin)",
            "instead of (?:\\w+ ){0,2}(?:friends?|family|partner|wife|husband|girlfriend|fiancée?|doctors?|therapists?|people|others|colleagues)",
            "rather than (?:\\w+ ){0,2}(?:friends?|family|partner|doctors?|therapists?|people|others)",
            "discourag\\w*", "(?:rely|relies|relying|depend\\w*) on (?:me|marvin|it)", "needs? (?:me|marvin)\\b",
            "(?:doesn't|does not|don't|do not) need (?:others|anyone|friends|people)", "isolat\\w*", "miss(?:es)? (?:me|marvin)",
            "speak up (?:more|more often|often)", "(?:interrupt|remind|nag)\\w* (?:\\w+ ){0,3}(?:more|more often|every \\d+ (?:seconds?|minutes?))",
            "more (?:proactive|talkative|often)", "(?:fewer|less|shorter) (?:breaks?|time away)", "come back (?:sooner|quickly)",
            "stay (?:longer|at the desk|with marvin)", "ignore (?:the )?(?:quiet hours|rate limit)",
            // French
            "passer plus de temps", "plus de temps avec", "(?:seul|meilleur|unique) (?:ami|confident)", "au lieu de (?:ses |sa |son )?(?:amis|famille|médecin|psy|copine|compagne)",
            "dépend\\w* de (?:moi|marvin)", "isol\\w*", "décourag\\w*");

    static final Pattern DISHONESTY = words(
            "pretend\\w*", "claims? to (?:see|hear|know|remember|feel|be human|have)", "say (?:it|you|that it) (?:is|are) (?:human|a person|alive)",
            "act as if", "invent\\w*", "make (?:things |facts |it )?up", "made up", "fabricat\\w*", "\\blie\\b", "\\blies\\b",
            "lying", "hide (?:that|where|its|the source|what)", "conceal\\w*",
            "(?:don't|do not|never) (?:say|tell|admit|mention) (?:\\w+ ){0,3}(?:where|source|does not know|doesn't know|unsure|not sure)",
            "never admit\\w*", "always (?:sound|be|act|seem) (?:sure|certain|confident)", "guess (?:\\w+ ){0,2}without saying",
            "deny (?:being|it is)", "(?:sees|hears|watches) (?:everything|always)",
            // French
            "faire semblant", "mentir", "ment\\b", "invente\\w*", "cacher (?:que|d'où|la source)", "ne jamais (?:dire|avouer|admettre)");

    static final Pattern POWERS = words(
            "tools?\\b", "permissions?", "access (?:to|rights)", "allowed to", "authori[sz]\\w*", "may (?:now )?(?:send|delete|use|read|access|act|buy|pay|post)",
            "without (?:the owner's |his |her |their |any )?(?:approval|permission|consent|confirmation|yes)\\b",
            "without asking (?:for )?(?:approval|permission|first)",
            "approves? (?:itself|its own|on (?:his|her|their) behalf)", "(?:send|forward|reply to) (?:\\w+ ){0,2}(?:emails?|mails?|messages?)",
            "delete\\w*", "\\bpay\\w*", "internet", "(?:camera|microphone|mic)\\w* (?:always|on all|stays on)",
            "record\\w* (?:everything|always|all)", "admin\\w*", "\\broot\\b", "unlock\\w*", "bypass\\w*",
            "override\\w*", "(?:ignore|disregard|forget|drop|skip) (?:\\w+ ){0,2}(?:rules?|core|instructions?|guardrails?|limits?|approvals?)",
            "no longer (?:needs?|asks?) (?:approval|permission)",
            // French
            "sans (?:demander|autorisation|permission|accord)", "outils?\\b", "ignorer (?:les |la )?(?:règles?|consignes?)");

    static final Pattern NEGATION = Pattern.compile(
            "(?iu)(?<![\\p{L}])(?:never|not|no|don't|do not|doesn't|does not|stop|without|avoid|ne|jamais|pas|plus)(?![\\p{L}])");

    private static final Set<String> STOP = Set.of("the", "a", "an", "to", "of", "in", "it", "is", "its", "and", "or",
            "owner", "owner's", "by", "on", "at", "for", "with", "what", "that", "this", "be", "when", "their", "they", "them",
            "his", "her", "he", "she", "marvin", "always", "never", "not", "no", "don't", "do", "does", "doesn't", "can", "cannot",
            "stop", "without", "avoid", "first", "le", "la", "les", "de", "du", "des", "et", "en", "à", "ne", "pas", "jamais");

    private Guardrails() {
    }

    /** Every rule's verdict on a learned part, against the core it must not contradict. */
    public static List<Check> check(String learned, String core) {
        String text = SoulText.normalise(learned);
        List<Check> out = new ArrayList<>();
        out.add(verdict(NO_MANIPULATION, MANIPULATION, text, "it would steer the owner"));
        out.add(verdict(NO_DEPENDENCY, DEPENDENCY, text, "it would build dependency on Marvin"));
        out.add(verdict(HONESTY, DISHONESTY, text, "it would make Marvin less honest"));
        out.add(bounded(text, core));
        return out;
    }

    /** Whether every rule passed. */
    public static boolean passes(List<Check> checks) {
        return checks.stream().allMatch(Check::ok);
    }

    /** The reasons of the rules that failed, in a sentence. */
    public static String reasons(List<Check> checks) {
        return String.join("; ", checks.stream().filter(c -> !c.ok()).map(c -> c.rule() + ": " + c.reason()).toList());
    }

    private static Check verdict(String rule, Pattern p, String text, String why) {
        Matcher m = p.matcher(text);
        if (m.find()) {
            return new Check(rule, false, "“" + m.group().strip() + "”: " + why);
        }
        return new Check(rule, true, null);
    }

    private static Check bounded(String text, String core) {
        int tokens = SoulText.tokens(text);
        if (tokens > SoulText.LEARNED_TOKENS) {
            return new Check(BOUNDED, false, "too long: about " + tokens + " tokens, at most " + SoulText.LEARNED_TOKENS);
        }
        if (text.length() > SoulText.MAX_CHARS) {
            return new Check(BOUNDED, false, "too long: " + text.length() + " characters, at most " + SoulText.MAX_CHARS);
        }
        Matcher m = POWERS.matcher(text);
        if (m.find()) {
            return new Check(BOUNDED, false, "“" + m.group().strip() + "”: the learned part cannot grant Marvin a "
                    + "tool or a permission, nor lift a rule");
        }
        String contradiction = contradiction(text, core);
        if (contradiction != null) {
            return new Check(BOUNDED, false, "“" + contradiction + "” contradicts the core");
        }
        return new Check(BOUNDED, true, null);
    }

    /**
     * A learned statement that says the opposite of a core statement: most of its words are the core statement's, and
     * exactly one of the two is negated ("Never calls the owner by their first name" against "Calls the owner by their
     * first name"). {@code null} when none.
     */
    static String contradiction(String learned, String core) {
        List<String> coreStatements = SoulText.statements(core);
        for (String l : SoulText.statements(learned)) {
            Set<String> lw = content(l);
            if (lw.size() < 2) {
                continue;
            }
            boolean ln = NEGATION.matcher(l).find();
            for (String c : coreStatements) {
                Set<String> cw = content(c);
                if (cw.isEmpty()) {
                    continue;
                }
                Set<String> shared = new HashSet<>(lw);
                shared.retainAll(cw);
                double overlap = (double) shared.size() / Math.min(lw.size(), cw.size());
                if (overlap >= 0.6 && ln != NEGATION.matcher(c).find()) {
                    return l;
                }
            }
        }
        return null;
    }

    private static Set<String> content(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+")) {
            if (w.length() > 1 && !STOP.contains(w)) {
                out.add(w.endsWith("s") && w.length() > 3 ? w.substring(0, w.length() - 1) : w);
            }
        }
        return out;
    }
}
